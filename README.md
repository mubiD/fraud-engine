# Fraud Rule Engine

A production-grade backend service that processes categorised transaction events, evaluates them against a configurable set of fraud detection rules, and exposes results via a REST API.

**Stack:** Java 21 · Spring Boot 3.3 · Apache Kafka (KRaft) · PostgreSQL 16 · Docker · JUnit 5 · Mockito · Testcontainers · k6

---

## Architecture

See [DESIGN.md](./DESIGN.md) for the full system design document covering all architectural decisions, trade-offs, and extensibility considerations.

```
POST /api/v1/transactions
        │
        ▼
  Kafka Producer ──► transactions.raw (partitioned by customerId)
                              │
                              ▼
                    Kafka Consumer (Spring)
                              │
                              ▼
                    Rule Engine (Strategy Pattern)
                    ├── AmountThresholdRule   (priority 1)
                    ├── VelocityRule          (priority 2)
                    ├── DuplicateRule         (priority 3)
                    ├── BlacklistedMerchant   (priority 4)
                    └── GeographicAnomaly     (priority 5)
                              │
                              ▼
                    FraudAssessment → PostgreSQL
                              │
                              ▼
              GET /api/v1/fraud-flags (cursor-based pagination)
```

---

## Running Locally

The only prerequisite is **Docker**. No Java, Maven, or Kafka installation required — everything runs inside containers.

Each environment is fully self-contained: its own app instance, Postgres database, and Kafka broker, all on separate ports so multiple environments can run simultaneously.

| Environment | App | Postgres | Kafka (host) |
|---|---|---|---|
| `dev` | 8081 | 5433 | 9192 |
| `int` | 8082 | 5434 | 9292 |
| `qa` | 8083 | 5435 | 9392 |
| `load` | 8084 | 5436 | 9492 |
| `prod` | 8085 | 5437 | 9592 |

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
3. Starts Kafka and waits until healthy
4. Starts the fraud-engine (Flyway runs migrations on boot)
5. Polls `/actuator/health` until the app is ready

Postgres data volumes are named per environment and persist across restarts.

### Tear down

```bash
make stop ENV=int
```

### Tail logs

```bash
make logs ENV=int
```

### See all running environments

```bash
make ps
```

---

## API Reference

### Submit a Transaction

```bash
curl -X POST http://localhost:8082/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST_001",
    "merchantId": "MERCHANT_ABC",
    "amount": 6500.00,
    "currency": "GBP",
    "category": "RETAIL",
    "location": "London, UK",
    "latitude": 51.5074,
    "longitude": -0.1278
  }'
```

Response `202 Accepted`:
```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "message": "Transaction accepted for fraud evaluation"
}
```

### Get Assessment for a Transaction

```bash
curl http://localhost:8082/api/v1/transactions/{transactionId}/assessment
```

### List Fraud Flags (with cursor pagination)

```bash
# All fraud flags
curl "http://localhost:8082/api/v1/fraud-flags?pageSize=20"

# Filter by customer
curl "http://localhost:8082/api/v1/fraud-flags?customerId=CUST_001"

# Filter by rule that triggered
curl "http://localhost:8082/api/v1/fraud-flags?ruleViolated=AMOUNT_THRESHOLD"

# Filter by minimum risk score
curl "http://localhost:8082/api/v1/fraud-flags?minRiskScore=75"

# Paginate using cursor from previous response
curl "http://localhost:8082/api/v1/fraud-flags?cursor=2026-06-15T10:00:00Z"
```

> Replace `8082` with the port for the environment you started.

### List Rules

```bash
curl http://localhost:8082/api/v1/rules
```

### Update a Rule (toggle/reconfigure at runtime)

```bash
# Raise the amount threshold
curl -X PATCH http://localhost:8082/api/v1/rules/AMOUNT_THRESHOLD \
  -H "Content-Type: application/json" \
  -d '{"threshold": 10000.00}'

# Disable a rule
curl -X PATCH http://localhost:8082/api/v1/rules/VELOCITY \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

---

## Fraud Rules

| Rule | Trigger | Severity | Priority |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | Amount > £5,000 (configurable) | HIGH | 1 |
| `VELOCITY` | > 5 transactions in 10 min (configurable) | HIGH | 2 |
| `DUPLICATE_TRANSACTION` | Same amount + merchant within 5 min | CRITICAL | 3 |
| `BLACKLISTED_MERCHANT` | Merchant on blacklist | CRITICAL | 4 |
| `GEOGRAPHIC_ANOMALY` | Physically impossible travel between locations | CRITICAL | 5 |

**Risk Scoring:** Each violation contributes a weighted score (LOW=10, MEDIUM=25, HIGH=50, CRITICAL=100), capped at 100. Transactions with score ≥ 50 are marked fraudulent.

---

## Testing

### Unit Tests

```bash
make test-unit
```

Tests each rule in isolation with zero Spring context. Fast and deterministic.

### Integration Tests (Testcontainers)

```bash
make test-integration
```

Spins up real PostgreSQL and Kafka containers via Testcontainers — no running environment needed. Tests the full pipeline end-to-end:
- Transaction submitted → Kafka consumed → rule engine evaluated → assessment persisted → API returns result
- Blacklisted merchant detection
- Validation error handling
- DLT routing on processing failure

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

This starts the fraud-engine, Kafka, Postgres, InfluxDB, and Grafana.

### 2. Open the live dashboard

```bash
make grafana
# or open http://localhost:3000 manually
```

The k6 dashboard is pre-provisioned — no login or setup required. Open it before starting a test so you can watch metrics stream in live.

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
| `01-baseline` | Steady-state throughput + assessment poll | 100 VUs, 2 min |
| `02-ramp` | Find degradation point under increasing load | 10 → 500 VUs, 5 min |
| `03-spike` | Validate Kafka absorbs a sudden burst | 50 → 500 → 50 VUs, ~4 min |
| `04-fraud-rules` | Mixed write + read path (60/20/20 traffic split) | 100 VUs, 3 min |

### Configuring load

Edit the relevant file in `load-tests/scenarios/`. The key knobs are:

```js
// 01-baseline.js — change VUs or duration
vus: 100,
duration: '2m',

// 02-ramp.js — change ramp stages
stages: [
  { duration: '1m', target: 100 },
  { duration: '2m', target: 300 },
  { duration: '2m', target: 500 },
],
```

Pass/fail thresholds are in `load-tests/config.js`:

```js
http_req_duration: ['p(95)<1500', 'p(99)<2000'],
http_req_failed:   ['rate<0.05'],
```

### HTML reports

At the end of every run, a self-contained HTML report is written to `load-tests/results/`:

| Scenario | Report |
|---|---|
| `01-baseline` | `load-tests/results/01-baseline.html` |
| `02-ramp` | `load-tests/results/02-ramp.html` |
| `03-spike` | `load-tests/results/03-spike.html` |
| `04-fraud-rules` | `load-tests/results/04-fraud-rules.html` |

Open any report in a browser. To export as PDF: **File → Print → Save as PDF**.

Reports include threshold results, request rate, VU count over time, p50/p90/p95/p99 latency, error rate, and all custom metrics for that scenario.

---

## Configuration

All rule thresholds are configurable via `application.yml` or environment variables:

```yaml
fraud:
  rules:
    amount-threshold:
      enabled: true
      threshold: 5000.00
    velocity:
      enabled: true
      max-transactions: 5
      window-minutes: 10
    duplicate:
      enabled: true
      window-seconds: 300
    blacklisted-merchant:
      enabled: true
    geographic:
      enabled: true
      window-minutes: 60
```

Rules can also be toggled at runtime via `PATCH /api/v1/rules/{ruleName}` without redeployment.

---

## Health & Observability

```bash
curl http://localhost:8082/actuator/health
curl http://localhost:8082/actuator/metrics
```

### Structured logging & MDC correlation

Every log line carries a consistent set of context fields so any transaction can be traced end-to-end across the HTTP layer, Kafka, and the rule engine — without needing a tracing agent.

| MDC field | Set by | Value |
|---|---|---|
| `requestId` | `MdcLoggingFilter` | Random UUID per HTTP request |
| `httpMethod` | `MdcLoggingFilter` | `POST`, `GET`, etc. |
| `httpPath` | `MdcLoggingFilter` | Request URI |
| `transactionId` | `TransactionService` / `TransactionConsumer` | Transaction UUID |
| `customerId` | `TransactionService` / `TransactionConsumer` | Customer identifier |
| `merchantId` | `TransactionService` / `TransactionConsumer` | Merchant identifier |
| `kafkaTopic` | `TransactionConsumer` | Topic the event was consumed from |
| `kafkaPartition` | `TransactionConsumer` | Partition number |
| `kafkaOffset` | `TransactionConsumer` | Message offset |

Log format (configured in `application.yml`):
```
2026-06-15 12:00:00.123  INFO [requestId] [txn=<uuid>] [cust=CUST_001] [merchant=MERCH_ABC] [transactions.raw:42] TransactionConsumer : ...
```

Fields not populated in the current context print as `-` so column alignment is preserved.

Rule changes made via `PATCH /api/v1/rules/{ruleName}` are logged at `WARN` with before/after values, providing a traceable audit trail in the log stream.

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
│   │   ├── controller/          # TransactionController, FraudFlagController, RuleController
│   │   ├── dto/                 # Request/Response DTOs
│   │   └── mapper/              # MapStruct mappers
│   ├── config/                  # KafkaConfig, CacheConfig, RuleProperties
│   ├── consumer/                # TransactionConsumer (Kafka listener + DLT handler)
│   ├── engine/
│   │   ├── FraudRule.java       # Strategy interface
│   │   ├── RuleEngine.java      # Orchestrates evaluation
│   │   ├── EvaluationContext.java
│   │   ├── EvaluationContextBuilder.java
│   │   └── rules/               # One class per rule
│   ├── exception/               # GlobalExceptionHandler
│   ├── filter/                  # MdcLoggingFilter (MDC correlation per request)
│   ├── kafka/                   # TransactionEvent, TransactionProducer
│   ├── model/                   # JPA entities
│   ├── repository/              # Spring Data repositories
│   └── service/                 # TransactionService, FraudFlagService, RuleManagementService
├── main/resources/
│   ├── application.yml
│   └── db/migration/            # Flyway SQL migrations
└── test/java/com/fraudengine/
    ├── engine/rules/            # Unit tests — one per rule
    ├── engine/                  # RuleEngineTest (Mockito)
    └── integration/             # TransactionIntegrationTest (Testcontainers)

load-tests/
├── config.js                    # Shared BASE_URL, thresholds, data pools
├── scenarios/                   # One file per k6 scenario
├── lib/reporter.js              # k6-reporter bundle (HTML summary generation)
├── results/                     # Generated HTML reports (gitignored)
└── grafana/
    ├── provisioning/            # Auto-configured datasource + dashboard provider
    └── dashboards/              # Pre-built k6 Grafana dashboard
```
