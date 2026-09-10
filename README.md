# Fraud Rule Engine

> New here? [TLDR.md](./TLDR.md) is the 2-minute version: what this is, how to run it, and what it deliberately is/isn't.

A production-grade backend service that consumes transaction events from Kafka, evaluates them against a configurable set of fraud detection rules, persists assessments to PostgreSQL, and routes outcomes to dedicated downstream topics.

**Stack:** Java 21 · Spring Boot 3.3 · Apache Kafka 3 (KRaft, 3-broker) · Protobuf · Confluent Schema Registry · PostgreSQL 16 (range-partitioned) · HashiCorp Vault · OpenTelemetry · Prometheus · Docker · JUnit 5 · Mockito · Testcontainers · k6

---

## Architecture

See [DESIGN.md](./DESIGN.md) for the full system design document covering all architectural decisions, trade-offs, and extensibility considerations. See [docs/future-prospects.md](./docs/future-prospects.md) for features and directions not yet built.

```
External System
      │
      ▼  TransactionEvent (Protobuf)
transactions.raw  ──────────────────────────────────────────────────────┐
      │                                                                  │
      ▼                                                             retry topics
  TransactionConsumer                                       (transactions.raw-retry-0,
      │   @RetryableTopic (3 attempts, exponential backoff)  transactions.raw-retry-1)
      │   @Transactional(chainedKafkaTransactionManager)               │
      │   idempotency guards: findByIdOnly (transaction row),          │
      │   findByTransactionId (assessment) → skip re-evaluate/         │
      │   re-save/re-publish entirely if already assessed              │
      │                                                                 ▼
      ▼                                                      transactions.raw.DLT
  EvaluationContextBuilder                             (@DltHandler → status=FAILED)
      │   queries recent transactions, merchant locations,
      │   daily spend total — all before rule evaluation
      ▼
  Rule Engine (Strategy Pattern)
  ├── AmountThresholdRule           priority 1   HIGH      (category-tiered thresholds)
  ├── VelocityRule                  priority 2   HIGH      (high-risk category boost → CRITICAL)
  ├── DuplicateTransactionRule      priority 3   CRITICAL  (type-aware window, currency-aware)
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

Prerequisites: **Docker**, plus a local **JDK 21** and **Maven** (`mvn` on `PATH`). No Kafka installation required, since that runs inside containers. The JAR is built on the host, not inside the image (`scripts/deploy.sh`); see `docker/Dockerfile`'s header comment for why the build isn't containerized (Confluent's Maven repository needs authentication that isn't available in a plain build container).

Each environment is fully self-contained: its own app instance, Postgres database, Kafka cluster, and observability stack, all on separate host ports so multiple environments can run simultaneously.

| Service | dev | load-test | prod |
|---|---|---|---|
| App | 8081 | 8084 | 8085 |
| Postgres | 5433 | 5436 | 5437 |
| Kafka broker 1 | 9192 | 9492 | 9592 |
| Kafka broker 2 | 9193 | 9493 | 9593 |
| Kafka broker 3 | 9194 | 9494 | 9594 |
| Schema Registry | 8091 | — | — |
| Vault | 8200 | — | — |
| Instana agent | — | — | — |
| Prometheus | 9090 | — | — |

> Schema Registry, Vault, and Prometheus host-port mappings are only exposed in the `dev` environment; in other environments they're accessible within the Docker network. The Instana agent row is intentionally all dashes: tracing is OpenTelemetry/OTLP to an Instana agent injected via Helm in Kubernetes only, and none of the `docker-compose*.yml` files run one, so locally (any environment, including `dev`) the app finds no tracing backend and drops spans gracefully.

### Start an environment

```bash
make dev
make load-test
make prod
```

Each command:
1. Builds the app image from source
2. Starts Postgres and waits until healthy
3. Starts the 3-broker Kafka cluster and waits until healthy
4. Starts Schema Registry and waits until healthy
5. Starts Vault in dev mode, pre-unsealed, for `dev`/`load-test`; `prod`'s compose override replaces this with a server-mode Vault + one-shot `vault-init` AppRole flow (`VAULT_ROLE_ID`/`VAULT_SECRET_ID` printed on first run) instead
6. Starts the fraud-engine (Flyway runs migrations on boot)
7. Polls `/actuator/health` until the app is ready

Postgres data volumes are named per environment and persist across restarts.

### Try it out (`dev` only)

`make dev` runs the app under the `local` Spring profile, which disables the Kafka consumer and activates a synchronous HTTP stub instead (`StandaloneTransactionController`). It's the only way to feed transactions into a locally-run environment without producing raw Protobuf to Kafka yourself. Not present in `load-test`/`prod`, where the real Kafka pipeline is the only ingress (see [DESIGN.md §3](./DESIGN.md#3-inbound-layer--kafka-ingestion)).

```bash
# Submit one transaction and see the assessment inline
curl -X POST http://localhost:8081/api/v1/standalone/submit \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-001","merchantId":"MERCH-001","amount":150.00,"currency":"ZAR","transactionType":"CARD_PRESENT"}'

# Or generate a batch of random transactions through the rule engine
make stream ENV=dev COUNT=500
```

### Tear down

```bash
make stop dev
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

> In `load-test`/`prod` there is no HTTP submission endpoint: transactions enter exclusively via the `transactions.raw` Kafka topic, and the API is read-only with one deliberate exception: `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record a fraud assessment's real-world ground truth (see below). `dev`/`standalone` are the exception to that: `POST /api/v1/standalone/submit` and `/stream` are a demo/dev-only synchronous stub, active only under those two profiles. See "Try it out" above and [DESIGN.md §3](./DESIGN.md#3-inbound-layer--kafka-ingestion).

All paginated endpoints return a consistent envelope:

```json
{ "data": [...], "hasMore": true, "nextCursor": "MjAyNi0wNy0yM1QwOTowMDowMFp8M2YyYTFiNGMtNDU2Ny00ODlhLWJjZGUtMTIzNDU2Nzg5YWJj" }
```

`nextCursor` is an opaque, base64-encoded token (internally a timestamp + row id, used for keyset pagination with a stable tie-break) — treat it as an opaque string, not a timestamp you construct yourself. Pass it verbatim as the `cursor` parameter on the next request to advance the page; `null` means there are no more pages. All timestamps elsewhere in responses (and in `from`/`to`/`since` request parameters) are ISO-8601 UTC.

> Replace `8081` with the port for the environment you started (`8084` = load-test, `8085` = prod).

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
| `cursor` | opaque string | no | Pagination cursor — copy verbatim from the previous response's `nextCursor` |
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
      "merchantId": "MERCH-NIKE-ZA",
      "amount": "350.00",
      "currency": "ZAR",
      "transactionType": "CARD_NOT_PRESENT",
      "status": "ASSESSED",
      "timestamp": "2026-07-23T09:00:00Z",
      "assessment": {
        "disposition": "FLAGGED",
        "riskScore": 62,
        "violations": [{ "ruleName": "DEVICE_FINGERPRINT", "severity": "HIGH" }]
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
the scoring model's likelihood ratios will eventually be calibrated against, see
`ScoringProperties` and `DESIGN.md` §5.

Outcomes are a **one-time disposition**: an assessment starts as `UNRESOLVED` and can be
set to `CONFIRMED_FRAUD` or `FALSE_POSITIVE` exactly once. A second attempt to update an
already-resolved assessment is rejected: outcomes aren't correctable through this
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
| `400 Bad Request` | Missing/invalid `outcome` value (must be `CONFIRMED_FRAUD` or `FALSE_POSITIVE`; `UNRESOLVED` cannot be set manually) |
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
| `maxRiskScore` | int | no | Upper bound on risk score (inclusive). Combine with `minRiskScore` to query a band, e.g. `50–65` isolates low-confidence fraud for false-positive review |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | opaque string | no | Pagination cursor — copy verbatim from the previous response's `nextCursor` |
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

The elevated-but-not-confident band: corroborating weak signals (e.g. an off-hours
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
| `cursor` | opaque string | no | Pagination cursor — copy verbatim from the previous response's `nextCursor` |
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
| `minRiskScore` | int | no | Lower bound on risk score (inclusive); cleared transactions are always low-scoring by construction, but this still lets you sort within that band |
| `from` / `to` | ISO-8601 | no | Date range on `assessedAt` |
| `cursor` | opaque string | no | Pagination cursor — copy verbatim from the previous response's `nextCursor` |
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

Returns all rules ordered by priority, each with its name, version, enabled status, priority, and **live configuration parameters**. Config reflects the values currently active in the running instance, useful for verifying deployments and debugging why a transaction was or was not flagged.

```bash
curl http://localhost:8081/api/v1/rules
```

Response `200 OK` (excerpt):
```json
{
  "data": [
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
}
```

Rule configuration changes require redeployment; there is no runtime PATCH endpoint.

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
  "data": {
    "customerId": "CUST-001",
    "totalTransactions": 342,
    "flaggedCount": 4,
    "notFlaggedCount": 338,
    "fraudRate": 1.17,
    "highestRiskScore": 75,
    "mostTriggeredRules": ["AmountThresholdRule", "VelocityRule", "TimeOfDayAnomalyRule"],
    "firstTransactionAt": "2025-01-15T08:00:00Z",
    "lastTransactionAt": "2026-07-23T09:00:00Z"
  }
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
| `cursor` | opaque string | no | Pagination cursor — copy verbatim from the previous response's `nextCursor` |
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
  "data": {
    "merchantId": "MERCH-NIKE-ZA",
    "totalTransactions": 1842,
    "flaggedCount": 12,
    "notFlaggedCount": 1830,
    "fraudRate": 0.65,
    "highestRiskScore": 85,
    "uniqueCustomers": 534,
    "mostTriggeredRules": ["AmountThresholdRule", "VelocityRule"],
    "firstTransactionAt": "2024-01-01T00:00:00Z",
    "lastTransactionAt": "2026-07-23T09:00:00Z"
  }
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
  "data": {
    "from": "2026-07-01T00:00:00Z",
    "to": "2026-07-31T23:59:59Z",
    "totalAssessed": 48320,
    "totalFlagged": 241,
    "totalNotFlagged": 48079,
    "fraudRate": 0.50,
    "ruleBreakdown": [
      { "ruleName": "AmountThresholdRule", "count": 98,  "percentage": 40.66 },
      { "ruleName": "VelocityRule",        "count": 72,  "percentage": 29.88 },
      { "ruleName": "GeographicAnomalyRule", "count": 45, "percentage": 18.67 }
    ]
  }
}
```

> Replace `8081` with the port for the environment you started.

---

## Fraud Rules

| Rule | Trigger | Severity | Priority |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | Amount exceeds threshold (default R5,000, with configurable per-category overrides, e.g. RETAIL R15,000, GROCERY R3,000) | HIGH | 1 |
| `VELOCITY` | > 5 transactions in 10 minutes for same customer. Boosted to CRITICAL when the merchant category is high-risk (crypto, money-transfer, wire-transfer). | HIGH → CRITICAL | 2 |
| `DUPLICATE_TRANSACTION` | Same merchant + same amount + same currency within window: **120 s** for CARD_PRESENT / CONTACTLESS / ATM, **300 s** for CARD_NOT_PRESENT. | CRITICAL | 3 |
| `GEOGRAPHIC_ANOMALY` | Implied travel speed between two consecutive physical locations exceeds 900 km/h. Skipped when transactions are < 1 minute apart (clock-skew guard). Falls back to merchant registered location when the transaction carries no coordinates. | CRITICAL | 5 |
| `CARD_CLONING` | Same transaction amount charged to 2+ different merchants within 10 minutes, a hallmark of automated card testing with a cloned card. | MEDIUM | 6 |
| `TIME_OF_DAY_ANOMALY` | Transaction occurs in the off-hours window (default 23:00–05:00 UTC). | MEDIUM | 7 |
| `HIGH_RISK_MERCHANT_CATEGORY` | Merchant category is crypto/money-transfer/wire-transfer (HIGH) or gambling/casino/payday-loan (MEDIUM). | HIGH / MEDIUM | 8 |
| `DEVICE_FINGERPRINT` | Transaction arrives from a device fingerprint the customer has never used before (within the lookback window). Skipped when no fingerprint is supplied or the customer has no prior fingerprinted history. | HIGH | 9 |
| `MULTI_CHANNEL_ANOMALY` | A physical-channel transaction (CARD_PRESENT, CONTACTLESS, ATM) and an online transaction (CARD_NOT_PRESENT) occur within 5 minutes of each other for the same customer. | MEDIUM | 10 |
| `CROSS_MERCHANT_VELOCITY` | ≥ 10 total transactions across any merchants within 10 minutes, providing an additional MEDIUM data point before the HIGH velocity rule threshold is reached. | MEDIUM | 11 |
| `CUMULATIVE_SPENDING` | Rolling spend exceeds the hourly limit (default R10,000) or daily limit (default R25,000). Hourly is computed from in-context recent transactions; daily is a pre-aggregated DB query. | HIGH | 12 |
| `CUSTOMER_AMOUNT_ANOMALY` | Amount exceeds `stddev-multiplier` (default 3.0) standard deviations above this specific customer's own historical mean, computed over a 90-day lookback (default `min-history-count` 5 prior transactions required). | MEDIUM | 13 |

> Priority 4 (`BLACKLISTED_MERCHANT`) was removed entirely. See DESIGN.md §5 for why a strictly post-authorisation system gets limited value from a pure blacklist-match rule.

**Risk scoring:** Rules are combined with a log-odds (naive-Bayes) model rather than summed points. Each fired rule carries a calibrated likelihood ratio (how much more likely fraud is, given that rule fired, versus not), keyed by rule name **and** severity so rules whose severity varies at runtime (e.g. `VelocityRule`'s high-risk-category escalation) are calibrated per variant. The posterior fraud probability is the sigmoid of the prior log-odds plus the sum of each violation's log-likelihood-ratio; `riskScore` is that probability × 100 (0–100). The verdict is a **three-way disposition**, not a binary flag, driven by two thresholds: `fraud.scoring.fraud-probability-threshold` (default 0.5) and below it, `fraud.scoring.review-probability-threshold` (default 0.10).

| `disposition` | When |
|---|---|
| `FLAGGED` | probability ≥ `fraud-probability-threshold` |
| `PENDING_REVIEW` | probability ≥ `review-probability-threshold`, below `fraud-probability-threshold` |
| `CLEARED` | probability below `review-probability-threshold` |

This deliberately does **not** treat "one strong signal" and "several weak, possibly-correlated signals" as equivalent the way a flat point sum would. Some rules (`GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, boosted `VELOCITY`, `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, high-risk-category `HIGH_RISK_MERCHANT_CATEGORY`) are calibrated to be `FLAGGED` on their own; others (`AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, `CUSTOMER_AMOUNT_ANOMALY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`) are calibrated as weak evidence that needs a second, independent corroborating signal to cross the `FLAGGED` threshold. Two transactions that fired only a weak rule each land in `PENDING_REVIEW` instead of being silently treated the same as a clean transaction; that band is exactly what `GET /transactions/pending-review` (§ API Reference) surfaces for an analyst to work.

The likelihood ratios in `ScoringProperties` are domain-judgment starting points, not values derived from labelled outcome data. This system doesn't yet have a confirmed-fraud / false-positive feedback loop to calibrate against, so treat them as a reasoned first pass rather than ground truth.

---

## Kafka Topics

| Topic | Partitions | Direction | Message type |
|---|---|---|---|
| `transactions.raw` | 6 | Inbound (consumed) | `TransactionEvent` Protobuf |
| `transactions.raw-retry-0`, `transactions.raw-retry-1` | 6 | Internal (retry) | auto-created by `@RetryableTopic` |
| `transactions.raw.DLT` | 6 | Dead-letter | exhausted-retry events |
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

If the DB commit fails, the Kafka TX aborts: no message is published, and the consumer retries cleanly.
If the Kafka commit fails after the DB commit, the consumer retries; the idempotency guard (`findByIdOnly`) skips the re-save and re-publishes the event.

Consumer uses `isolation.level=read_committed` so downstream readers only see committed messages.

---

## Testing

### Unit tests

```bash
make test-unit
```

Each rule is tested in isolation with zero Spring context, fast and deterministic. Covers category-tiered thresholds, type-aware duplicate windows, geographic speed edge cases, merchant-location fallback, off-hours wrap-around, device fingerprint unknown/known paths, multi-channel switching, cross-merchant velocity boundaries, and hourly/daily spend limits.

### REST controller tests

`@WebMvcTest` slices: Spring MVC wiring with Mockito-backed service/mapper dependencies. No database or Kafka required. Each controller class has its own test class:

| Test class | Controller | Tests |
|---|---|---|
| `TransactionQueryControllerTest` | `TransactionQueryController` | 38 |
| `MerchantControllerTest` | `MerchantController` | 13 |
| `StatsControllerTest` | `StatsController` | 4 |
| `CustomerControllerTest` | `CustomerController` | 4 |
| `RuleControllerTest` | `RuleController` | 3 |
| `TransactionOutcomeControllerTest` | `TransactionOutcomeController` | 6 |
| `StandaloneTransactionControllerTest` | Standalone/local profile endpoints | 17 |

Coverage per controller:

**`GET /transactions`**: paginated results, cursor passthrough, date range parsing, `pageSize` min/max validation, malformed timestamp → 400

**`GET /transactions/{id}`**: found → 200 with DTO, not found → 404, invalid UUID → 400

**`GET /transactions/{id}/assessment`**: found → 200, not found → 404, invalid UUID → 400

**`GET /transactions/flagged`**: no filters, per-filter isolation (customerId, ruleViolated, minRiskScore, maxRiskScore, date range), combined risk score band, `pageSize` validation

**`GET /transactions/pending-review`**: no filters, customerId passthrough, combined risk score band, date range, `pageSize`/sort validation

**`GET /transactions/passed`**: no filters, cursor passthrough, customerId + date range, `minRiskScore` filter

**`GET /merchants/{id}/flagged`**: no filters, date range, ruleViolated, minRiskScore, combined ruleViolated + minRiskScore, next-cursor set when `hasMore=true`, `pageSize` validation, malformed date → 400

**`GET /merchants/{id}/risk-summary`**: no since, with since (verifies Instant passed to service), malformed since → 400

**`GET /customers/{id}/risk-summary`**: full response shape, with since, malformed since → 400, zeroed summary

**`GET /stats/fraud-summary`**: no date range, with date range (verifies Instant passthrough), malformed from → 400, empty breakdown

**`GET /rules`**: returns all rules, empty list, disabled rule included, `config` map populated

**`PATCH /transactions/{id}/outcome`**: 200 on first resolution, 409 on a second attempt, 404 for an unknown transaction, 400 for a missing/invalid `outcome` value

### Integration tests (Testcontainers)

```bash
make test-integration
```

Requires the `confluent` Maven profile (`make test-integration` already passes it): this test
produces real Confluent Protobuf `TransactionEvent` messages, and `KafkaProtobufDeserializer`
is only on the classpath under that opt-in profile (see the "Running Locally" prerequisites
above for why it's opt-in). Running `mvn test -Dtest="**/integration/**"` directly without
`-Pconfluent` fails with `ClassNotFoundException`, not a real bug. Also needs a real Docker
daemon reachable from Maven. On Windows with a non-Docker-Desktop engine (e.g. Rancher Desktop
on Docker Engine 29+), Testcontainers 1.x may fail with "Could not find a valid Docker
environment" / "client version 1.32 is too old" unless `src/test/resources/docker-java.properties`
pins a compatible API version (already committed).

Spins up real PostgreSQL and Kafka containers. Tests the full pipeline end-to-end:

- Transaction published to `transactions.raw` → consumed → rule engine → assessment persisted
- Clean transaction published to `transactions.passed`
- High-risk-category transaction (`WIRE_TRANSFER`) published to `transactions.flagged`, not a
  high amount alone, which no longer flags by itself under the log-odds scoring model (see
  "Risk scoring" above); `HIGH_RISK_MERCHANT_CATEGORY` is calibrated as standalone-sufficient
- Query API: customer transactions returning `PENDING` and `ASSESSED` statuses
- 404 on assessment for unknown transaction ID

Uses `mock://` Confluent Schema Registry (in-process) so no live registry is needed for tests.

### Run all tests

```bash
make test
```

---

## Load & Performance Tests

Load tests run exclusively against the `load-test` environment, which includes InfluxDB and Grafana for live metrics.

### 1. Start the load-test environment

```bash
make load-test
```

### 2. Open the live dashboard

```bash
make grafana
# or open http://localhost:3000 manually
```

The k6 dashboard is pre-provisioned, no login or setup required.

### 3. Run a scenario

```bash
make k6-run                          # 01-baseline (default)
make k6-run SCENARIO=02-ramp
make k6-run SCENARIO=03-spike
make k6-run SCENARIO=04-fraud-rules
make k6-run SCENARIO=01-baseline RATE=500   # override target concurrent load
```

### 4. Run all scenarios sequentially

```bash
make k6-run-all
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

Startup validation: every rule window measured against `recentCustomerTransactions` (currently `velocity`, `geographic`, `card-cloning`, `device-fingerprint`, `multi-channel`, `cross-merchant-velocity`, and `cumulative-spending`'s hourly window) is checked against `context-lookback-minutes` in one exhaustive map (`RuleProperties.validate()`). If any exceeds it, the app fails to start with an `IllegalStateException` rather than silently under-counting.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `SCHEMA_REGISTRY_URL` | `http://schema-registry:8081` | Confluent Schema Registry |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `frauddb` | PostgreSQL connection |
| `DB_USER` / `DB_PASSWORD` | `fraud` / `fraud` | PostgreSQL credentials |
| `VAULT_HOST` / `VAULT_TOKEN` | `vault` / `dev-root-token` | HashiCorp Vault |
| `FRAUD_IDP_URI` | `https://idp.acmebank.example/oauth2/default` | JWT issuer; JWKS fetched from `{issuer}/.well-known/openid-configuration` at startup |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4317` | OTel GRPC endpoint (Instana agent in K8s, unset locally, spans dropped gracefully) |

---

## Security

### Profile-based behaviour

Security is profile-gated so local development and tests require no credentials.

| Profile | Behaviour |
|---|---|
| `local`, `standalone` | All requests permitted. No IDP contact. |
| `test` | All requests permitted. `@WebMvcTest` tests pass without auth headers. |
| `load-test`, `prod` | JWT bearer token required on `/api/v1/**`. |

> Note: the `dev` **environment** (`make dev`, `docker-compose.dev.yml`) activates the `local` Spring **profile**, not a profile named `dev`, so it falls in the open bucket above, with no auth required. The `load-test`/`prod` environments each activate their own like-named profile.

### Authentication

The API uses OAuth2 JWT bearer tokens issued by Acme Bank's IDP. Include the token as a standard `Authorization` header:

```
Authorization: Bearer <jwt>
```

The IDP base URI is read from `FRAUD_IDP_URI`. At startup the app fetches the JWKS from
`{idpBaseUri}/.well-known/openid-configuration` and caches the public keys for signature
validation. If the IDP is unreachable at startup, the app will fail to start; this is
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
| `fraud.assessments.total{verdict="FLAGGED"}` | Counter | Flagged assessments since startup |
| `fraud.assessments.total{verdict="PENDING_REVIEW"}` | Counter | Pending-review assessments since startup |
| `fraud.assessments.total{verdict="CLEARED"}` | Counter | Cleared assessments since startup |
| `fraud.dlt.total` | Counter | Transactions that exhausted all retries and reached the dead-letter topic |
| `fraud.rule.evaluation.duration.seconds` | Timer | Full rule engine evaluation time (p50/p95/p99) |

Consumer lag per partition is automatically exposed via `kafka_consumer_fetch_manager_records_lag` from the Micrometer + Spring Kafka auto-instrumentation.

### Distributed tracing

All Kafka listener invocations and HTTP requests are traced via the Micrometer OTel bridge and exported via OTLP gRPC to `${MANAGEMENT_OTLP_TRACING_ENDPOINT}`. Sampling probability is 10% by default.

**In Kubernetes** the Instana agent runs as a DaemonSet. Each pod's Helm values file injects `HOST_IP` (the node IP) and sets `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://$(HOST_IP):4317`, pointing the OTLP exporter at the local agent. Traces are visible in the Instana UI with full service dependency maps.

**Locally** `MANAGEMENT_OTLP_TRACING_ENDPOINT` is not set. The app defaults to `http://localhost:4317`, finds nothing, and drops spans silently. All other functionality is unaffected.

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

Flyway manages schema changes — a single consolidated `V1` migration, since this service hasn't gone live yet and there's no deployed history to preserve against. `spring.jpa.hibernate.ddl-auto=validate` means the app will fail to start if the entity model diverges from the schema.

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
│   │   │   ├── TransactionOutcomeController.java # PATCH /transactions/{id}/outcome — the one write endpoint
│   │   │   ├── RuleController.java              # /api/v1/rules
│   │   │   ├── CustomerController.java          # /api/v1/customers/{id}/risk-summary
│   │   │   ├── MerchantController.java          # /api/v1/merchants/{id}/flagged + risk-summary
│   │   │   ├── StatsController.java             # /api/v1/stats/fraud-summary
│   │   │   └── StandaloneTransactionController.java # /standalone/submit + /stream — local/standalone only
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
│   ├── config/              # KafkaConfig, CacheConfig, RuleProperties, ScoringProperties,
│   │                        # FraudMetrics, SchedulingConfig, DataSourceConfig +
│   │                        # ReplicationRoutingDataSource (reader/writer routing)
│   ├── consumer/           # TransactionConsumer (@KafkaListener + @DltHandler)
│   ├── engine/
│   │   ├── FraudRule.java                  # Strategy interface (evaluate, getRuleName, getConfig, …)
│   │   ├── RuleEngine.java                 # Orchestrates evaluation + risk scoring
│   │   ├── EvaluationContext.java          # Carries pre-fetched context (transactions,
│   │   │                                   # merchant location, daily spend total)
│   │   ├── EvaluationContextBuilder.java   # All DB queries run here before rule evaluation
│   │   ├── ReferenceDataCache.java         # Caffeine-cached merchant location reads
│   │   │                                   # (separate bean so @Cacheable isn't bypassed by self-invocation)
│   │   └── rules/
│   │       ├── AmountThresholdRule.java    # priority 1  — category-tiered thresholds
│   │       ├── VelocityRule.java           # priority 2  — with high-risk category boost
│   │       ├── DuplicateTransactionRule.java  # priority 3
│   │       │                                  # (priority 4, BlacklistedMerchantRule, removed entirely — see DESIGN.md §5)
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
│   ├── kafka/              # AssessmentProducer — publishes to flagged/pending-review/passed
│   ├── model/              # Transaction (+ deviceFingerprint), FraudAssessment, RuleViolation,
│   │                       # MerchantLocation + enums
│   ├── proto/              # ProtoMapper (Protobuf ↔ domain model conversion)
│   ├── repository/
│   │   ├── TransactionRepository.java       # JPQL queries — customer/merchant history, counts,
│   │   │                                    # timestamps, duplicate candidates
│   │   ├── FraudAssessmentRepository.java   # JPQL queries — flagged/passed feeds, merchant feed,
│   │   │                                    # aggregate counts and top-rule GROUP BY
│   │   └── MerchantLocationRepository.java
│   ├── service/
│   │   ├── TransactionQueryService.java     # All read operations for the API layer, routed
│   │   │                                    # through the reader DataSource
│   │   ├── AssessmentOutcomeService.java    # PATCH .../outcome — one-time disposition write
│   │   ├── StandaloneTransactionProcessor.java # Per-item @Transactional persist→evaluate→
│   │   │                                    # save→publish, shared by submit() and stream()
│   │   ├── RuleManagementService.java
│   │   └── PartitionMaintenanceJob.java
│   └── streams/            # Kafka Streams topology (com.fraudengine.streams) — second,
│                            # independent consumer group on transactions.raw, maintains a
│                            # changelog-backed per-customer state store (RocksDB) serving
│                            # recent-transaction history, daily spend, and the customer
│                            # amount-anomaly baseline via Interactive Queries, instead of a
│                            # live Postgres query per evaluation. Falls back to Postgres if
│                            # the store isn't RUNNING yet — see DESIGN.md §5.
├── main/proto/
│   ├── transaction_event.proto           # TransactionEvent + TransactionType enum
│   ├── cleared_transaction_event.proto   # ClearedTransactionEvent
│   ├── fraudulent_transaction_event.proto # FraudulentTransactionEvent
│   └── pending_review_transaction_event.proto # PendingReviewTransactionEvent
├── main/resources/
│   ├── application.yml
│   └── db/migration/
│       └── V1__init_schema.sql   # Consolidated — no deployed history to preserve pre-launch
└── test/java/com/fraudengine/
    ├── api/controller/
    │   ├── TransactionQueryControllerTest.java  # 38 tests
    │   ├── MerchantControllerTest.java          # 13 tests
    │   ├── CustomerControllerTest.java          # 4 tests
    │   ├── StatsControllerTest.java             # 4 tests
    │   ├── RuleControllerTest.java              # 3 tests
    │   ├── TransactionOutcomeControllerTest.java # 6 tests
    │   └── StandaloneTransactionControllerTest.java # 17 tests
    ├── engine/
    │   ├── RuleEngineTest.java
    │   └── rules/              # Unit tests — one per rule (12 rule test classes)
    ├── kafka/                  # AssessmentProducerTest (Mockito)
    ├── service/                # AssessmentOutcomeServiceTest, TransactionQueryServiceTest,
    │                           # PartitionMaintenanceJobTest
    ├── config/                 # KafkaConfigTest (retry/DLT partition-count cross-check),
    │                           # ReplicationRoutingDataSourceTest
    ├── streams/                # CustomerActivityProcessorTest (TopologyTestDriver, no broker)
    └── integration/            # TransactionIntegrationTest (Testcontainers + mock Schema Registry,
                                 # requires -Pconfluent — see "Integration tests" above)

load-tests/
├── config.js               # Shared BASE_URL, thresholds, data pools
├── scenarios/              # One file per k6 scenario
├── lib/reporter.js         # HTML summary generation
├── results/                # Generated HTML reports (gitignored)
└── grafana/
    ├── provisioning/       # Auto-configured datasource + dashboard provider
    └── dashboards/         # Pre-built k6 Grafana dashboard
```
