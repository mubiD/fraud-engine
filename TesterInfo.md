# TesterInfo — Fraud Rule Engine

**Audience:** QA/Tester preparing a test plan, test cases, and test scenarios.

---

## 1. What This Service Is

This is an **asynchronous, post-authorisation** transaction fraud detection engine for Acme Bank — it evaluates a transaction *after* it has already happened, not as a blocking gate before authorisation. It evaluates financial transactions against **12** rule-based fraud detectors, persists the results, and routes outcomes downstream. The service is built on Spring Boot 3.3 / Java 21.

In production-like environments (`int`, `qa`, `load`, `prod`), transactions enter **exclusively** via Kafka — there is no HTTP endpoint to submit a transaction, and the query API is read-only. The one exception is described in §2 below.

---

## 2. How Transactions Enter the System

| Path | Active When |
|---|---|
| **Kafka topic** `transactions.raw` (Protobuf) | `int`, `qa`, `load`, `prod` — the real production path |
| **HTTP POST** `/api/v1/standalone/submit` or `/api/v1/standalone/stream` | `standalone` and `local` Spring profiles only — an explicit demo/dev stub, not a production feature |

**Verified directly from the docker-compose files** (this matters — don't infer it from environment names):

| `make` target | Environment name | `SPRING_PROFILES_ACTIVE` |
|---|---|---|
| `make dev` | dev | **`local`** |
| `make int` | int | `int` |
| `make qa` | qa | `qa` |
| `make load` | load | `load` |
| `make prod` | prod | `prod` |

So **`make dev` is the one environment where the standalone HTTP endpoints are reachable** — its Spring profile is `local`, not a profile literally named `dev`. `int`/`qa`/`load`/`prod` all run their own matching profile name, none of which is `local`/`standalone`/`test`, so the real Kafka consumer pipeline is active there and the standalone controller is not wired in at all (`@Profile("standalone | local")` on `StandaloneTransactionController`).

The `standalone` profile (not tied to any `make` target — run manually with `--spring.profiles.active=standalone`) uses an **H2 in-memory database** (no PostgreSQL, no Kafka, no Vault) — the quickest way to exercise the rule engine with zero infrastructure. `local` (i.e. `make dev`) uses real PostgreSQL + Kafka with JSON wire format (no Confluent Schema Registry needed).

---

## 3. Environments and Ports

| Environment | App (host) | PostgreSQL (host) | Kafka Broker 1/2/3 (host) |
|---|---|---|---|
| `dev` | **8081** | 5433 | 9192 / 9193 / 9194 |
| `int` | 8082 | 5434 | 9292 / 9293 / 9294 |
| `qa` | 8083 | 5435 | 9392 / 9393 / 9394 |
| `load` | 8084 | 5436 | 9492 / 9493 / 9494 |
| `prod` | 8085 | 5437 | 9592 / 9593 / 9594 |

Container-internal port is always **8080**. Each environment runs its own 3-broker Kafka cluster (replication factor 2).

Swagger UI: `http://localhost:<host-port>/swagger-ui.html` (root `/` redirects there). In secured environments (§11) you'll need a bearer token pasted into Swagger's Authorize button to actually call anything beyond the whitelisted paths.

---

## 4. REST API Endpoints

### 4.1 Submit a Transaction (standalone/local only)

**`POST /api/v1/standalone/submit`**

Persists the transaction, runs it through the rule engine, and returns the assessment inline. **This does not represent the production flow** — see §2.

**Request body:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `transactionId` | UUID | No | Idempotency key — if already processed, the existing assessment is returned without re-evaluation. Omit to let the server assign one (no idempotency guarantee). |
| `customerId` | string | Yes | Not blank |
| `merchantId` | string | Yes | Not blank — use `MERCHANT_FRAUD_001`/`002`/`003` to trigger the blacklist rule (§13) |
| `amount` | decimal | Yes | Must be positive |
| `currency` | string | Yes | Exactly 3 uppercase letters (ISO 4217) |
| `category` | string | No | Drives category-tiered amount thresholds and high-risk-category detection — see §5.1, §5.8 |
| `transactionType` | string | No | `CARD_PRESENT`, `CARD_NOT_PRESENT`, `CONTACTLESS`, `ATM`; defaults to `CARD_NOT_PRESENT` |
| `location` | string | No | Human-readable, not used by any rule directly |
| `latitude` | decimal | No | -90 to 90. Required (on this transaction or a prior one) for `GEOGRAPHIC_ANOMALY` to activate — see §5.5 for the merchant-location fallback |
| `longitude` | decimal | No | -180 to 180 |
| `deviceFingerprint` | string | No | When present, exercises `DEVICE_FINGERPRINT` (§5.9) — omit it and that rule is a guaranteed no-op on this transaction |

**Responses:**

| Status | Condition |
|---|---|
| `200 OK` | Assessment returned (existing one, if `transactionId` was already processed; otherwise a fresh evaluation) |
| `400 Bad Request` | Blank required field, non-positive amount, currency not a 3-letter uppercase code, out-of-range lat/long |

---

### 4.2 Bulk Generate Transactions (standalone/local only)

**`POST /api/v1/standalone/stream?count={n}`**

Generates `n` randomised transactions through the rule engine to populate the database.

| Parameter | Type | Required | Constraints |
|---|---|---|---|
| `count` | int | Yes | 1–10000 |

**Response 200 OK:**
```json
{ "total": 100, "passed": 75, "flagged": 25 }
```

Approximately **15%** of generated transactions exceed the (flat, non-category-tiered) amount threshold and **~10%** hit a blacklisted merchant — generated transactions don't carry a `category`, so category-tiered thresholds (§5.1) never apply here.

| Status | Condition |
|---|---|
| `200 OK` | Bulk run complete |
| `400 Bad Request` | `count` outside 1–10000 |

---

### 4.3 List Transactions for a Customer

**`GET /api/v1/transactions?customerId={id}&from={iso8601}&to={iso8601}&cursor={iso8601}&pageSize={n}`**

Returns a cursor-paginated list of transactions (with embedded assessments) for a specific customer, ordered by timestamp descending.

| Parameter | Required | Constraints |
|---|---|---|
| `customerId` | Yes | — |
| `from` / `to` | No | ISO-8601 instant; inclusive range on `timestamp` |
| `cursor` | No | ISO-8601 instant (e.g. `2026-07-23T10:00:00Z`); queries `timestamp < cursor` (ties broken by id) |
| `pageSize` | No | 1–1000; default 20 |

**Response 200 OK shape:**
```json
{
  "data": [ { "transactionId": "...", "customerId": "...", "amount": 1250.00, ... , "assessment": { ... } } ],
  "nextCursor": "2026-07-23T09:14:00Z",
  "hasMore": true
}
```

| Status | Condition |
|---|---|
| `200 OK` | Results returned (empty `data` array if none found) |
| `400 Bad Request` | `pageSize` out of range, or `from`/`to`/`cursor` not valid ISO-8601 |

---

### 4.4 Get Assessment for One Transaction

**`GET /api/v1/transactions/{transactionId}/assessment`**

Path variable: `transactionId` (UUID).

**Response 200 OK:**
```json
{
  "assessmentId": "...",
  "transactionId": "...",
  "fraudulent": true,
  "riskScore": 89,
  "assessedAt": "2026-07-23T09:15:01Z",
  "violations": [
    { "ruleName": "BLACKLISTED_MERCHANT", "ruleVersion": "1.0", "description": "...", "severity": "CRITICAL" }
  ]
}
```
`riskScore` is no longer a simple point sum — see §6 before writing assertions against specific values.

| Status | Condition |
|---|---|
| `200 OK` | Assessment found |
| `400 Bad Request` | `transactionId` is not a valid UUID |
| `404 Not Found` | No assessment exists for that transaction ID |

---

### 4.5 List Flagged (Fraudulent) Transactions

**`GET /api/v1/transactions/flagged`**

Returns cursor-paginated assessments where `fraudulent = true`.

| Parameter | Required | Notes |
|---|---|---|
| `customerId` | No | Filter by customer |
| `ruleViolated` | No | Filter by rule name (e.g. `AMOUNT_THRESHOLD`) — must be a real, currently-registered rule name or you get `400` |
| `minRiskScore` | No | Lower bound (inclusive) |
| `maxRiskScore` | No | Upper bound (inclusive) — combine with `minRiskScore` for a band query, e.g. `minRiskScore=50&maxRiskScore=65` for low-confidence flags |
| `from` / `to` | No | Date range on `assessedAt` |
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

**All supplied filters combine with AND — they do not select a single "winning" filter.** Sending `customerId=X&ruleViolated=Y` returns only rows matching *both*, not just the customer filter. (An earlier version of this doc claimed a precedence/mutual-exclusion behaviour here — that was never how the underlying query works; `FraudAssessmentRepository.findFlagged()` ANDs every non-null parameter together. If you have test cases or automation built on the old "only one filter applies" assumption, they're testing the wrong thing.)

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters, or `ruleViolated` doesn't match a registered rule name |

---

### 4.6 List Passed (Cleared) Transactions

**`GET /api/v1/transactions/passed`**

Returns cursor-paginated assessments where `fraudulent = false`.

| Parameter | Required | Notes |
|---|---|---|
| `customerId` | No | Filter by customer |
| `minRiskScore` | No | **Near-miss filter** — returns cleared transactions that still scored at/above this. Useful for finding transactions the engine almost flagged, or (post-rescoring, §6) transactions that hit two weak-alone signals without crossing the threshold. |
| `from` / `to` | No | Date range on `assessedAt` |
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters |

---

### 4.7 List Fraud Rules

**`GET /api/v1/rules`**

Returns all 12 registered fraud rules, ordered by priority, with live config.

**Response 200 OK (excerpt):**
```json
[
  { "ruleName": "AMOUNT_THRESHOLD", "ruleVersion": "1.0", "priority": 1, "enabled": true,
    "config": { "threshold": 5000.00, "categoryThresholds": { "RETAIL": 15000.00, "GROCERY": 3000.00 } } },
  { "ruleName": "VELOCITY", "ruleVersion": "1.0", "priority": 2, "enabled": true,
    "config": { "windowMinutes": 10, "maxTransactions": 5 } }
]
```
`config` reflects whatever is actually active in the running instance — useful for confirming a deployed environment's thresholds match what you expect before writing test expectations against them. Full rule list and their config keys: §5.

Rules cannot be enabled/disabled or have thresholds changed via this or any other endpoint — changes require a redeployment.

---

### 4.8 Health / Observability

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Service health check (unauthenticated in every profile) |
| `GET /actuator/prometheus` | Prometheus metrics scrape (unauthenticated in every profile) |
| `GET /actuator/info` | App info (unauthenticated in every profile) |
| `GET /actuator/metrics` | Spring metrics |

---

### 4.9 Customer Risk Summary

**`GET /api/v1/customers/{customerId}/risk-summary?since={iso8601}`**

Pre-aggregated risk profile: `totalTransactions`, `flaggedCount`, `passedCount`, `fraudRate`, `highestRiskScore`, `mostTriggeredRules`, `firstTransactionAt`, `lastTransactionAt`. `since` (optional) scopes the counts/rates to that point onward; `firstTransactionAt`/`lastTransactionAt` are always all-time regardless.

---

### 4.10 Merchant Flagged Feed

**`GET /api/v1/merchants/{merchantId}/flagged`**

Same filter set as §4.5 (`ruleViolated`, `minRiskScore`, `from`/`to`, `cursor`, `pageSize`), scoped to one merchant, filters combine the same way.

---

### 4.11 Merchant Risk Summary

**`GET /api/v1/merchants/{merchantId}/risk-summary?since={iso8601}`**

Same shape as §4.9 plus `uniqueCustomers`. `uniqueCustomers`, `firstTransactionAt`, `lastTransactionAt` are always all-time.

---

### 4.12 Global Fraud Summary

**`GET /api/v1/stats/fraud-summary?from={iso8601}&to={iso8601}`**

`totalAssessed`, `totalFlagged`, `totalPassed`, `fraudRate`, and a `ruleBreakdown` (per-rule count + percentage of flags it contributed to). Omit `from`/`to` for all-time totals.

---

## 5. Fraud Detection Rules — Full Specification

All 12 rules are evaluated in priority order (1 → 12). All *enabled* rules are checked regardless of earlier violations — the engine does **not** short-circuit after the first violation. Whether a given violation, or combination of violations, actually produces a `fraudulent=true` verdict is governed separately by §6 — treat "does the rule fire" and "is the transaction flagged" as two different questions when writing test cases.

### 5.1 AMOUNT_THRESHOLD (Priority 1)

**Trigger:** Transaction amount **strictly greater than** the applicable threshold.

- **Category-tiered**: if `category` matches a configured override, that threshold applies instead of the flat default. Defaults: `RETAIL`/`ELECTRONICS` 15000.00, `TRAVEL` 20000.00, `GROCERY` 3000.00, `MONEY_TRANSFER`/`WIRE_TRANSFER` 2000.00. No category, or an unrecognised one, falls back to the flat default (5000.00).
- Category matching is case-insensitive (`category.toUpperCase()` against the configured keys).
- Currency-agnostic — the same numeric threshold applies regardless of ZAR/USD/EUR/etc.

| Category | Amount | Triggers? |
|---|---|---|
| (none) | 4999.99 | No |
| (none) | 5000.00 | **No** (boundary — strictly-greater-than, does not trigger) |
| (none) | 5000.01 | Yes |
| `RETAIL` | 6500.00 | **No** — under the RETAIL-specific 15000 threshold |
| `GROCERY` | 3500.00 | **Yes** — over the GROCERY-specific 3000 threshold |

Config: `fraud.rules.amount-threshold.threshold`, `fraud.rules.amount-threshold.category-thresholds`.

---

### 5.2 VELOCITY (Priority 2)

**Trigger:** The same customer has made **5 or more prior** transactions within the last 10 minutes when the current transaction is submitted.

- The current transaction is **excluded** from the lookback count — 5 prior in-window transactions → the 6th (current) triggers.
- **Escalates to CRITICAL severity** (from HIGH) when the current transaction's merchant category matches a high-risk keyword (crypto, money-transfer, wire-transfer — same keyword list as §5.8).
- Window cannot exceed the global context lookback (default 60 minutes) — app fails to start if misconfigured (§12).

Config: `fraud.rules.velocity.max-transactions` (default 5), `fraud.rules.velocity.window-minutes` (default 10).

---

### 5.3 DUPLICATE_TRANSACTION (Priority 3)

**Trigger:** Same customer, same merchant ID, same amount, **and same currency**, submitted more than once within a channel-dependent time window.

| Transaction Type | Window |
|---|---|
| `CARD_PRESENT` | 120 seconds (2 minutes) |
| `CONTACTLESS` | 120 seconds |
| `ATM` | 120 seconds |
| `CARD_NOT_PRESENT` | 300 seconds (5 minutes) — wider, to catch payment-processor retries |

**Currency must match.** `100.00 ZAR` followed by `100.00 USD` at the same merchant is **not** a duplicate — this is deliberate (an international shop charging in two currencies isn't the same charge twice). If you find documentation elsewhere claiming currency is ignored, that's describing behaviour the rule no longer has.

Config: `fraud.rules.duplicate.card-present-window-seconds`, `fraud.rules.duplicate.card-not-present-window-seconds`.

---

### 5.4 BLACKLISTED_MERCHANT (Priority 4)

**Trigger:** The transaction's `merchantId` appears in the blacklisted-merchants table.

**Pre-seeded blacklisted merchants** (§13 has the full list): `MERCHANT_FRAUD_001`, `MERCHANT_FRAUD_002`, `MERCHANT_FRAUD_003`.

- Cached in-memory (Caffeine, 5-minute TTL, max 10,000 entries) via a dedicated `ReferenceDataCache` bean.
- A merchant added directly to the DB is not picked up until the cache entry expires (up to 5 minutes) — and note this TTL is per application instance; in a multi-replica deployment, different pods can disagree on blacklist state for up to 5 minutes independently of each other.

---

### 5.5 GEOGRAPHIC_ANOMALY (Priority 5)

**Trigger:** Implied travel speed between two consecutive geolocated transactions for the same customer exceeds **900 km/h**.

**Coordinates don't have to be supplied on the transaction itself.** For `CARD_PRESENT`/`CONTACTLESS`/`ATM` transactions with no `latitude`/`longitude`, the engine falls back to the merchant's *registered* location (the `merchant_locations` table, §9) if one exists. This fallback is **not** applied to `CARD_NOT_PRESENT` transactions — an online purchase's merchant address isn't a proxy for where the customer physically is. So: a `CARD_PRESENT` transaction with no coordinates can still trigger this rule if the merchant has a registered location and a prior transaction puts the customer somewhere implausibly far away in time.

**Other conditions:**
1. A prior transaction for the same customer within the last 60 minutes must also resolve to coordinates (explicit or merchant-fallback).
2. The two transactions must be **more than 1 minute apart** (pairs within 1 minute are skipped — clock skew / batched submissions).

Algorithm: Haversine formula, Earth radius 6371 km, speed = distance(km) / time-diff(hours).

Config: `fraud.rules.geographic.max-travel-speed-kmh` (default 900), `fraud.rules.geographic.window-minutes` (default 60).

---

### 5.6 CARD_CLONING (Priority 6)

**Trigger:** The same amount charged to **2 or more distinct other merchants** within a short window — automated card-testing signature.

- Window default 10 minutes, minimum 2 *other* distinct merchants (so 3 total distinct merchants including the current transaction, at the default config).
- Config: `fraud.rules.card-cloning.window-minutes`, `fraud.rules.card-cloning.min-different-merchants`.

---

### 5.7 TIME_OF_DAY_ANOMALY (Priority 7)

**Trigger:** Transaction falls inside the configured off-hours window, default **23:00–05:00 UTC** (wraps midnight — `hour >= 23 OR hour < 5`).

- Config: `fraud.rules.time-of-day.off-hours-start-hour`, `fraud.rules.time-of-day.off-hours-end-hour`.
- Doesn't use `EvaluationContext` at all — it's a pure function of the transaction's own timestamp, so it's the cheapest rule to reason about in isolation.

---

### 5.8 HIGH_RISK_MERCHANT_CATEGORY (Priority 8)

**Trigger:** `category` (case-insensitive substring match) contains a configured keyword.

- **High tier** (crypto/money-transfer/wire-transfer keywords): default `CRYPTO`, `CRYPTOCURRENCY`, `CRYPTO_EXCHANGE`, `MONEY_TRANSFER`, `WIRE_TRANSFER`.
- **Medium tier** (gambling/payday-loan keywords): default `GAMBLING`, `CASINO`, `BETTING`, `PAYDAY_LOAN`.
- No `category` on the transaction → rule is a guaranteed no-op.
- Config: `fraud.rules.high-risk-category.high-risk-keywords`, `fraud.rules.high-risk-category.medium-risk-keywords`.

---

### 5.9 DEVICE_FINGERPRINT (Priority 9)

**Trigger:** Transaction carries a `deviceFingerprint` that hasn't been seen for this customer within the lookback window (default 60 minutes).

- **Skipped** (no violation) if the current transaction has no `deviceFingerprint` — backwards-compatible with producers that don't send it.
- **Also skipped** if the customer has *no* prior fingerprinted transactions in the window — a customer's first-ever fingerprinted transaction can't be judged anomalous against nothing. This means: to actually trigger this rule in a test, you need at least one prior transaction with a *different* fingerprint, not just a fresh customer submitting one with a fingerprint for the first time.
- Config: `fraud.rules.device-fingerprint.window-minutes`.

---

### 5.10 MULTI_CHANNEL_ANOMALY (Priority 10)

**Trigger:** A physical-channel transaction (`CARD_PRESENT`, `CONTACTLESS`, `ATM`) and a `CARD_NOT_PRESENT` transaction for the same customer within a short window (default 5 minutes) — a legitimate customer can't tap in-store and transact online simultaneously.

- Config: `fraud.rules.multi-channel.window-minutes`.

---

### 5.11 CROSS_MERCHANT_VELOCITY (Priority 11)

**Trigger:** ≥10 total transactions across *any* merchants within a window (default 10 minutes) — a broader, lower-severity companion to VELOCITY (§5.2).

- **Note for testers:** this rule's default window and threshold overlap heavily with VELOCITY's (>=5 in the same 10-minute window). In practice, any transaction set that trips CROSS_MERCHANT_VELOCITY (needing 10 in the window) will almost always have already tripped VELOCITY (needing only 5). Don't expect to isolate this rule's effect on the fraud verdict from VELOCITY's — see §6.
- Config: `fraud.rules.cross-merchant-velocity.max-transactions`, `fraud.rules.cross-merchant-velocity.window-minutes`.

---

### 5.12 CUMULATIVE_SPENDING (Priority 12)

**Trigger:** Rolling spend exceeds an hourly or daily limit.

- **Hourly**: sum of the customer's transactions within the hourly window (default 60 minutes, from in-context recent-transaction data) plus the current amount, compared against `hourly-limit` (default 10000.00).
- **Daily**: a separate, pre-aggregated 24-hour DB query (independent of the context lookback window), plus the current amount, compared against `daily-limit` (default 25000.00).
- Either limit being exceeded triggers the rule (checked as two independent conditions, not summed together).
- Config: `fraud.rules.cumulative-spending.hourly-limit`, `.daily-limit`, `.hourly-window-minutes`.

---

## 6. Risk Scoring and Fraud Verdict

> Scoring uses a log-odds (naive-Bayes) model, not a flat point sum — see README.md's "Risk scoring" section and `ScoringProperties.java` for the full mechanism and rationale. Summary for test-writing purposes:

- Every fired rule contributes a calibrated **likelihood ratio** (keyed by `RULE_NAME:SEVERITY`, since some rules like `VELOCITY` escalate severity at runtime and are calibrated per variant). These are **not** interchangeable just because two rules share a `Severity` enum value.
- **Standalone-sufficient** (fraudulent alone, given current defaults): `BLACKLISTED_MERCHANT`, `GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, `VELOCITY` (both severity variants), `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, crypto/wire-transfer-tier `HIGH_RISK_MERCHANT_CATEGORY`.
- **Weak alone, needs a second corroborating signal**: `AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`.
- `riskScore` = posterior fraud probability × 100 (0–100). A transaction with **zero** violations scores near the assumed base rate (≈1), not 0.
- `fraudulent = true` when the posterior probability crosses `fraud.scoring.fraud-probability-threshold` (default 0.5, so effectively `riskScore` around 50, but **don't assume you can hand-derive an exact expected riskScore from a rule combination** the way you could under the old additive model — it's a sigmoid of summed log-ratios, not integer addition).
- Two weak-alone rules firing together land in an elevated-but-not-flagged band (roughly riskScore 10–20 for two MEDIUM-tier rules under current defaults) — worth pulling via the near-miss query (§4.6) rather than expecting `fraudulent=true`.

**Approximate examples** (current calibration — treat as a sanity check, not an exact-match assertion target):

| Rules Triggered | Approx. riskScore | Fraudulent? |
|---|---|---|
| None | ~1 | No |
| 1 × AMOUNT_THRESHOLD alone | ~2 | No |
| 1 × CARD_CLONING alone | ~6 | No |
| 1 × CARD_CLONING + 1 × TIME_OF_DAY_ANOMALY | ~15 | No — elevated, not enough alone |
| 1 × VELOCITY (unboosted, HIGH) | ~57 | **Yes** |
| 1 × BLACKLISTED_MERCHANT | ~89 | **Yes** |
| 1 × DEVICE_FINGERPRINT | ~62 | **Yes** |

---

## 7. Transaction Lifecycle

**Production path** (`int`/`qa`/`load`/`prod` — Kafka only):

```
transactions.raw (Kafka) ──► TransactionConsumer ──► EvaluationContextBuilder
                                                              │
                                                              ▼
                                          RuleEngine (12 rules, priority order)
                                                              │
                                                              ▼
                              FraudAssessment persisted (same Kafka+DB transaction)
                                        │                              │
                              fraudulent=true              fraudulent=false
                                        ▼                              ▼
                             transactions.flagged            transactions.passed

  3 failed delivery attempts (exponential backoff) ──► transactions.raw.DLT ──► status: FAILED
```

**Dev/standalone path** (`local`/`standalone` profiles — HTTP only, §2):

```
POST /api/v1/standalone/submit ──► RuleEngine (same 12 rules) ──► assessment returned inline
                                                                    (no Kafka publish in this path)
```

**Transaction statuses:**
- `PENDING` — created, not yet assessed
- `ASSESSED` — rule evaluation complete
- `FAILED` — all Kafka retries exhausted; event in dead-letter topic (production path only)

---

## 8. Kafka Behaviour (non-standalone environments)

| Behaviour | Detail |
|---|---|
| Retry on failure | 3 total attempts, exponential backoff: attempt 1 immediately, attempt 2 after ~1s, attempt 3 after ~2s |
| Dead-letter | `transactions.raw.DLT` — transaction marked `FAILED` |
| Idempotency (transaction row) | If a transaction with that ID already exists in the DB (duplicate delivery), the row isn't re-saved. **Worth verifying separately:** the consumer re-runs `ruleEngine.evaluate()` and persists a new `FraudAssessment` row on every delivery of a given transaction, including redeliveries — the idempotency guard covers the `transactions` row, not assessment creation. If you're testing redelivery scenarios specifically, check whether a second assessment row actually appears rather than assuming full end-to-end idempotency. |
| Transactional | DB commit and Kafka commit are wrapped in `ChainedKafkaTransactionManager`; a DB failure aborts the Kafka commit |

---

## 9. Data Model Summary

### transactions table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key (composite with `timestamp` — see below) |
| `timestamp` | TIMESTAMPTZ | Also part of the primary key; partition key (daily partitions) |
| `customer_id` | VARCHAR(64) | Not logged (PII policy) |
| `merchant_id` | VARCHAR(64) | Not logged (PII policy) |
| `amount` | NUMERIC(19,4) | |
| `currency` | VARCHAR(3) | |
| `category` | VARCHAR(64) | Nullable |
| `location` | VARCHAR(128) | Nullable |
| `latitude` / `longitude` | DOUBLE PRECISION | Nullable |
| `device_fingerprint` | VARCHAR(128) | Nullable |
| `transaction_type` | VARCHAR(32) | Enum, defaults `CARD_NOT_PRESENT` |
| `status` | VARCHAR(32) | PENDING / ASSESSED / FAILED |
| `created_at` | TIMESTAMPTZ | |

### fraud_assessments table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `transaction_id`, `transaction_timestamp` | UUID, TIMESTAMPTZ | Composite FK → `transactions(id, timestamp)` — required because the referenced table is partitioned |
| `is_fraudulent` | BOOLEAN | |
| `risk_score` | INTEGER | DB-enforced CHECK 0–100 |
| `assessed_at` | TIMESTAMPTZ | |

### rule_violations table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `assessment_id` | UUID | FK → fraud_assessments |
| `rule_name` | VARCHAR(64) | |
| `rule_version` | VARCHAR(16) | |
| `description` | TEXT | Human-readable explanation |
| `severity` | VARCHAR(16) | LOW / MEDIUM / HIGH / CRITICAL |

### blacklisted_merchants table

| Column | Type | Notes |
|---|---|---|
| `merchant_id` | VARCHAR(64) | Primary key |
| `reason` | TEXT | |
| `added_at` | TIMESTAMPTZ | |

### merchant_locations table

| Column | Type | Notes |
|---|---|---|
| `merchant_id` | VARCHAR(64) | Primary key |
| `latitude` / `longitude` | DOUBLE PRECISION | Registered address — feeds the GEOGRAPHIC_ANOMALY fallback (§5.5) |

---

## 10. Error Response Format (RFC 7807)

All error responses use `Content-Type: application/problem+json`.

| HTTP Status | Trigger | Response shape |
|---|---|---|
| `400` | `@Valid` field constraint failure | `{ "detail": "Validation failed", "errors": { "fieldName": "message" } }` |
| `400` | Query param constraint failure | `{ "detail": "Invalid request parameters", "errors": { "paramName": "message" } }` |
| `400` | `IllegalArgumentException` (e.g. unknown `ruleViolated` value, `to` before `from`) | `{ "detail": "<exception message>" }` |
| `400` | Malformed ISO-8601 date/cursor | `{ "detail": "Invalid date-time value: '<value>'. Expected ISO-8601 format, e.g. 2026-07-23T10:00:00Z" }` |
| `400` | Malformed/missing request body | `{ "detail": "Request body is missing or malformed. Ensure the body is valid JSON and all required fields are present." }` |
| `404` | No assessment for transaction ID | `{ "detail": "<message>" }` |
| `429` | Rate limit exceeded (standalone `/submit` only) | `{ "detail": "Too many requests — please retry after a moment." }`, `Retry-After: 10` header |
| `500` | Unhandled exception | `{ "detail": "An unexpected error occurred" }` |

---

## 11. Authentication and Authorization

**This is profile-gated, not uniform across environments — check which one you're testing against before assuming either way.**

| Environment(s) | Profile | Auth required? |
|---|---|---|
| `dev` (`make dev`) | `local` | **No** — all requests permitted |
| standalone (manual run) | `standalone` | **No** |
| `int`, `qa`, `load`, `prod` | `int` / `qa` / `load` / `prod` | **Yes** — OAuth2 JWT bearer token |

Where auth is required: `Authorization: Bearer <jwt>`, issued by the Acme IDP configured via `FRAUD_IDP_URI`. The token's `roles` claim must contain `FRAUD_ANALYST` or `FRAUD_ENGINEER` (mapped to `ROLE_*` Spring Security authorities). `/actuator/health`, `/actuator/info`, `/actuator/prometheus`, and the Swagger/OpenAPI paths are open in every profile. If the configured IDP is unreachable at startup, the app **fails to start** — this is intentional, not a bug, if you see it in a secured environment.

If you're only ever testing against `make dev`, you won't hit any of this — which is exactly why it's worth confirming which environment a given test run actually targets before writing "no auth needed" into a test plan.

---

## 12. Key Testing Facts and Gotchas

1. **`make dev` runs the `local` Spring profile, not a profile named "dev".** That's what makes the standalone HTTP endpoints reachable there and nowhere else in the make targets (§2, §11). `standalone` profile (H2, no Kafka) is run manually, not via a `make` target; use `/h2-console` if enabled.

2. **Amount boundary is exact and category-dependent.** 5000.00 flat does **not** trigger `AMOUNT_THRESHOLD`; 5000.01 does. But check `category` first — a `RETAIL` transaction uses a 15000 threshold, not 5000 (§5.1).

3. **Velocity count:** the rule triggers on **5 prior** transactions in the window — the transaction being submitted is the 6th total. Sending 5 quickly and then a 6th should trigger it.

4. **Duplicate rule requires matching currency.** `amount=100.00, currency=ZAR` and `amount=100.00, currency=USD` at the same merchant are **not** duplicates — currency must match too (§5.3). (If you've seen a claim to the contrary in older docs, that's stale — this was fixed in the rule itself a while back.)

5. **Duplicate window is 120 seconds for physical channels, not 30.** `CARD_PRESENT`/`CONTACTLESS`/`ATM` = 120s; `CARD_NOT_PRESENT` = 300s (§5.3).

6. **Geographic rule doesn't strictly require explicit coordinates.** A `CARD_PRESENT`/`CONTACTLESS`/`ATM` transaction with no `latitude`/`longitude` can still trigger `GEOGRAPHIC_ANOMALY` via the merchant-location fallback, if that merchant has a row in `merchant_locations` (§5.5). This fallback never applies to `CARD_NOT_PRESENT`.

7. **Geographic rule skips near-simultaneous transactions:** pairs less than 1 minute apart are not speed-checked.

8. **Filters on `/flagged` (and the merchant equivalent) combine with AND — they do not select one "winning" filter.** `customerId=X&ruleViolated=Y` returns rows matching both (§4.5). Don't write test cases assuming otherwise.

9. **Pagination cursor is strictly less-than, with an id tie-break:** a cursor of `T` returns records with `timestamp < T`, or `timestamp = T AND id < cursorId` for same-timestamp rows. The cursor row itself is not repeated.

10. **Blacklist cache TTL:** adding a merchant to the blacklist DB directly can take up to 5 minutes to be picked up (per-instance Caffeine cache, §5.4). In a multi-replica deployment, different pods can disagree during that window.

11. **Risk score cap:** score cannot exceed 100 (explicitly clamped) or go below 0.

12. **The `fraudulent` verdict threshold is a probability, not a point total (§6).** Don't try to hand-predict an exact `riskScore` from "which rules fired" the way you could under the old additive model — verify against a running instance or the approximate table in §6.

13. **Context lookback (default 60 minutes) bounds every window-based rule**, not just velocity/geographic — also duplicate, card-cloning, device-fingerprint, multi-channel, cross-merchant-velocity, and cumulative-spending's hourly window. The app validates all of these against the lookback at startup and refuses to start if any window is configured wider than it.

14. **Partition maintenance job:** runs at 02:00 daily. Creates the partition 2 days ahead; drops the partition from 91 days ago (90-day retention plus a 1-day buffer). Test data older than ~91 days will be purged.

15. **No runtime rule config change:** rules cannot be enabled/disabled or have thresholds changed via API — requires a redeployment.

16. **Device fingerprint rule needs prior history to mean anything.** A customer's very first fingerprinted transaction never triggers `DEVICE_FINGERPRINT` — there's nothing to compare it against yet (§5.9). Set up a prior transaction with a *different* fingerprint first if you want to test the trigger path.

17. **`CROSS_MERCHANT_VELOCITY` rarely fires in isolation from `VELOCITY`.** Their default windows overlap and `CROSS_MERCHANT_VELOCITY`'s threshold (≥10) is stricter than `VELOCITY`'s (≥5) in the same window — see §5.11.

---

## 13. Pre-seeded Test Data

The following merchants are always blacklisted out of the box:

| Merchant ID | Reason |
|---|---|
| `MERCHANT_FRAUD_001` | Phishing |
| `MERCHANT_FRAUD_002` | Card skimming |
| `MERCHANT_FRAUD_003` | Synthetic identity fraud |

Use these to reliably trigger `BLACKLISTED_MERCHANT` without any DB setup.

---

## 14. Observability (for test verification)

- **Logs:** every log line carries `requestId` (per HTTP request) and `transactionId` (Kafka path also adds `kafkaTopic`/`kafkaPartition`/`kafkaOffset`). Customer and merchant IDs are **not** logged.
- **Metrics** (Prometheus at `GET /actuator/prometheus`):
  - `fraud.assessments.total{verdict="FRAUDULENT"|"PASSED"}` — counters
  - `fraud.dlt.total` — increments each time a transaction exhausts retries and hits the dead-letter topic
  - `fraud.rule.evaluation.duration.seconds` — timer, p50/p95/p99 published
  - Kafka consumer lag is exposed automatically via Micrometer/Spring Kafka auto-instrumentation
- **Tracing:** OpenTelemetry (OTLP gRPC), 10% sampling. In `dev`, no collector is configured — traces are dropped silently, which is expected, not a bug.
