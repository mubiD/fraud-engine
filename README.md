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

### Prerequisites
- Docker & Docker Compose
- Java 21+
- Maven 3.9+

### Start with Docker Compose

```bash
docker compose up --build
```

The service starts on `http://localhost:8080`. All infrastructure (Kafka, PostgreSQL) is included.

### Run without Docker (development)

```bash
# Start infrastructure only
docker compose up postgres kafka -d

# Run application
./mvnw spring-boot:run
```

---

## API Reference

### Submit a Transaction

```bash
curl -X POST http://localhost:8080/api/v1/transactions \
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
curl http://localhost:8080/api/v1/transactions/{transactionId}/assessment
```

### List Fraud Flags (with cursor pagination)

```bash
# All fraud flags
curl "http://localhost:8080/api/v1/fraud-flags?pageSize=20"

# Filter by customer
curl "http://localhost:8080/api/v1/fraud-flags?customerId=CUST_001"

# Filter by rule that triggered
curl "http://localhost:8080/api/v1/fraud-flags?ruleViolated=AMOUNT_THRESHOLD"

# Filter by minimum risk score
curl "http://localhost:8080/api/v1/fraud-flags?minRiskScore=75"

# Paginate using cursor from previous response
curl "http://localhost:8080/api/v1/fraud-flags?cursor=2026-06-15T10:00:00Z"
```

### List Rules

```bash
curl http://localhost:8080/api/v1/rules
```

### Update a Rule (toggle/reconfigure at runtime)

```bash
# Raise the amount threshold
curl -X PATCH http://localhost:8080/api/v1/rules/AMOUNT_THRESHOLD \
  -H "Content-Type: application/json" \
  -d '{"threshold": 10000.00}'

# Disable a rule
curl -X PATCH http://localhost:8080/api/v1/rules/VELOCITY \
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
./mvnw test -pl . -Dtest="**/engine/**,**/service/**"
```

Tests each rule in isolation with zero Spring context. Fast and deterministic.

### Integration Tests (Testcontainers)

```bash
./mvnw test -Dtest="**/integration/**"
```

Spins up real PostgreSQL and Kafka containers. Tests the full pipeline end-to-end:
- Transaction submitted → Kafka consumed → rule engine evaluated → assessment persisted → API returns result
- Blacklisted merchant detection
- Validation error handling
- DLT routing on processing failure

### Load & Performance Tests (k6)

k6 runs as a separate standalone suite in `load-tests/`. See [`load-tests/README.md`](./load-tests/README.md) for full details.

```bash
# Install k6 (macOS)
brew install k6

# Ensure the application is running
docker compose up -d

cd load-tests

# Run a single scenario
k6 run scenarios/01-baseline.js

# Run all scenarios
./run-all.sh
```

**Scenarios:**
1. **Baseline** (`01-baseline.js`) — 100 VUs, steady state, 2 min
2. **Ramp** (`02-ramp.js`) — 10 → 500 VUs over 5 min, finds degradation point
3. **Spike** (`03-spike.js`) — sudden 10x burst, validates Kafka absorption
4. **Fraud Rules Mix** (`04-fraud-rules.js`) — realistic 60/20/20 traffic mix + concurrent read path

**Thresholds (fail if breached):** p95 < 1500ms · p99 < 2000ms · error rate < 5%

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
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
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
│   ├── kafka/                   # TransactionEvent, TransactionProducer
│   ├── model/                   # JPA entities
│   ├── repository/              # Spring Data repositories
│   └── service/                 # TransactionService, FraudFlagService, RuleManagementService
├── main/resources/
│   ├── application.yml
│   └── db/migration/            # Flyway SQL migrations
├── test/java/com/fraudengine/
│   ├── engine/rules/            # Unit tests — one per rule
│   ├── engine/                  # RuleEngineTest (Mockito)
│   └── integration/             # TransactionIntegrationTest (Testcontainers)
└── load-tests/                  # k6 scenarios (see load-tests/)
```
