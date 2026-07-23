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
  Rule Engine (Strategy Pattern)                         (@DltHandler → status=FAILED)
  ├── AmountThresholdRule    priority 1   HIGH
  ├── VelocityRule           priority 2   HIGH
  ├── DuplicateTransactionRule priority 3  CRITICAL   (type-aware window)
  ├── BlacklistedMerchantRule  priority 4  CRITICAL   (Caffeine-cached)
  └── GeographicAnomalyRule    priority 5  CRITICAL   (speed + clock-skew guard)
      │
      ▼
  FraudAssessment → PostgreSQL (Flyway-managed, daily-partitioned)
      │
      ├── isFraudulent=true  → FraudulentTransactionEvent (Protobuf)
      │                              ▼
      │                       transactions.flagged
      │
      └── isFraudulent=false → ClearedTransactionEvent (Protobuf)
                                     ▼
                              transactions.passed

Query API (read-only)
  GET /api/v1/transactions?customerId=
  GET /api/v1/transactions/{id}/assessment
  GET /api/v1/transactions/flagged
  GET /api/v1/transactions/passed
  GET /api/v1/rules
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

> There is no HTTP submission endpoint. Transactions enter the system exclusively via `transactions.raw` Kafka topic. The API is read-only.

### List transactions for a customer

```bash
curl "http://localhost:8081/api/v1/transactions?customerId=CUST_001&pageSize=20"
```

Response `200 OK`:
```json
{
  "data": [
    {
      "transactionId": "550e8400-e29b-41d4-a716-446655440000",
      "customerId": "CUST_001",
      "merchantId": "MERCHANT_ABC",
      "amount": "6500.00",
      "currency": "GBP",
      "transactionType": "CARD_PRESENT",
      "status": "ASSESSED",
      "timestamp": "2026-06-15T10:00:00Z",
      "assessment": {
        "fraudulent": true,
        "riskScore": 50,
        "violations": [{ "ruleName": "AMOUNT_THRESHOLD", "severity": "HIGH" }]
      }
    }
  ],
  "nextCursor": "2026-06-15T09:59:00Z"
}
```

### Get full assessment for a transaction

```bash
curl http://localhost:8081/api/v1/transactions/{transactionId}/assessment
```

Returns `200 OK` with `FraudAssessmentDto`, or `404` if the transaction does not exist.

### List fraudulent assessments

```bash
# All flagged transactions
curl "http://localhost:8081/api/v1/transactions/flagged?pageSize=20"

# Filter by rule that triggered
curl "http://localhost:8081/api/v1/transactions/flagged?ruleViolated=AMOUNT_THRESHOLD"

# Filter by minimum risk score
curl "http://localhost:8081/api/v1/transactions/flagged?minRiskScore=75"

# Paginate using cursor from previous response
curl "http://localhost:8081/api/v1/transactions/flagged?cursor=2026-06-15T10:00:00Z"
```

### List cleared assessments

```bash
curl "http://localhost:8081/api/v1/transactions/passed?pageSize=20"
```

### List registered rules

```bash
curl http://localhost:8081/api/v1/rules
```

Returns each rule's name, version, enabled status, and priority. Rule configuration changes require redeployment — there is no runtime PATCH endpoint.

> Replace `8081` with the port for the environment you started.

---

## Fraud Rules

| Rule | Trigger | Severity | Priority |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | Amount ≥ threshold (default £5,000) | HIGH | 1 |
| `VELOCITY` | > N transactions in M minutes for same customer (default: 5 in 10 min) | HIGH | 2 |
| `DUPLICATE_TRANSACTION` | Same merchant + same amount within window — **30 s** for CARD_PRESENT / CONTACTLESS / ATM, **300 s** for CARD_NOT_PRESENT. Currency-agnostic by design to catch currency-hopping fraud. | CRITICAL | 3 |
| `BLACKLISTED_MERCHANT` | Merchant ID on the blacklist (Caffeine-cached, 5-min TTL) | CRITICAL | 4 |
| `GEOGRAPHIC_ANOMALY` | Implied travel speed between two consecutive locations exceeds 900 km/h. Skipped when transactions are < 1 minute apart (clock-skew guard). | CRITICAL | 5 |

**Risk scoring:** Each violation contributes a weighted score (LOW=10, MEDIUM=25, HIGH=50, CRITICAL=100), capped at 100. A transaction is marked fraudulent when `riskScore ≥ 50`.

---

## Kafka Topics

| Topic | Partitions | Direction | Message type |
|---|---|---|---|
| `transactions.raw` | 6 | Inbound (consumed) | `TransactionEvent` Protobuf |
| `transactions.raw-0`, `transactions.raw-1` | 6 | Internal (retry) | auto-created by `@RetryableTopic` |
| `transactions.raw.DLT` | 1 | Dead-letter | exhausted-retry events |
| `transactions.flagged` | 3 | Outbound (produced) | `FraudulentTransactionEvent` Protobuf |
| `transactions.passed` | 3 | Outbound (produced) | `ClearedTransactionEvent` Protobuf |

All topics use replication factor 2 across the 3-broker cluster. Schemas are registered with and enforced by Confluent Schema Registry.

---

## Exactly-Once Semantics

The DB write and Kafka publish are atomic via `ChainedKafkaTransactionManager`:

1. Kafka TX opens
2. DB TX opens
3. `FraudAssessment` + updated `TransactionStatus` written to Postgres
4. Outcome event published to `transactions.flagged` or `transactions.passed`
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

Each rule is tested in isolation with zero Spring context — fast and deterministic. Includes type-aware window tests (CP vs CNP), geographic speed edge cases, and currency-agnostic duplicate detection.

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
    velocity:
      enabled: true
      max-transactions: 5
      window-minutes: 10
    duplicate:
      enabled: true
      card-present-window-seconds: 30
      card-not-present-window-seconds: 300
    blacklisted-merchant:
      enabled: true
    geographic:
      enabled: true
      window-minutes: 60
      max-travel-speed-kmh: 900.0
```

Startup validation: if `velocity.window-minutes` or `geographic.window-minutes` exceeds `context-lookback-minutes`, the app fails to start with an `IllegalStateException`.

### Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker list |
| `SCHEMA_REGISTRY_URL` | `http://schema-registry:8081` | Confluent Schema Registry |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `frauddb` | PostgreSQL connection |
| `DB_USER` / `DB_PASSWORD` | `fraud` / `fraud` | PostgreSQL credentials |
| `VAULT_HOST` / `VAULT_TOKEN` | `vault` / `dev-root-token` | HashiCorp Vault |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | `http://localhost:4317` | OTel GRPC endpoint (Instana agent in K8s, unset locally — spans dropped gracefully) |

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

Flyway manages all schema changes. `spring.jpa.hibernate.ddl-auto=validate` means the app will fail to start if the entity model diverges from the schema.

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
│   │   ├── controller/     # TransactionQueryController, RuleController
│   │   ├── dto/            # TransactionSummaryDto, FraudAssessmentDto, PagedResponse
│   │   └── mapper/         # MapStruct mappers
│   ├── config/             # KafkaConfig, CacheConfig, RuleProperties, FraudMetrics, SchedulingConfig
│   ├── consumer/           # TransactionConsumer (@KafkaListener + @DltHandler)
│   ├── engine/
│   │   ├── FraudRule.java              # Strategy interface
│   │   ├── RuleEngine.java             # Orchestrates evaluation + risk scoring
│   │   ├── EvaluationContext.java
│   │   ├── EvaluationContextBuilder.java
│   │   └── rules/                      # AmountThresholdRule, VelocityRule, DuplicateTransactionRule,
│   │                                   # BlacklistedMerchantRule, GeographicAnomalyRule
│   ├── exception/          # GlobalExceptionHandler
│   ├── filter/             # MdcLoggingFilter
│   ├── kafka/              # AssessmentProducer, TransactionEvent (POJO), event POJO classes
│   ├── model/              # Transaction, FraudAssessment, RuleViolation, BlacklistedMerchant + enums
│   ├── proto/              # ProtoMapper (Protobuf ↔ domain model conversion)
│   ├── repository/         # Spring Data JPA repositories
│   └── service/            # TransactionQueryService, PartitionMaintenanceJob, RuleManagementService
├── main/proto/
│   ├── transaction_event.proto           # TransactionEvent + TransactionType enum
│   ├── cleared_transaction_event.proto   # ClearedTransactionEvent
│   └── fraudulent_transaction_event.proto # FraudulentTransactionEvent
├── main/resources/
│   ├── application.yml
│   └── db/migration/       # V1–V4 Flyway migrations (schema, blacklist, transaction type, partitioning)
└── test/java/com/fraudengine/
    ├── engine/rules/       # Unit tests — one per rule
    ├── kafka/              # AssessmentProducerTest (Mockito)
    └── integration/        # TransactionIntegrationTest (Testcontainers + mock Schema Registry)

load-tests/
├── config.js               # Shared BASE_URL, thresholds, data pools
├── scenarios/              # One file per k6 scenario
├── lib/reporter.js         # HTML summary generation
├── results/                # Generated HTML reports (gitignored)
└── grafana/
    ├── provisioning/       # Auto-configured datasource + dashboard provider
    └── dashboards/         # Pre-built k6 Grafana dashboard
```
