# TesterInfo — Fraud Rule Engine

**Audience:** QA/Tester preparing a test plan, test cases, and test scenarios.

---

## 1. What This Service Is

This is an **asynchronous, post-authorisation** transaction fraud detection engine for Acme Bank — it evaluates a transaction *after* it has already happened, not as a blocking gate before authorisation. It evaluates financial transactions against **13** rule-based fraud detectors, persists the results, and routes outcomes downstream. The service is built on Spring Boot 3.3 / Java 21.

In production-like environments (`int`, `qa`, `load`, `prod`), transactions enter **exclusively** via Kafka — there is no HTTP endpoint to submit a transaction. §2 below describes a demo-only HTTP submission stub that exists solely in `local`/`standalone` profiles, not in production. The query API is otherwise read-only except for one real, always-present write endpoint — §4.14 — which lets an analyst record a fraud assessment's ground-truth outcome; it does not accept new transactions.

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
  "disposition": "FLAGGED",
  "riskScore": 89,
  "assessedAt": "2026-07-23T09:15:01Z",
  "violations": [
    { "ruleName": "BLACKLISTED_MERCHANT", "ruleVersion": "1.0", "description": "...", "severity": "CRITICAL" }
  ],
  "outcome": "UNRESOLVED"
}
```
`disposition` (`CLEARED`/`PENDING_REVIEW`/`FLAGGED`) is the system's real-time verdict at assessment time; `outcome` (`UNRESOLVED`/`CONFIRMED_FRAUD`/`FALSE_POSITIVE`) is the analyst's ground truth recorded afterward via §4.14 — the two are independent fields, not synonyms.
`riskScore` is no longer a simple point sum — see §6 before writing assertions against specific values.

| Status | Condition |
|---|---|
| `200 OK` | Assessment found |
| `400 Bad Request` | `transactionId` is not a valid UUID |
| `404 Not Found` | No assessment exists for that transaction ID |

---

### 4.5 List Flagged (Fraudulent) Transactions

**`GET /api/v1/transactions/flagged`**

Returns cursor-paginated assessments where `disposition = FLAGGED`.

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

### 4.6 List Transactions Pending Review

**`GET /api/v1/transactions/pending-review`**

Returns cursor-paginated assessments where `disposition = PENDING_REVIEW` — the
elevated-but-not-confident band (tier 4 of the scoring plan, §6). Two or more
corroborating weak signals (e.g. off-hours + card cloning) land here instead of
being silently treated the same as a clean, zero-violation transaction. Same
parameter set and AND-combining behaviour as §4.5.

| Parameter | Required | Notes |
|---|---|---|
| `customerId` | No | Filter by customer |
| `ruleViolated` | No | Filter by rule name — must be a real, currently-registered rule name or `400` |
| `minRiskScore` | No | Lower bound (inclusive) |
| `maxRiskScore` | No | Upper bound (inclusive) |
| `from` / `to` | No | Date range on `assessedAt` |
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters, or `ruleViolated` doesn't match a registered rule name |

---

### 4.7 List Passed (Cleared) Transactions

**`GET /api/v1/transactions/passed`**

Returns cursor-paginated assessments where `disposition = CLEARED`.

| Parameter | Required | Notes |
|---|---|---|
| `customerId` | No | Filter by customer |
| `minRiskScore` | No | Lower bound (inclusive). Cleared transactions score low by construction (below `review-probability-threshold`, default 0.10) — this filter sorts within that band rather than surfacing near-misses, which now belong to `disposition = PENDING_REVIEW` (§4.6) instead. |
| `from` / `to` | No | Date range on `assessedAt` |
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters |

---

### 4.8 List Fraud Rules

**`GET /api/v1/rules`**

Returns all 13 registered fraud rules, ordered by priority, with live config.

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

### 4.9 Health / Observability

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Service health check (unauthenticated in every profile) |
| `GET /actuator/prometheus` | Prometheus metrics scrape (unauthenticated in every profile) |
| `GET /actuator/info` | App info (unauthenticated in every profile) |
| `GET /actuator/metrics` | Spring metrics |

---

### 4.10 Customer Risk Summary

**`GET /api/v1/customers/{customerId}/risk-summary?since={iso8601}`**

Pre-aggregated risk profile: `totalTransactions`, `flaggedCount`, `notFlaggedCount`, `fraudRate`, `highestRiskScore`, `mostTriggeredRules`, `firstTransactionAt`, `lastTransactionAt`. `since` (optional) scopes the counts/rates to that point onward; `firstTransactionAt`/`lastTransactionAt` are always all-time regardless. As with §4.13, `notFlaggedCount` is `totalTransactions - flaggedCount` and includes `PENDING_REVIEW` transactions, not just `CLEARED` ones — use §4.6 directly for an exact pending-review count.

---

### 4.11 Merchant Flagged Feed

**`GET /api/v1/merchants/{merchantId}/flagged`**

Same filter set as §4.5 (`ruleViolated`, `minRiskScore`, `from`/`to`, `cursor`, `pageSize`), scoped to one merchant, filters combine the same way.

---

### 4.12 Merchant Risk Summary

**`GET /api/v1/merchants/{merchantId}/risk-summary?since={iso8601}`**

Same shape as §4.10 plus `uniqueCustomers`. `uniqueCustomers`, `firstTransactionAt`, `lastTransactionAt` are always all-time.

---

### 4.13 Global Fraud Summary

**`GET /api/v1/stats/fraud-summary?from={iso8601}&to={iso8601}`**

`totalAssessed`, `totalFlagged`, `totalNotFlagged`, `fraudRate`, and a `ruleBreakdown` (per-rule count + percentage of flags it contributed to). Omit `from`/`to` for all-time totals. `totalNotFlagged` is `totalAssessed - totalFlagged` and includes `PENDING_REVIEW` assessments, not just `CLEARED` ones. There's no separate `totalPendingReview` field yet; use §4.6 directly to get an exact pending-review count.

---

### 4.14 Record a Transaction's Outcome (the one write endpoint)

**`PATCH /api/v1/transactions/{transactionId}/outcome`**

This is the **second exception** to "the query API is read-only" (the first being §4.1/§4.2, which are profile-gated demo stubs not present in production). This endpoint is a real, permanent, non-profile-gated part of the production API — it just doesn't accept a *new transaction*, only a disposition on an assessment that already exists.

Lets a fraud analyst record whether a flagged (or cleared) transaction turned out to actually be fraud or a false positive, once reviewed. Keyed by `transactionId` (not the assessment's own internal `id`) — the same UUID that appears in Kafka's `transaction_id`, the `txn=` MDC/log correlation key, and every DTO that already returns `transactionId`, so an analyst can trace a case end-to-end with one identifier.

**Request body:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `outcome` | string | Yes | `CONFIRMED_FRAUD` or `FALSE_POSITIVE`. `UNRESOLVED` cannot be set manually — it's only the default for a never-reviewed assessment. |

```json
{ "outcome": "CONFIRMED_FRAUD" }
```

**Responses:**

| Status | Condition |
|---|---|
| `200 OK` | Outcome recorded; returns the updated assessment (including the new `outcome` field, added to `FraudAssessmentDto`) |
| `400 Bad Request` | Missing/invalid `outcome`, or an attempt to set `UNRESOLVED` |
| `404 Not Found` | No assessment exists for this transaction ID |
| `409 Conflict` | **One-time disposition**: the assessment already has a resolved outcome (`CONFIRMED_FRAUD` or `FALSE_POSITIVE`). A second update attempt is always rejected — this endpoint has no "correction" path. |

Authorization is identical to every other `/api/v1/**` endpoint (§11) — same JWT, same `FRAUD_ANALYST`/`FRAUD_ENGINEER` roles, no separate write-scoped role exists.

This field exists to eventually calibrate the log-odds scoring model's likelihood ratios (§6) against real confirmed-fraud/false-positive data — recording outcomes is implemented; automatically feeding them back into `ScoringProperties` is not (still open, tracked in `DESIGN.md` §11).

---

## 5. Fraud Detection Rules — Full Specification

All 13 rules are evaluated in priority order (1 → 13). All *enabled* rules are checked regardless of earlier violations — the engine does **not** short-circuit after the first violation. Whether a given violation, or combination of violations, actually produces a `FLAGGED` (or `PENDING_REVIEW`) disposition is governed separately by §6 — treat "does the rule fire" and "what disposition results" as two different questions when writing test cases.

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
- **Daily**: a separate, pre-aggregated 24-hour DB query (independent of the context lookback window, and excluding the transaction currently being evaluated — fixed 2026-09-03, see below), plus the current amount, compared against `daily-limit` (default 25000.00).
- Either limit being exceeded triggers the rule (checked as two independent conditions, not summed together).
- Config: `fraud.rules.cumulative-spending.hourly-limit`, `.daily-limit`, `.hourly-window-minutes`.

---

### 5.13 CUSTOMER_AMOUNT_ANOMALY (Priority 13)

**Trigger:** Transaction amount is more than `stddev-multiplier` (default 3.0) standard deviations above *this specific customer's* own historical mean amount — a personal-baseline complement to the global `AMOUNT_THRESHOLD` (§5.1).

- Uses a separate, longer-window transaction history (`fraud.rules.customer-amount-anomaly.lookback-days`, default 90 days) — independent of `context-lookback-minutes` and the primary `recentCustomerTransactions` window other rules share.
- **Cold-start guard**: fewer than `min-history-count` (default 5) prior transactions in that window → the rule passes unconditionally. Not enough history to say what's "normal" for this customer yet.
- **Degenerate-variance guard**: if every prior amount is identical (stdDev = 0 — e.g. a subscription-only customer), the rule passes rather than dividing by zero. Known limitation, not a bug.
- Mean/stdDev are computed as population statistics (divide by `n`, not `n-1`) over that window, excluding the transaction currently being evaluated.
- A R4,999 purchase from a customer whose typical transaction is ~R80 is exactly the kind of case this rule catches that `AMOUNT_THRESHOLD` (fixed at 5000, or up to 20000 for some categories) is blind to — and conversely, a customer with genuinely variable habits (e.g. spend ranging 2000–3000) making a 3500 purchase is *not* flagged, since that's within their own normal variation.
- Config: `fraud.rules.customer-amount-anomaly.enabled`, `.lookback-days`, `.min-history-count`, `.stddev-multiplier`.

---

## 6. Risk Scoring and Fraud Verdict

> Scoring uses a log-odds (naive-Bayes) model, not a flat point sum — see README.md's "Risk scoring" section and `ScoringProperties.java` for the full mechanism and rationale. Summary for test-writing purposes:

- Every fired rule contributes a calibrated **likelihood ratio** (keyed by `RULE_NAME:SEVERITY`, since some rules like `VELOCITY` escalate severity at runtime and are calibrated per variant). These are **not** interchangeable just because two rules share a `Severity` enum value.
- **Standalone-sufficient** (`FLAGGED` alone, given current defaults): `BLACKLISTED_MERCHANT`, `GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, `VELOCITY` (both severity variants), `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, crypto/wire-transfer-tier `HIGH_RISK_MERCHANT_CATEGORY`.
- **Weak alone, needs a second corroborating signal**: `AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, `CUSTOMER_AMOUNT_ANOMALY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`.
- `riskScore` = posterior fraud probability × 100 (0–100). A transaction with **zero** violations scores near the assumed base rate (≈1), not 0.
- **`disposition` is a three-way verdict, not a boolean**, driven by two thresholds:
  - `disposition = FLAGGED` when the posterior probability ≥ `fraud.scoring.fraud-probability-threshold` (default 0.5, so effectively `riskScore` around 50).
  - `disposition = PENDING_REVIEW` when the probability is ≥ `fraud.scoring.review-probability-threshold` (default 0.10, ~`riskScore` 10) but below the fraud threshold.
  - `disposition = CLEARED` below both.
  - **Don't assume you can hand-derive an exact expected riskScore from a rule combination** the way you could under the old additive model — it's a sigmoid of summed log-ratios, not integer addition.
- Two weak-alone rules firing together land in the `PENDING_REVIEW` band (roughly riskScore 10–20 for two MEDIUM-tier rules under current defaults) — pull them via §4.6 rather than expecting `disposition = FLAGGED`. A single weak-alone rule by itself stays `CLEARED` (roughly riskScore 2–6, below the review threshold).

**Approximate examples** (current calibration — treat as a sanity check, not an exact-match assertion target):

| Rules Triggered | Approx. riskScore | Disposition |
|---|---|---|
| None | ~1 | `CLEARED` |
| 1 × AMOUNT_THRESHOLD alone | ~2 | `CLEARED` |
| 1 × CARD_CLONING alone | ~6 | `CLEARED` |
| 1 × CARD_CLONING + 1 × TIME_OF_DAY_ANOMALY | ~15 | `PENDING_REVIEW` |
| 1 × VELOCITY (unboosted, HIGH) | ~57 | `FLAGGED` |
| 1 × BLACKLISTED_MERCHANT | ~89 | `FLAGGED` |
| 1 × DEVICE_FINGERPRINT | ~62 | `FLAGGED` |

---

## 7. Transaction Lifecycle

**Production path** (`int`/`qa`/`load`/`prod` — Kafka only):

```
transactions.raw (Kafka) ──► TransactionConsumer ──► EvaluationContextBuilder
                                                              │
                                                              ▼
                                          RuleEngine (13 rules, priority order)
                                                              │
                                                              ▼
                              FraudAssessment persisted (same Kafka+DB transaction)
                                 │                    │                    │
                        disposition=FLAGGED  disposition=PENDING_REVIEW  disposition=CLEARED
                                 ▼                    ▼                    ▼
                    transactions.flagged  transactions.pending-review  transactions.passed

  3 failed delivery attempts (exponential backoff) ──► transactions.raw.DLT ──► status: FAILED
```

**Dev/standalone path** (`local`/`standalone` profiles — HTTP only, §2):

```
POST /api/v1/standalone/submit ──► RuleEngine (same 13 rules) ──► assessment returned inline
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
| Idempotency (transaction row) | If a transaction with that ID already exists in the DB (duplicate delivery), the row isn't re-saved. |
| Idempotency (assessment) | Fixed 2026-09-03. The consumer checks `fraudAssessmentRepository.findByTransactionId` before evaluation; if an assessment already exists for the transaction, evaluation, the assessment save, and the outcome-event publish are all skipped (a `fraud.kafka.duplicate_delivery.total` metric is incremented instead). `fraud_assessments.transaction_id` also carries a `UNIQUE` constraint (`V9` migration) as a database-level backstop. Previously (through 2026-09-03) this was a real gap — every redelivery, including ones after a fully-committed transaction, inserted a second `FraudAssessment` row and re-published a duplicate outcome event. |
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
| `disposition` | VARCHAR(32) | `CLEARED` / `PENDING_REVIEW` / `FLAGGED` — the system's real-time verdict at assessment time (§6). Replaces an earlier `is_fraudulent` boolean. |
| `risk_score` | INTEGER | DB-enforced CHECK 0–100 |
| `assessed_at` | TIMESTAMPTZ | |
| `outcome` | VARCHAR(32) | `UNRESOLVED` (default) / `CONFIRMED_FRAUD` / `FALSE_POSITIVE`. Ground truth recorded by an analyst via §4.14 — one-time write, not reversible through the API. |

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
| `409` | Outcome already resolved (§4.14) | `{ "detail": "<message>" }` |
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

12. **The `disposition` verdict is driven by two probability thresholds, not a point total (§6).** Don't try to hand-predict an exact `riskScore` from "which rules fired" the way you could under the old additive model — verify against a running instance or the approximate table in §6.

13. **`disposition` is three-way, not binary** — `CLEARED` / `PENDING_REVIEW` / `FLAGGED`. Existing test suites or scripts written against a boolean `fraudulent` field need updating; `/transactions/passed` and `/transactions/flagged` are strictly `CLEARED`-only and `FLAGGED`-only respectively (not "everything except the other"), and `/transactions/pending-review` (§4.6) is the third, previously-nonexistent bucket.

14. **Context lookback (default 60 minutes) bounds every window-based rule**, not just velocity/geographic — also duplicate, card-cloning, device-fingerprint, multi-channel, cross-merchant-velocity, and cumulative-spending's hourly window. The app validates all of these against the lookback at startup and refuses to start if any window is configured wider than it.

15. **Partition maintenance job:** runs at 02:00 daily. Creates the partition 2 days ahead; drops the partition from 91 days ago (90-day retention plus a 1-day buffer). Test data older than ~91 days will be purged.

16. **No runtime rule config change:** rules cannot be enabled/disabled or have thresholds changed via API — requires a redeployment.

17. **Device fingerprint rule needs prior history to mean anything.** A customer's very first fingerprinted transaction never triggers `DEVICE_FINGERPRINT` — there's nothing to compare it against yet (§5.9). Set up a prior transaction with a *different* fingerprint first if you want to test the trigger path.

18. **`CROSS_MERCHANT_VELOCITY` rarely fires in isolation from `VELOCITY`.** Their default windows overlap and `CROSS_MERCHANT_VELOCITY`'s threshold (≥10) is stricter than `VELOCITY`'s (≥5) in the same window — see §5.11.

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
  - `fraud.assessments.total{verdict="FLAGGED"|"PENDING_REVIEW"|"CLEARED"}` — counters, matching the `disposition` enum exactly (renamed from the old `FRAUDULENT`/`PASSED` tag values)
  - `fraud.dlt.total` — increments each time a transaction exhausts retries and hits the dead-letter topic
  - `fraud.rule.evaluation.duration.seconds` — timer, p50/p95/p99 published
  - Kafka consumer lag is exposed automatically via Micrometer/Spring Kafka auto-instrumentation
- **Tracing:** OpenTelemetry (OTLP gRPC), 10% sampling. In `dev`, no collector is configured — traces are dropped silently, which is expected, not a bug.
