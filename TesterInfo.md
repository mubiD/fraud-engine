# TesterInfo — Fraud Rule Engine

**Audience:** QA/Tester preparing a test plan, test cases, and test scenarios.

---

## 1. What This Service Is

This is a **real-time transaction fraud detection engine** for Acme Bank. It evaluates financial transactions against a chain of five rule-based fraud detectors, persists the results, and routes outcomes downstream. The service is built on Spring Boot 3.3 / Java 21.

---

## 2. How Transactions Enter the System

There are **two entry paths**, depending on environment:

| Path | Active When |
|---|---|
| **Kafka topic** `transactions.raw` (Protobuf) | `prod`, `int`, `qa`, `load` profiles |
| **HTTP POST** `/api/v1/standalone/submit` or `/api/v1/standalone/stream` | `standalone` and `local` profiles only |

In all environments, the **dev Docker stack sets `SPRING_PROFILES_ACTIVE=local`**, so testers using Docker will use the HTTP standalone endpoints, not Kafka.

The `standalone` profile uses an **H2 in-memory database** (no PostgreSQL, no Kafka, no Vault). The `local` profile uses **PostgreSQL + Kafka with JSON serialisation** (no Confluent Schema Registry needed).

---

## 3. Environments and Ports

| Environment | App (host) | PostgreSQL (host) | Kafka Broker 1 (host) |
|---|---|---|---|
| `dev` | **8081** | 5433 | 9192 |
| `int` | 8082 | 5434 | 9292 |
| `qa` | 8083 | 5435 | 9392 |
| `load` | 8084 | 5436 | 9492 |
| `prod` | 8085 | 5437 | 9592 |

Container-internal port is always **8080**.

Swagger UI: `http://localhost:<host-port>/swagger-ui.html` (root `/` redirects there).

---

## 4. REST API Endpoints

### 4.1 Submit a Transaction (standalone/local only)

**`POST /api/v1/standalone/submit`**

Creates a single transaction and immediately returns the fraud assessment.

**Request body:**

| Field | Type | Required | Constraints |
|---|---|---|---|
| `customerId` | string | Yes | Not blank |
| `merchantId` | string | Yes | Not blank |
| `amount` | decimal | Yes | Must be positive |
| `currency` | string | Yes | Exactly 3 characters |
| `category` | string | No | — |
| `transactionType` | string | No | `CARD_PRESENT`, `CARD_NOT_PRESENT`, `CONTACTLESS`, `ATM`; defaults to `CARD_NOT_PRESENT` |
| `location` | string | No | — |
| `latitude` | decimal | No | Required for geographic rule to activate |
| `longitude` | decimal | No | Required for geographic rule to activate |

**Responses:**

| Status | Condition |
|---|---|
| `200 OK` | Assessment returned |
| `400 Bad Request` | Blank required field, non-positive amount, currency not 3 chars |

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

Approximately **15%** of generated transactions exceed the amount threshold and **~10%** hit a blacklisted merchant.

| Status | Condition |
|---|---|
| `200 OK` | Bulk run complete |
| `400 Bad Request` | `count` outside 1–10000 |

---

### 4.3 List Transactions for a Customer

**`GET /api/v1/transactions?customerId={id}&cursor={iso8601}&pageSize={n}`**

Returns a cursor-paginated list of transactions (with assessments) for a specific customer, ordered by timestamp descending.

| Parameter | Required | Constraints |
|---|---|---|
| `customerId` | Yes | — |
| `cursor` | No | ISO-8601 instant (e.g. `2026-07-23T10:00:00Z`); queries `timestamp < cursor` |
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
| `400 Bad Request` | `pageSize` out of range or `cursor` is not valid ISO-8601 |

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
  "riskScore": 50,
  "assessedAt": "2026-07-23T09:15:01Z",
  "violations": [
    { "ruleName": "AMOUNT_THRESHOLD", "ruleVersion": "1.0", "description": "...", "severity": "HIGH" }
  ]
}
```

| Status | Condition |
|---|---|
| `200 OK` | Assessment found |
| `404 Not Found` | No assessment exists for that transaction ID (empty body) |

---

### 4.5 List Flagged (Fraudulent) Transactions

**`GET /api/v1/transactions/flagged`**

Returns cursor-paginated assessments where `fraudulent = true`. Supports optional filters.

| Parameter | Required | Notes |
|---|---|---|
| `customerId` | No | Filter by customer |
| `ruleViolated` | No | Filter by rule name (e.g. `AMOUNT_THRESHOLD`) |
| `minRiskScore` | No | Minimum risk score (inclusive) |
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

**Filter precedence (only one is applied per request):** `customerId` → `ruleViolated` → `minRiskScore`. Sending multiple filters does **not** combine them — only the highest-precedence non-null filter applies.

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters |

---

### 4.6 List Passed (Cleared) Transactions

**`GET /api/v1/transactions/passed`**

Returns cursor-paginated assessments where `fraudulent = false`.

| Parameter | Required | Notes |
|---|---|---|
| `cursor` | No | ISO-8601 cursor |
| `pageSize` | No | 1–1000; default 20 |

| Status | Condition |
|---|---|
| `200 OK` | Results returned |
| `400 Bad Request` | Invalid parameters |

---

### 4.7 List Fraud Rules

**`GET /api/v1/rules`**

Returns all registered fraud rules and their current state.

**Response 200 OK:**
```json
[
  { "ruleName": "AMOUNT_THRESHOLD", "ruleVersion": "1.0", "priority": 1, "enabled": true },
  { "ruleName": "VELOCITY", "ruleVersion": "1.0", "priority": 2, "enabled": true },
  { "ruleName": "DUPLICATE_TRANSACTION", "ruleVersion": "1.0", "priority": 3, "enabled": true },
  { "ruleName": "BLACKLISTED_MERCHANT", "ruleVersion": "1.0", "priority": 4, "enabled": true },
  { "ruleName": "GEOGRAPHIC_ANOMALY", "ruleVersion": "1.0", "priority": 5, "enabled": true }
]
```

Rules cannot be modified at runtime — changes require redeployment.

---

### 4.8 Health / Observability

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Service health check |
| `GET /actuator/prometheus` | Prometheus metrics scrape |
| `GET /actuator/info` | App info |
| `GET /actuator/metrics` | Spring metrics |

---

## 5. Fraud Detection Rules — Full Specification

Rules are evaluated in priority order (1 → 5). All active rules are checked — the engine does **not** short-circuit after the first violation.

### 5.1 AMOUNT_THRESHOLD (Priority 1, Severity: HIGH, Score: 50)

**Trigger:** Transaction amount **strictly greater than** 5000.00.

| Amount | Triggers? |
|---|---|
| 4999.99 | No |
| 5000.00 | **No** (boundary — does NOT trigger) |
| 5000.01 | Yes |
| 9999.99 | Yes |

- Threshold is currency-agnostic (same value regardless of ZAR, USD, EUR, etc.)
- Default threshold: 5000.00 — configurable via `fraud.rules.amount-threshold.threshold`

---

### 5.2 VELOCITY (Priority 2, Severity: HIGH, Score: 50)

**Trigger:** The same customer has made **5 or more prior** transactions within the last 10 minutes when the current transaction is submitted.

- The current transaction being evaluated is **excluded** from the lookback count.
- So: 5 prior transactions in the window → 6th transaction triggers the rule.
- Default window: 10 minutes; default max: 5 transactions.
- Config: `fraud.rules.velocity.max-transactions`, `fraud.rules.velocity.window-minutes`
- Window cannot exceed the global context lookback (60 minutes) — app fails to start if misconfigured.

| Prior transactions in window | Triggers? |
|---|---|
| 4 | No |
| 5 | **Yes** |
| 10 | Yes |

---

### 5.3 DUPLICATE_TRANSACTION (Priority 3, Severity: CRITICAL, Score: 100)

**Trigger:** Same customer, same merchant ID, and same amount submitted more than once within a time window.

**Window is transaction-type dependent:**

| Transaction Type | Window |
|---|---|
| `CARD_PRESENT` | 30 seconds |
| `CONTACTLESS` | 30 seconds |
| `ATM` | 30 seconds |
| `CARD_NOT_PRESENT` | 300 seconds (5 minutes) |

**Important:** Currency is **ignored** by design. A 100.00 ZAR and a 100.00 USD at the same merchant count as duplicates.

---

### 5.4 BLACKLISTED_MERCHANT (Priority 4, Severity: CRITICAL, Score: 100)

**Trigger:** The transaction's `merchantId` appears in the blacklisted merchants table.

**Pre-seeded blacklisted merchants:**

| Merchant ID | Reason |
|---|---|
| `MERCHANT_FRAUD_001` | Phishing |
| `MERCHANT_FRAUD_002` | Card skimming |
| `MERCHANT_FRAUD_003` | Synthetic identity fraud |

- Merchant list is **cached in-memory** (Caffeine, 5-minute TTL, max 10,000 entries).
- A newly blacklisted merchant added directly to the DB will not be picked up until the cache expires (up to 5 minutes).

---

### 5.5 GEOGRAPHIC_ANOMALY (Priority 5, Severity: CRITICAL, Score: 100)

**Trigger:** The implied travel speed between two consecutive geolocated transactions for the same customer exceeds **900 km/h** (approximately the speed of a commercial aircraft).

**Conditions for the rule to activate:**
1. The current transaction must have non-null `latitude` and `longitude`.
2. There must be a prior transaction for the same customer within the last 60 minutes that also has coordinates.
3. The two transactions must be **more than 1 minute apart** in time (pairs within 1 minute are skipped to avoid false positives from batched submissions).

- Algorithm: Haversine formula, Earth radius = 6371 km.
- Speed = distance (km) / time difference (hours).
- Config: `fraud.rules.geographic.max-travel-speed-kmh` (default 900), `fraud.rules.geographic.window-minutes` (default 60).

---

## 6. Risk Scoring and Fraud Verdict

### Score Weights by Severity

| Severity | Score |
|---|---|
| LOW | 10 |
| MEDIUM | 25 |
| HIGH | 50 |
| CRITICAL | 100 |

### Fraud Verdict Rule

> **`riskScore >= 50` → `fraudulent = true`**

- Raw score = sum of all triggered rule weights.
- Score is **capped at 100**.
- A single HIGH violation (score 50) is sufficient to mark a transaction fraudulent.
- A single LOW violation (score 10) alone does **not** flag a transaction.

### Examples

| Rules Triggered | Raw Score | Capped Score | Fraudulent? |
|---|---|---|---|
| None | 0 | 0 | No |
| 1 × LOW | 10 | 10 | No |
| 1 × HIGH (e.g. AMOUNT_THRESHOLD) | 50 | 50 | **Yes** |
| 1 × CRITICAL (e.g. BLACKLISTED_MERCHANT) | 100 | 100 | **Yes** |
| 2 × HIGH | 100 | 100 | **Yes** |
| 1 × HIGH + 1 × CRITICAL | 150 | **100** | **Yes** |

---

## 7. Transaction Lifecycle

```
                  ┌─────────────────────────────────────────────┐
                  │               Rule Engine                   │
                  │  1. AMOUNT_THRESHOLD                        │
Kafka /           │  2. VELOCITY                                │
Standalone POST ──► status: PENDING  ──► status: ASSESSED ──────► DB (fraud_assessments)
                  │  3. DUPLICATE                               │
                  │  4. BLACKLISTED_MERCHANT                    │     fraudulent=true  ──► transactions.flagged
                  │  5. GEOGRAPHIC_ANOMALY                      │     fraudulent=false ──► transactions.passed
                  └─────────────────────────────────────────────┘
                               │ error
                               ▼
                          status: FAILED
                          DLT topic
```

**Transaction statuses:**
- `PENDING` — created, not yet assessed
- `ASSESSED` — rule evaluation complete
- `FAILED` — all Kafka retries exhausted; event in dead-letter topic

---

## 8. Kafka Behaviour (non-standalone environments)

| Behaviour | Detail |
|---|---|
| Retry on failure | 3 total attempts, exponential backoff: attempt 1 immediately, attempt 2 after ~1s, attempt 3 after ~2s |
| Dead-letter | `transactions.raw.DLT` — transaction marked `FAILED` |
| Idempotency | If a transaction already exists in DB (duplicate delivery), assessment is re-published without re-saving |
| Transactional | DB commit and Kafka commit are wrapped in `ChainedKafkaTransactionManager`; a DB failure aborts the Kafka commit |

---

## 9. Data Model Summary

### transactions table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `timestamp` | TIMESTAMPTZ | Partition key (daily partitions) |
| `customer_id` | VARCHAR(64) | Not logged (PII policy) |
| `merchant_id` | VARCHAR(64) | Not logged (PII policy) |
| `amount` | NUMERIC(19,4) | |
| `currency` | VARCHAR(3) | |
| `category` | VARCHAR(64) | Nullable |
| `location` | VARCHAR(128) | Nullable |
| `latitude` | DOUBLE | Nullable |
| `longitude` | DOUBLE | Nullable |
| `transaction_type` | VARCHAR(32) | Enum |
| `status` | VARCHAR(32) | PENDING / ASSESSED / FAILED |
| `created_at` | TIMESTAMPTZ | |

### fraud_assessments table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID | Primary key |
| `transaction_id` | UUID | FK → transactions |
| `transaction_timestamp` | TIMESTAMPTZ | Composite FK (partition) |
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

---

## 10. Error Response Format (RFC 7807)

All error responses use `Content-Type: application/problem+json`.

| HTTP Status | Trigger | Response shape |
|---|---|---|
| `400` | `@Valid` field constraint failure | `{ "detail": "Validation failed", "errors": { "fieldName": "message" } }` |
| `400` | Query param constraint failure | `{ "detail": "Invalid request parameters", "errors": { "paramName": "message" } }` |
| `400` | `IllegalArgumentException` | `{ "detail": "<exception message>" }` |
| `400` | Malformed ISO-8601 cursor | `{ "detail": "Invalid date-time value: '<value>'. Expected ISO-8601 format, e.g. 2026-07-23T10:00:00Z" }` |
| `404` | No assessment for transaction ID | Empty body |
| `500` | Unhandled exception | `{ "detail": "An unexpected error occurred" }` |

---

## 11. Authentication and Authorization

**There is no authentication or authorization on any HTTP endpoint.** No API key, Bearer token, or session cookie is required. All endpoints are completely open.

---

## 12. Key Testing Facts and Gotchas

1. **Standalone vs local profiles:** `standalone` = H2 in-memory + no Kafka. `local` = PostgreSQL + Kafka with JSON. The dev Docker stack uses `local`. Use H2 console at `/h2-console` when on `standalone`.

2. **Amount boundary:** 5000.00 does **not** trigger `AMOUNT_THRESHOLD`. 5000.01 does. Test both sides.

3. **Velocity count:** The rule triggers when there are **5 prior** transactions in the window — the current transaction being submitted is the 6th total. Sending 5 transactions quickly enough and then a 6th should trigger it.

4. **Duplicate rule and currency:** `amount=100.00, currency=ZAR` and `amount=100.00, currency=USD` at the same merchant in the same window ARE treated as duplicates.

5. **Duplicate window varies by type:** `CARD_NOT_PRESENT` window is 5 minutes; physical channels (`CARD_PRESENT`, `CONTACTLESS`, `ATM`) window is only 30 seconds.

6. **Geographic rule requires coordinates:** A transaction with no `latitude`/`longitude` will never trigger `GEOGRAPHIC_ANOMALY`, regardless of other transactions.

7. **Geographic rule skips near-simultaneous transactions:** Two transactions less than 1 minute apart in time are not checked for travel speed.

8. **Filter mutual exclusion on `/flagged`:** If you send `customerId=X&ruleViolated=Y`, only `customerId=X` is applied. Filters do not combine.

9. **Pagination cursor is strictly less than:** A cursor of `T` returns records with `timestamp < T`. The cursor itself is not included.

10. **Blacklist cache TTL:** If you add a merchant to the blacklist DB directly, allow up to 5 minutes before the cache refreshes and the rule activates.

11. **Risk score cap:** Score cannot exceed 100. Multiple simultaneous violations do not push it above 100 in the response or DB.

12. **`fraudulent` verdict threshold is 50:** Score 49 = not fraudulent. Score 50 = fraudulent.

13. **Context lookback is 60 minutes globally:** Velocity and geographic rules only look back 60 minutes. Transactions older than that are invisible to those rules.

14. **Partition maintenance job:** Runs at 02:00 daily. Creates a partition 2 days ahead; drops the partition from 91 days ago. Test data from > 90 days ago will be purged.

15. **No runtime rule config change:** Rules cannot be enabled/disabled or have thresholds changed via API. Requires a redeployment with updated config.

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

- **Logs:** Every log line carries `requestId` (per HTTP request) and `transactionId`. Customer and merchant IDs are **not** logged.
- **Metrics:** Prometheus at `GET /actuator/prometheus`. Key counter: `fraud.dlt.total` (increments each time a transaction hits the dead-letter topic).
- **Tracing:** OpenTelemetry (OTLP). In dev, no collector is configured — traces are dropped silently.
