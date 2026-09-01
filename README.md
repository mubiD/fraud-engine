# Fraud Rule Engine

A production-grade backend service that consumes transaction events from Kafka, evaluates them against a configurable set of fraud detection rules, persists assessments to PostgreSQL, and routes outcomes to dedicated downstream topics.

**Stack:** Java 21 · Spring Boot 3.3 · Apache Kafka 3 (KRaft, 3-broker) · Protobuf · Confluent Schema Registry · PostgreSQL 16 (range-partitioned) · HashiCorp Vault · Zipkin · Prometheus · Docker · JUnit 5 · Mockito · Testcontainers · k6

---

## Architecture

See [DESIGN.md](./DESIGN.md) for the full system design document covering all architectural decisions, trade-offs, and extensibility considerations.

```
External System
      │
      ▼  TransactionEvent (Protobuf)
transactions.raw  ──────────────────────────────────────────────────────┐
      │                                                                  │
      ▼                                                             retry topics
  TransactionConsumer                                            (transactions.raw-0,
      │   @RetryableTopic (3 attempts, exponential backoff)       transactions.raw-1)
      │   @Transactional(chainedKafkaTransactionManager)               │
      │   idempotency guard: findByIdOnly → skip-save if exists        │
      │                                                                 ▼
      ▼                                                      transactions.raw.DLT
  EvaluationContextBuilder                             (@DltHandler → status=FAILED)
      │   queries recent transactions, blacklist, merchant locations,
      │   daily spend total — all before rule evaluation
      ▼
  Rule Engine (Strategy Pattern)
  ├── AmountThresholdRule           priority 1   HIGH      (category-tiered thresholds)
  ├── VelocityRule                  priority 2   HIGH      (high-risk category boost → CRITICAL)
  ├── DuplicateTransactionRule      priority 3   CRITICAL  (type-aware window, currency-aware)
  ├── BlacklistedMerchantRule       priority 4   CRITICAL  (Caffeine-cached)
  ├── GeographicAnomalyRule         priority 5   CRITICAL  (speed + clock-skew guard)
  ├── CardCloningRule               priority 6   MEDIUM    (same amount, multiple merchants)
  ├── TimeOfDayAnomalyRule          priority 7   MEDIUM    (23:00–05:00 UTC off-hours)
  ├── HighRiskMerchantCategoryRule  priority 8   HIGH/MED  (crypto/money-transfer/gambling)
  ├── DeviceFingerprintRule         priority 9   HIGH      (unknown device for customer)
  ├── MultiChannelAnomalyRule       priority 10  MEDIUM    (rapid physical↔online switch)
  ├── CrossMerchantVelocityRule     priority 11  MEDIUM    (≥10 txns across merchants in 10 min)
  ├── CumulativeSpendingRule        priority 12  HIGH      (hourly + daily spend limits)
  └── CustomerAmountAnomalyRule     priority 13  MEDIUM    (deviation from customer's own spending baseline)
      │
      ▼
  FraudAssessment → PostgreSQL (Flyway-managed, daily-partitioned)
      │
      ├── disposition=FLAGGED         → FraudulentTransactionEvent (Protobuf)
      │                                        ▼
      │                                 transactions.flagged
      │
      ├── disposition=PENDING_REVIEW → PendingReviewTransactionEvent (Protobuf)
      │                                        ▼
      │                                 transactions.pending-review
      │
      └── disposition=CLEARED        → ClearedTransactionEvent (Protobuf)
                                               ▼
                                        transactions.passed

Query API (read-only, with one write exception — see below)
  Transactions
    GET   /api/v1/transactions                          — customer transaction history
    GET   /api/v1/transactions/{id}                      — single transaction by ID
    GET   /api/v1/transactions/{id}/assessment            — fraud assessment for a transaction
    PATCH /api/v1/transactions/{id}/outcome               — record an analyst's ground-truth outcome (the one write endpoint)
    GET   /api/v1/transactions/flagged                    — fraud queue (filterable by customer, rule, score band, date)
    GET   /api/v1/transactions/pending-review             — elevated-but-not-confident queue (same filters as flagged)
    GET   /api/v1/transactions/passed                     — cleared transactions (filterable by customer, min score, date)
  Rules
    GET /api/v1/rules                                   — registered rules with live config
  Customers
    GET /api/v1/customers/{id}/risk-summary             — pre-aggregated customer risk profile
  Merchants
    GET /api/v1/merchants/{id}/flagged                  — merchant fraud feed (filterable by rule, score, date)
    GET /api/v1/merchants/{id}/risk-summary             — pre-aggregated merchant risk profile
  Stats
    GET /api/v1/stats/fraud-summary                     — global fraud aggregates with rule breakdown
```

---

## Running Locally

The only prerequisite is **Docker**. No Java, Maven, or Kafka installation required — everything runs inside containers.

Each environment is fully self-contained: its own app instance, Postgres database, Kafka cluster, and observability stack, all on separate host ports so multiple environments can run simultaneously.

| Service | dev | int | qa | load | prod |
|---|---|---|---|---|---|
| App | 8081 | 8082 | 8083 | 8084 | 8085 |
| Postgres | 5433 | 5434 | 5435 | 5436 | 5437 |
| Kafka broker 1 | 9192 | 9292 | 9392 | 9492 | 9592 |
| Kafka broker 2 | 9193 | 9293 | 9393 | 9493 | 9593 |
| Kafka broker 3 | 9194 | 9294 | 9394 | 9494 | 9594 |
| Schema Registry | 8091 | — | — | — | — |
| Vault | 8200 | — | — | — | — |
| Instana agent | — | — | — | — | — |
| Prometheus | 9090 | — | — | — | — |

> Schema Registry, Vault, Zipkin, and Prometheus host-port mappings are only exposed in the `dev` environment. In other environments they are accessible within the Docker network.

### Start an environment

```bash
make dev
make int
make qa
make load
make prod
```

Each command:
1. Builds the app image from source
2. Starts Postgres and waits until healthy
3. Starts the 3-broker Kafka cluster and waits until healthy
4. Starts Schema Registry and waits until healthy
5. Starts Vault (dev mode, pre-unsealed)
6. Starts the fraud-engine (Flyway runs migrations on boot)
7. Polls `/actuator/health` until the app is ready

Postgres data volumes are named per environment and persist across restarts.

### Tear down

```bash
make stop ENV=dev
```

### Tail logs

```bash
make logs ENV=dev
```

### See all running environments

```bash
make ps
```

---

## API Reference

> There is no HTTP submission endpoint. Transactions enter the system exclusively via `transactions.raw` Kafka topic. The API is read-only, with one deliberate exception: `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record a fraud assessment's real-world ground truth (see below).

All paginated endpoints return a consistent envelope:

```json
{ "data": [...], "hasMore": true, "nextCursor": "2026-07-23T09:00:00Z" }
```

Pass `nextCursor` as the `cursor` parameter on the next request to advance the page. All timestamps are ISO-8601 UTC.

> Replace `8081` with the port for the environment you started (`8082` = int, `8083` = qa, etc.).

---

### Transactions

#### List transactions for a customer

```
GET /api/v1/transactions
```

| Param | Type | Required | Description |
|---|---|---|---|
| `customerId` | string | yes | Customer identifier |
| `from` | ISO-8601 | no | Include transactions at or after this timestamp |
| `to` | ISO-8601 | no | Include transactions at or before this timestamp |
| `cursor` | ISO-8601 | no | Pagination cursor from previous response |
| `pageSize` | int 1–1000 | no | Default 20 |

```bash
curl "http://localhost:8081/api/v1/transactions?customerId=CUST-001&from=2026-07-01T00:00:00Z&pageSize=50"
```

Response `200 OK`:
```json
{
  "data": [
    {
      "transactionId": "550e8400-e29b-41d4-a716-446655440000",
      "customerId": "CUST-001",
      "merchantId": "MERCH-FRAUD-003",
      "amount": "350.00",
      "currency": "ZAR",
      "transactionType": "CARD_PRESENT",
      "status": "ASSESSED",
      "timestamp": "2026-07-23T09:00:00Z",
      "assessment": {
        "disposition": "FLAGGED",
        "riskScore": 89,
        "violations": [{ "ruleName": "BLACKLISTED_MERCHANT", "severity": "CRITICAL" }]
      }
    }
  ],
  "hasMore": false,
  "nextCursor": null
}
```

#### Get a single transaction

```
GET /api/v1/transactions/{transactionId}
```

Returns `200 OK` with `TransactionSummaryDto` (including the embedded assessment if one exists), or `404` if not found.

```bash
curl http://localhost:8081/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000
```

#### Get fraud assessment for a transaction

```
GET /api/v1/transactions/{transactionId}/assessment
```

Returns `200 OK` with the full assessment (risk score, all rule violations, severity), or `404` if no assessment exists for that transaction.

```bash
curl http://localhost:8081/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000/assessment
```

#### Record a transaction's outcome (the one write endpoint)

```
PATCH /api/v1/transactions/{transactionId}/outcome
```

Lets a fraud analyst record whether a flagged (or cleared) transaction turned out to
actually be fraud or a false positive, once reviewed. This is the ground-truth feedback
the scoring model's likelihood ratios will eventually be calibrated against — see
`ScoringProperties` and `DESIGN.md` §5.

Outcomes are a **one-time disposition**: an assessment starts as `UNRESOLVED` and can be
set to `CONFIRMED_FRAUD` or `FALSE_POSITIVE` exactly once. A second attempt to update an
already-resolved assessment is rejected — outcomes aren't correctable through this
endpoint once set.

Request body:
```json
{ "outcome": "CONFIRMED_FRAUD" }
```

```bash
curl -X PATCH http://localhost:8081/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000/outcome \
  -H "Content-Type: application/json" \
  -d '{ "outcome": "CONFIRMED_FRAUD" }'
```

| Status | Meaning |
|---|---|
| `200 OK` | Outcome recorded; returns the updated assessment |
| `400 Bad Request` | Missing/invalid `outcome` value (must be `CONFIRMED_FRAUD` or `FALSE_POSITIVE` — `UNRESOLVED` cannot be set manually) |
| `404 Not Found` | No assessment exists for this transaction |
| `409 Conflict` | The assessment already has a resolved outcome |

Requires the same JWT bearer token and `FRAUD_ANALYST`/`FRAUD_ENGINEER` role as every
other `/api/v1/**` endpoint — no separate write permission exists.

#### List flagged (fraudulent) transactions

```
GET /api/v1/transactions/flagged
```

| Param | Type | Required | Description |
|---|---|---|---|
| `customerId` | string | no | Narrow to a specific customer |
| `ruleViolated` | string | no | Narrow to assessments where this rule fired (e.g. `VelocityRule`) |
| `minRiskScore` | int | no | Lower bound on risk score (inclusive) |
| `maxRiskScore` | int | no | Upper bound on risk score (inclusive). Combine with `minRiskScore` to query a band — e.g. `50–65` isolates low-confidence fraud for false-positive review |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | ISO-8601 | no | Pagination cursor |
| `pageSize` | int 1–1000 | no | Default 20 |

```bash
# All fraud in July
curl "http://localhost:8081/api/v1/transactions/flagged?from=2026-07-01T00:00:00Z&to=2026-07-31T23:59:59Z"

# Low-confidence band — likely false positives, worth reviewing
curl "http://localhost:8081/api/v1/transactions/flagged?minRiskScore=50&maxRiskScore=65"

# Velocity violations for a specific customer
curl "http://localhost:8081/api/v1/transactions/flagged?customerId=CUST-001&ruleViolated=VelocityRule"
```

#### List transactions pending review

```
GET /api/v1/transactions/pending-review
```

The elevated-but-not-confident band — corroborating weak signals (e.g. an off-hours
transaction that also fired a second weak rule) that don't cross the `FLAGGED`
threshold on their own, but are no longer silently treated the same as a clean
transaction either. Same filter set as `/flagged`; work this queue via
`PATCH /transactions/{id}/outcome` once reviewed.

| Param | Type | Required | Description |
|---|---|---|---|
| `customerId` | string | no | Narrow to a specific customer |
| `ruleViolated` | string | no | Narrow to assessments where this rule fired |
| `minRiskScore` | int | no | Lower bound on risk score (inclusive) |
| `maxRiskScore` | int | no | Upper bound on risk score (inclusive) |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | ISO-8601 | no | Pagination cursor |
| `pageSize` | int 1–1000 | no | Default 20 |

```bash
# Everything awaiting review this month
curl "http://localhost:8081/api/v1/transactions/pending-review?from=2026-07-01T00:00:00Z&to=2026-07-31T23:59:59Z"
```

#### List passed (cleared) transactions

```
GET /api/v1/transactions/passed
```

| Param | Type | Required | Description |
|---|---|---|---|
| `customerId` | string | no | Narrow to a specific customer |
| `minRiskScore` | int | no | Lower bound on risk score (inclusive) — cleared transactions are always low-scoring by construction, but this still lets you sort within that band |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | ISO-8601 | no | Pagination cursor |
| `pageSize` | int 1–1000 | no | Default 20 |

```bash
# Audit trail for a customer over the last month
curl "http://localhost:8081/api/v1/transactions/passed?customerId=CUST-001&from=2026-07-01T00:00:00Z"
```

---

### Rules

#### List registered rules

```
GET /api/v1/rules
```

Returns all rules ordered by priority, each with its name, version, enabled status, priority, and **live configuration parameters**. Config reflects the values currently active in the running instance — useful for verifying deployments and debugging why a transaction was or was not flagged.

```bash
curl http://localhost:8081/api/v1/rules
```

Response `200 OK` (excerpt):
```json
[
  {
    "ruleName": "AmountThresholdRule",
    "ruleVersion": "1.0",
    "priority": 1,
    "enabled": true,
    "config": {
      "threshold": 5000.00,
      "categoryThresholds": { "RETAIL": 15000.00, "GROCERY": 3000.00 }
    }
  },
  {
    "ruleName": "VelocityRule",
    "ruleVersion": "1.0",
    "priority": 2,
    "enabled": true,
    "config": { "windowMinutes": 10, "maxTransactions": 5 }
  }
]
```

Rule configuration changes require redeployment — there is no runtime PATCH endpoint.

---

### Customers

#### Get customer risk summary

```
GET /api/v1/customers/{customerId}/risk-summary
```

| Param | Type | Required | Description |
|---|---|---|---|
| `since` | ISO-8601 | no | Scopes all activity metrics (counts, fraud rate, highest score, top rules) to this point in time onwards. `firstTransactionAt` and `lastTransactionAt` are always all-time values regardless of `since`. |

Pre-aggregated risk profile for a customer. Designed for customer service agents who need a quick read before approving a dispute or escalating a case.

```bash
# All-time risk profile
curl http://localhost:8081/api/v1/customers/CUST-001/risk-summary

# Activity scoped to the last 30 days
curl "http://localhost:8081/api/v1/customers/CUST-001/risk-summary?since=2026-07-01T00:00:00Z"
```

Response `200 OK`:
```json
{
  "customerId": "CUST-001",
  "totalTransactions": 342,
  "flaggedCount": 4,
  "passedCount": 338,
  "fraudRate": 1.17,
  "highestRiskScore": 75,
  "mostTriggeredRules": ["AmountThresholdRule", "VelocityRule", "TimeOfDayAnomalyRule"],
  "firstTransactionAt": "2025-01-15T08:00:00Z",
  "lastTransactionAt": "2026-07-23T09:00:00Z"
}
```

---

### Merchants

#### List flagged transactions for a merchant

```
GET /api/v1/merchants/{merchantId}/flagged
```

| Param | Type | Required | Description |
|---|---|---|---|
| `ruleViolated` | string | no | Narrow to assessments where this rule fired |
| `minRiskScore` | int | no | Lower bound on risk score (inclusive) |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | ISO-8601 | no | Pagination cursor |
| `pageSize` | int 1–1000 | no | Default 20 |

```bash
# All fraud at a merchant in July
curl "http://localhost:8081/api/v1/merchants/MERCH-NIKE-ZA/flagged?from=2026-07-01T00:00:00Z&to=2026-07-31T23:59:59Z"

# High-severity fraud only
curl "http://localhost:8081/api/v1/merchants/MERCH-NIKE-ZA/flagged?minRiskScore=75"

# Which velocity violations occurred at this merchant?
curl "http://localhost:8081/api/v1/merchants/MERCH-NIKE-ZA/flagged?ruleViolated=VelocityRule"
```

#### Get merchant risk summary

```
GET /api/v1/merchants/{merchantId}/risk-summary
```

| Param | Type | Required | Description |
|---|---|---|---|
| `since` | ISO-8601 | no | Scopes activity metrics to this point in time onwards. `uniqueCustomers`, `firstTransactionAt`, and `lastTransactionAt` are always all-time values. |

Pre-aggregated risk profile for a merchant. Useful for merchant risk teams and onboarding reviews.

```bash
curl "http://localhost:8081/api/v1/merchants/MERCH-NIKE-ZA/risk-summary?since=2026-07-01T00:00:00Z"
```

Response `200 OK`:
```json
{
  "merchantId": "MERCH-NIKE-ZA",
  "totalTransactions": 1842,
  "flaggedCount": 12,
  "passedCount": 1830,
  "fraudRate": 0.65,
  "highestRiskScore": 85,
  "uniqueCustomers": 534,
  "mostTriggeredRules": ["AmountThresholdRule", "VelocityRule"],
  "firstTransactionAt": "2024-01-01T00:00:00Z",
  "lastTransactionAt": "2026-07-23T09:00:00Z"
}
```

---

### Stats

#### Global fraud summary

```
GET /api/v1/stats/fraud-summary
```

| Param | Type | Required | Description |
|---|---|---|---|
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt`. Omit both for all-time totals. |

Returns global fraud counts and a per-rule breakdown showing how often each rule contributed to a fraud flag within the window. Designed for operational dashboards and weekly fraud reports.

```bash
# July fraud summary
curl "http://localhost:8081/api/v1/stats/fraud-summary?from=2026-07-01T00:00:00Z&to=2026-07-31T23:59:59Z"
```

Response `200 OK`:
```json
{
  "from": "2026-07-01T00:00:00Z",
  "to": "2026-07-31T23:59:59Z",
  "totalAssessed": 48320,
  "totalFlagged": 241,
  "totalPassed": 48079,
  "fraudRate": 0.50,
  "ruleBreakdown": [
    { "ruleName": "AmountThresholdRule", "count": 98,  "percentage": 40.66 },
    { "ruleName": "VelocityRule",        "count": 72,  "percentage": 29.88 },
    { "ruleName": "BlacklistedMerchantRule", "count": 45, "percentage": 18.67 }
  ]
}
```

> Replace `8081` with the port for the environment you started.

---

## Fraud Rules

| Rule | Trigger | Severity | Priority |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | Amount exceeds threshold — default R5,000, with configurable per-category overrides (e.g. RETAIL R15,000, GROCERY R3,000) | HIGH | 1 |
| `VELOCITY` | > 5 transactions in 10 minutes for same customer. Boosted to CRITICAL when the merchant category is high-risk (crypto, money-transfer, wire-transfer). | HIGH → CRITICAL | 2 |
| `DUPLICATE_TRANSACTION` | Same merchant + same amount + same currency within window — **120 s** for CARD_PRESENT / CONTACTLESS / ATM, **300 s** for CARD_NOT_PRESENT. | CRITICAL | 3 |
| `BLACKLISTED_MERCHANT` | Merchant ID on the blacklist (Caffeine-cached, 5-min TTL) | CRITICAL | 4 |
| `GEOGRAPHIC_ANOMALY` | Implied travel speed between two consecutive physical locations exceeds 900 km/h. Skipped when transactions are < 1 minute apart (clock-skew guard). Falls back to merchant registered location when the transaction carries no coordinates. | CRITICAL | 5 |
| `CARD_CLONING` | Same transaction amount charged to 2+ different merchants within 10 minutes — hallmark of automated card testing with a cloned card. | MEDIUM | 6 |
| `TIME_OF_DAY_ANOMALY` | Transaction occurs in the off-hours window (default 23:00–05:00 UTC). | MEDIUM | 7 |
| `HIGH_RISK_MERCHANT_CATEGORY` | Merchant category is crypto/money-transfer/wire-transfer (HIGH) or gambling/casino/payday-loan (MEDIUM). | HIGH / MEDIUM | 8 |
| `DEVICE_FINGERPRINT` | Transaction arrives from a device fingerprint the customer has never used before (within the lookback window). Skipped when no fingerprint is supplied or the customer has no prior fingerprinted history. | HIGH | 9 |
| `MULTI_CHANNEL_ANOMALY` | A physical-channel transaction (CARD_PRESENT, CONTACTLESS, ATM) and an online transaction (CARD_NOT_PRESENT) occur within 5 minutes of each other for the same customer. | MEDIUM | 10 |
| `CROSS_MERCHANT_VELOCITY` | ≥ 10 total transactions across any merchants within 10 minutes — provides an additional MEDIUM data point before the HIGH velocity rule threshold is reached. | MEDIUM | 11 |
| `CUMULATIVE_SPENDING` | Rolling spend exceeds the hourly limit (default R10,000) or daily limit (default R25,000). Hourly is computed from in-context recent transactions; daily is a pre-aggregated DB query. | HIGH | 12 |

**Risk scoring:** Rules are combined with a log-odds (naive-Bayes) model rather than summed points — each fired rule carries a calibrated likelihood ratio (how much more likely fraud is, given that rule fired, versus not), keyed by rule name **and** severity so rules whose severity varies at runtime (e.g. `VelocityRule`'s high-risk-category escalation) are calibrated per variant. The posterior fraud probability is the sigmoid of the prior log-odds plus the sum of each violation's log-likelihood-ratio; `riskScore` is that probability × 100 (0–100). The verdict is a **three-way disposition**, not a binary flag, driven by two thresholds: `fraud.scoring.fraud-probability-threshold` (default 0.5) and below it, `fraud.scoring.review-probability-threshold` (default 0.10).

| `disposition` | When |
|---|---|
| `FLAGGED` | probability ≥ `fraud-probability-threshold` |
| `PENDING_REVIEW` | probability ≥ `review-probability-threshold`, below `fraud-probability-threshold` |
| `CLEARED` | probability below `review-probability-threshold` |

This deliberately does **not** treat "one strong signal" and "several weak, possibly-correlated signals" as equivalent the way a flat point sum would — some rules (`BLACKLISTED_MERCHANT`, `GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, boosted `VELOCITY`, `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, high-risk-category `HIGH_RISK_MERCHANT_CATEGORY`) are calibrated to be `FLAGGED` on their own; others (`AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, `CUSTOMER_AMOUNT_ANOMALY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`) are calibrated as weak evidence that needs a second, independent corroborating signal to cross the `FLAGGED` threshold. Two transactions that fired only a weak rule each land in `PENDING_REVIEW` instead of being silently treated the same as a clean transaction — that band is exactly what `GET /transactions/pending-review` (§ API Reference) surfaces for an analyst to work.

The likelihood ratios in `ScoringProperties` are domain-judgment starting points, not values derived from labelled outcome data — this system doesn't yet have a confirmed-fraud / false-positive feedback loop to calibrate against, so treat them as a reasoned first pass rather than ground truth.

---

## Kafka Topics

| Topic | Partitions | Direction | Message type |
|---|---|---|---|
| `transactions.raw` | 6 | Inbound (consumed) | `TransactionEvent` Protobuf |
| `transactions.raw-0`, `transactions.raw-1` | 6 | Internal (retry) | auto-created by `@RetryableTopic` |
| `transactions.raw.DLT` | 1 | Dead-letter | exhausted-retry events |
| `transactions.flagged` | 3 | Outbound (produced) | `FraudulentTransactionEvent` Protobuf |
| `transactions.pending-review` | 3 | Outbound (produced) | `PendingReviewTransactionEvent` Protobuf |
| `transactions.passed` | 3 | Outbound (produced) | `ClearedTransactionEvent` Protobuf |

All topics use replication factor 2 across the 3-broker cluster. Schemas are registered with and enforced by Confluent Schema Registry.

---

## Exactly-Once Semantics

The DB write and Kafka publish are atomic via `ChainedKafkaTransactionManager`:

1. Kafka TX opens
2. DB TX opens
3. `FraudAssessment` + updated `TransactionStatus` written to Postgres
4. Outcome event published to `transactions.flagged`, `transactions.pending-review`, or `transactions.passed`
5. DB TX commits; Kafka TX commits

If the DB commit fails, the Kafka TX aborts — no message is published, and the consumer retries cleanly.
If the Kafka commit fails after the DB commit, the consumer retries; the idempotency guard (`findByIdOnly`) skips the re-save and re-publishes the event.

Consumer uses `isolation.level=read_committed` so downstream readers only see committed messages.

---

## Testing

### Unit tests

```bash
make test-unit
```

Each rule is tested in isolation with zero Spring context — fast and deterministic. Covers category-tiered thresholds, type-aware duplicate windows, geographic speed edge cases, merchant-location fallback, off-hours wrap-around, device fingerprint unknown/known paths, multi-channel switching, cross-merchant velocity boundaries, and hourly/daily spend limits.

### REST controller tests

`@WebMvcTest` slices — Spring MVC wiring with Mockito-backed service/mapper dependencies. No database or Kafka required. Each controller class has its own test class:

| Test class | Controller | Tests |
|---|---|---|
| `TransactionQueryControllerTest` | `TransactionQueryController` | 38 |
| `MerchantControllerTest` | `MerchantController` | 11 |
| `StatsControllerTest` | `StatsController` | 4 |
| `CustomerControllerTest` | `CustomerController` | 4 |
| `RuleControllerTest` | `RuleController` | 4 |
| `StandaloneTransactionControllerTest` | Standalone profile smoke test | 1 |

Coverage per controller:

**`GET /transactions`** — paginated results, cursor passthrough, date range parsing, `pageSize` min/max validation, malformed timestamp → 400

**`GET /transactions/{id}`** — found → 200 with DTO, not found → 404, invalid UUID → 400

**`GET /transactions/{id}/assessment`** — found → 200, not found → 404, invalid UUID → 400

**`GET /transactions/flagged`** — no filters, per-filter isolation (customerId, ruleViolated, minRiskScore, maxRiskScore, date range), combined risk score band, `pageSize` validation

**`GET /transactions/pending-review`** — no filters, customerId passthrough, combined risk score band, date range, `pageSize`/sort validation

**`GET /transactions/passed`** — no filters, cursor passthrough, customerId + date range, `minRiskScore` filter

**`GET /merchants/{id}/flagged`** — no filters, date range, ruleViolated, minRiskScore, combined ruleViolated + minRiskScore, next-cursor set when `hasMore=true`, `pageSize` validation, malformed date → 400

**`GET /merchants/{id}/risk-summary`** — no since, with since (verifies Instant passed to service), malformed since → 400

**`GET /customers/{id}/risk-summary`** — full response shape, with since, malformed since → 400, zeroed summary

**`GET /stats/fraud-summary`** — no date range, with date range (verifies Instant passthrough), malformed from → 400, empty breakdown

**`GET /rules`** — returns all rules, empty list, disabled rule included, `config` map populated

### Integration tests (Testcontainers)

```bash
make test-integration
```

Spins up real PostgreSQL and Kafka containers. Tests the full pipeline end-to-end:

- Transaction published to `transactions.raw` → consumed → rule engine → assessment persisted
- Clean transaction published to `transactions.passed`
- High-amount transaction published to `transactions.flagged`
- Blacklisted merchant detection
- Query API: customer transactions returning `PENDING` and `ASSESSED` statuses
- 404 on assessment for unknown transaction ID

Uses `mock://` Confluent Schema Registry (in-process) so no live registry is needed for tests.

### Run all tests

```bash
make test
```

---

## Load & Performance Tests

Load tests run exclusively against the `load` environment, which includes InfluxDB and Grafana for live metrics.

### 1. Start the load environment

```bash
make load
```

### 2. Open the live dashboard

```bash
make grafana
# or open http://localhost:3000 manually
```

The k6 dashboard is pre-provisioned — no login or setup required.

### 3. Run a scenario

```bash
make load-test                       # 01-baseline (default)
make load-test SCENARIO=02-ramp
make load-test SCENARIO=03-spike
make load-test SCENARIO=04-fraud-rules
```

### 4. Run all scenarios sequentially

```bash
make load-test-all
```

### Scenarios

| Scenario | Purpose | Load |
|---|---|---|
| `01-baseline` | Steady-state throughput | 100 VUs, 2 min |
| `02-ramp` | Find degradation point under increasing load | 10 → 500 VUs, 5 min |
| `03-spike` | Validate Kafka absorbs a sudden burst | 50 → 500 → 50 VUs, ~4 min |
| `04-fraud-rules` | Mixed read/query traffic | 100 VUs, 3 min |

Pass/fail thresholds are in `load-tests/config.js`:

```js
http_req_duration: ['p(95)<1500', 'p(99)<2000'],
http_req_failed:   ['rate<0.05'],
```

HTML reports are written to `load-tests/results/` at the end of each run.

---

## Configuration

All rule thresholds and windows are configurable via `application.yml` or environment variables:

```yaml
fraud:
  rules:
    context-lookback-minutes: 60
    amount-threshold:
      enabled: true
      threshold: 5000.00
      category-thresholds:
        RETAIL: 15000.00
        ELECTRONICS: 15000.00
        TRAVEL: 20000.00
        GROCERY: 3000.00
        MONEY_TRANSFER: 2000.00
        WIRE_TRANSFER: 2000.00
    velocity:
      enabled: true
      max-transactions: 5
      window-minutes: 10
    duplicate:
      enabled: true
      card-present-window-seconds: 120
      card-not-present-window-seconds: 300
    blacklisted-merchant:
      enabled: true
    geographic:
      enabled: true
      window-minutes: 60
      max-travel-speed-kmh: 900.0
    card-cloning:
      enabled: true
      window-minutes: 10
      min-different-merchants: 2
    time-of-day:
      enabled: true
      off-hours-start-hour: 23
      off-hours-end-hour: 5
    high-risk-category:
      enabled: true
      high-risk-keywords: [CRYPTO, CRYPTOCURRENCY, CRYPTO_EXCHANGE, MONEY_TRANSFER, WIRE_TRANSFER]
      medium-risk-keywords: [GAMBLING, CASINO, BETTING, PAYDAY_LOAN]
    device-fingerprint:
      enabled: true
      window-minutes: 60
    multi-channel:
      enabled: true
      window-minutes: 5
    cross-merchant-velocity:
      enabled: true
      max-transactions: 10
      window-minutes: 10
    cumulative-spending:
      enabled: true
      hourly-limit: 10000.00
      daily-limit: 25000.00
      hourly-window-minutes: 60

fraud:
  scoring:
    prior-fraud-probability: 0.01       # assumed base fraud rate, before any rule evidence
    fraud-probability-threshold: 0.5    # posterior probability at/above which a transaction is FLAGGED
    review-probability-threshold: 0.10  # at/above this (but below fraud-probability-threshold) -> PENDING_REVIEW
    likelihood-ratios:                  # "RULE_NAME:SEVERITY" -> ratio; see ScoringProperties.java
      "AMOUNT_THRESHOLD:HIGH": 2.0      # example override — Java defaults apply if omitted; note the
                                         # quoted key — YAML requires quoting a key containing a colon
    default-likelihood-ratio-low: 1.3
    default-likelihood-ratio-medium: 3.0
    default-likelihood-ratio-high: 9.0
    default-likelihood-ratio-critical: 120.0
```

Startup validation: every rule window measured against `recentCustomerTransactions` (currently `velocity`, `geographic`, `card-cloning`, `device-fingerprint`, `multi-channel`, `cross-merchant-velocity`, and `cumulative-spending`'s hourly window) is checked against `context-lookback-minutes` in one exhaustive map (`RuleProperties.validate()`) — if any exceeds it, the app fails to start with an `IllegalStateException` rather than silently under-counting.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `SCHEMA_REGISTRY_URL` | `http://schema-registry:8081` | Confluent Schema Registry |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `frauddb` | PostgreSQL connection |
| `DB_USER` / `DB_PASSWORD` | `fraud` / `fraud` | PostgreSQL credentials |
| `VAULT_HOST` / `VAULT_TOKEN` | `vault` / `dev-root-token` | HashiCorp Vault |
| `FRAUD_IDP_URI` | `https://idp.acmebank.example/oauth2/default` | JWT issuer — JWKS fetched from `{issuer}/.well-known/openid-configuration` at startup |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4317` | OTel GRPC endpoint (Instana agent in K8s, unset locally — spans dropped gracefully) |

---

## Security

### Profile-based behaviour

Security is profile-gated so local development and tests require no credentials.

| Profile | Behaviour |
|---|---|
| `local`, `standalone` | All requests permitted. No IDP contact. |
| `test` | All requests permitted. `@WebMvcTest` tests pass without auth headers. |
| `int`, `qa`, `load`, `prod` | JWT bearer token required on `/api/v1/**`. |

> Note: the `dev` **environment** (`make dev`, `docker-compose.dev.yml`) activates the `local` Spring **profile** — not a profile named `dev` — so it falls in the open bucket above, with no auth required. The `int`/`qa`/`load`/`prod` environments each activate their own like-named profile.

### Authentication

The API uses OAuth2 JWT bearer tokens issued by the Acme IDP. Include the token as a standard `Authorization` header:

```
Authorization: Bearer <jwt>
```

The IDP base URI is read from `FRAUD_IDP_URI`. At startup the app fetches the JWKS from
`{idpBaseUri}/.well-known/openid-configuration` and caches the public keys for signature
validation. If the IDP is unreachable at startup, the app will fail to start — this is
intentional (fail-fast over serving unauthenticated requests).

### Authorisation

JWT tokens must carry a `roles` claim containing at least one of the allowed roles:

| Role | Purpose |
|---|---|
| `FRAUD_ANALYST` | Read-only access to fraud assessments, customer/merchant risk profiles |
| `FRAUD_ENGINEER` | Same as above; intended for engineering team access |

Roles are mapped to Spring Security authorities with a `ROLE_` prefix. The allowed roles and
the protected/whitelisted path lists are configurable via `fraud.security.*` in `application.yml`.

### Open endpoints (no token required in all profiles)

| Path | Reason |
|---|---|
| `/actuator/health` | Docker and Kubernetes liveness/readiness probes |
| `/actuator/info` | Non-sensitive build metadata |
| `/actuator/prometheus` | Prometheus scrape target (network-restricted in K8s) |
| `/swagger-ui/**`, `/swagger-ui.html` | API documentation |
| `/v3/api-docs`, `/v3/api-docs/**` | OpenAPI spec |

### Swagger UI

The Swagger UI at `/swagger-ui.html` shows a lock icon on all endpoints and an `Authorize` button
at the top. Paste a valid bearer token there to make authenticated requests directly from the UI.

---

## Health & Observability

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8081/actuator/prometheus
```

### Custom Prometheus metrics

| Metric | Type | Description |
|---|---|---|
| `fraud.assessments.total{verdict="FRAUDULENT"}` | Counter | Fraudulent assessments since startup |
| `fraud.assessments.total{verdict="PASSED"}` | Counter | Cleared assessments since startup |
| `fraud.dlt.total` | Counter | Transactions that exhausted all retries and reached the dead-letter topic |
| `fraud.rule.evaluation.duration.seconds` | Timer | Full rule engine evaluation time (p50/p95/p99) |

Consumer lag per partition is automatically exposed via `kafka_consumer_fetch_manager_records_lag` from the Micrometer + Spring Kafka auto-instrumentation.

### Distributed tracing

All Kafka listener invocations and HTTP requests are traced via the Micrometer OTel bridge and exported via OTLP gRPC to `${MANAGEMENT_OTLP_TRACING_ENDPOINT}`. Sampling probability is 10% by default.

**In Kubernetes** the Instana agent runs as a DaemonSet. Each pod's Helm values file injects `HOST_IP` (the node IP) and sets `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://$(HOST_IP):4317`, pointing the OTLP exporter at the local agent. Traces are visible in the Instana UI with full service dependency maps.

**Locally** `MANAGEMENT_OTLP_TRACING_ENDPOINT` is not set. The app defaults to `http://localhost:4317`, finds nothing, and drops spans silently — all other functionality is unaffected.

### Structured logging and MDC correlation

Every log line carries a consistent set of context fields:

| MDC field | Set by | Value |
|---|---|---|
| `requestId` | `MdcLoggingFilter` | Random UUID per HTTP request |
| `transactionId` | `TransactionConsumer` | Transaction UUID |
| `kafkaTopic` | `TransactionConsumer` | Topic the event was consumed from |
| `kafkaPartition` | `TransactionConsumer` | Partition number |
| `kafkaOffset` | `TransactionConsumer` | Message offset |

Customer and merchant identifiers are intentionally **not** logged (PII removal). `transactionId` is sufficient to join all tables and trace end-to-end.

Log format:
```
2026-06-15 12:00:00.123  INFO [requestId] [txn=<uuid>] [transactions.raw:42] TransactionConsumer : ...
```

---

## Data Lifecycle

The `transactions` table is range-partitioned by `timestamp` (daily). `PartitionMaintenanceJob` runs every night at 02:00:

- Creates the partition for `today + 2 days` (pre-creation buffer)
- Drops the partition for `today − 91 days` (90-day retention)

The `fraud_assessments` table has a composite foreign key `(transaction_id, transaction_timestamp)` referencing the partitioned table's composite primary key `(id, timestamp)`.

Flyway manages all schema changes (`V1`–`V6`). `spring.jpa.hibernate.ddl-auto=validate` means the app will fail to start if the entity model diverges from the schema.

---

## Quick Reference

```bash
make help
```

---

## Project Structure

```
src/
├── main/java/com/fraudengine/
│   ├── FraudRuleEngineApplication.java
│   ├── api/
│   │   ├── controller/
│   │   │   ├── TransactionQueryController.java  # /api/v1/transactions — history, flagged, passed
│   │   │   ├── RuleController.java              # /api/v1/rules
│   │   │   ├── CustomerController.java          # /api/v1/customers/{id}/risk-summary
│   │   │   ├── MerchantController.java          # /api/v1/merchants/{id}/flagged + risk-summary
│   │   │   └── StatsController.java             # /api/v1/stats/fraud-summary
│   │   ├── dto/
│   │   │   ├── TransactionSummaryDto.java
│   │   │   ├── FraudAssessmentDto.java
│   │   │   ├── RuleViolationDto.java
│   │   │   ├── RuleDto.java                     # includes live config map
│   │   │   ├── PagedResponse.java
│   │   │   ├── FraudSummaryDto.java             # global fraud aggregates
│   │   │   ├── RuleBreakdownDto.java            # per-rule count/percentage in fraud summary
│   │   │   ├── CustomerRiskSummaryDto.java      # pre-aggregated customer risk profile
│   │   │   └── MerchantRiskSummaryDto.java      # pre-aggregated merchant risk profile
│   │   └── mapper/
│   │       └── TransactionMapper.java           # MapStruct — FraudAssessment, RuleViolation,
│   │                                            # Transaction, FraudRule → DTOs
│   ├── config/             # KafkaConfig, CacheConfig, RuleProperties, FraudMetrics, SchedulingConfig
│   ├── consumer/           # TransactionConsumer (@KafkaListener + @DltHandler)
│   ├── engine/
│   │   ├── FraudRule.java                  # Strategy interface (evaluate, getRuleName, getConfig, …)
│   │   ├── RuleEngine.java                 # Orchestrates evaluation + risk scoring
│   │   ├── EvaluationContext.java          # Carries pre-fetched context (transactions, blacklist,
│   │   │                                   # merchant location, daily spend total)
│   │   ├── EvaluationContextBuilder.java   # All DB queries run here before rule evaluation
│   │   ├── ReferenceDataCache.java         # Caffeine-cached blacklist + merchant location reads
│   │   │                                   # (separate bean so @Cacheable isn't bypassed by self-invocation)
│   │   └── rules/
│   │       ├── AmountThresholdRule.java    # priority 1  — category-tiered thresholds
│   │       ├── VelocityRule.java           # priority 2  — with high-risk category boost
│   │       ├── DuplicateTransactionRule.java  # priority 3
│   │       ├── BlacklistedMerchantRule.java   # priority 4
│   │       ├── GeographicAnomalyRule.java     # priority 5  — merchant location fallback
│   │       ├── CardCloningRule.java           # priority 6
│   │       ├── TimeOfDayAnomalyRule.java      # priority 7
│   │       ├── HighRiskMerchantCategoryRule.java  # priority 8
│   │       ├── DeviceFingerprintRule.java     # priority 9
│   │       ├── MultiChannelAnomalyRule.java   # priority 10
│   │       ├── CrossMerchantVelocityRule.java # priority 11
│   │       ├── CumulativeSpendingRule.java    # priority 12
│   │       └── CustomerAmountAnomalyRule.java # priority 13 — personal spending baseline
│   ├── exception/          # GlobalExceptionHandler (400 for validation, type mismatch, date parse)
│   ├── filter/             # MdcLoggingFilter
│   ├── kafka/              # AssessmentProducer, TransactionEvent (POJO), event POJO classes
│   ├── model/              # Transaction (+ deviceFingerprint), FraudAssessment, RuleViolation,
│   │                       # BlacklistedMerchant, MerchantLocation + enums
│   ├── proto/              # ProtoMapper (Protobuf ↔ domain model conversion)
│   ├── repository/
│   │   ├── TransactionRepository.java       # JPQL queries — customer/merchant history, counts,
│   │   │                                    # timestamps, duplicate candidates
│   │   ├── FraudAssessmentRepository.java   # JPQL queries — flagged/passed feeds, merchant feed,
│   │   │                                    # aggregate counts and top-rule GROUP BY
│   │   ├── BlacklistedMerchantRepository.java
│   │   └── MerchantLocationRepository.java
│   └── service/
│       ├── TransactionQueryService.java     # All read operations for the API layer
│       ├── RuleManagementService.java
│       └── PartitionMaintenanceJob.java
├── main/proto/
│   ├── transaction_event.proto           # TransactionEvent + TransactionType enum
│   ├── cleared_transaction_event.proto   # ClearedTransactionEvent
│   ├── fraudulent_transaction_event.proto # FraudulentTransactionEvent
│   └── pending_review_transaction_event.proto # PendingReviewTransactionEvent
├── main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__initial_schema.sql
│       ├── V2__seed_blacklisted_merchants.sql
│       ├── V3__add_transaction_type.sql
│       ├── V4__partition_transactions.sql
│       ├── V5__add_device_fingerprint.sql      # device_fingerprint column + index
│       ├── V6__add_merchant_locations.sql      # merchant_locations table + seed data
│       └── V7__add_assessment_outcome.sql      # outcome column + index on fraud_assessments
└── test/java/com/fraudengine/
    ├── api/controller/
    │   ├── TransactionQueryControllerTest.java  # 22 tests
    │   ├── MerchantControllerTest.java          # 11 tests
    │   ├── CustomerControllerTest.java          # 4 tests
    │   ├── StatsControllerTest.java             # 4 tests
    │   ├── RuleControllerTest.java              # 4 tests
    │   └── StandaloneTransactionControllerTest.java
    ├── engine/
    │   ├── RuleEngineTest.java
    │   └── rules/              # Unit tests — one per rule (13 rule test classes)
    ├── kafka/                  # AssessmentProducerTest (Mockito)
    └── integration/            # TransactionIntegrationTest (Testcontainers + mock Schema Registry)

load-tests/
├── config.js               # Shared BASE_URL, thresholds, data pools
├── scenarios/              # One file per k6 scenario
├── lib/reporter.js         # HTML summary generation
├── results/                # Generated HTML reports (gitignored)
└── grafana/
    ├── provisioning/       # Auto-configured datasource + dashboard provider
    └── dashboards/         # Pre-built k6 Grafana dashboard
```
