# Fraud Rule Engine — System Design Document

**Author:** Mubashir  
**Date:** 2026-06-15  
**Stack:** Java 25 · Spring Boot · Apache Kafka · PostgreSQL · Docker · JUnit 5 · Mockito · Testcontainers · k6  
**Status:** In Progress

---

## Table of Contents

1. [Purpose & Scope](#1-purpose--scope)
2. [System Overview](#2-system-overview)
3. [Inbound Layer — REST API](#3-inbound-layer--rest-api)
4. [Messaging Layer — Apache Kafka](#4-messaging-layer--apache-kafka)
5. [Processing Layer — The Rule Engine](#5-processing-layer--the-rule-engine)
6. [Persistence Layer — PostgreSQL](#6-persistence-layer--postgresql)
7. [Query Layer — REST API](#7-query-layer--rest-api)
8. [Infrastructure — Docker & Docker Compose](#8-infrastructure--docker--docker-compose)
9. [Testing Strategy](#9-testing-strategy)
10. [Extensibility & Future-Proofing Summary](#10-extensibility--future-proofing-summary)
11. [Known Drawbacks & Production Considerations](#11-known-drawbacks--production-considerations)

---

## 1. Purpose & Scope

This document describes the architecture, design decisions, trade-offs, and extensibility considerations for the Fraud Rule Engine Service. The service processes categorised transaction events, evaluates them against a configurable set of fraud rules, persists the results, and exposes them via a REST API.

The system is designed to be **production-grade**: it prioritises durability, decoupling, testability, and maintainability over simplicity of implementation. Design decisions are made with explicit awareness of how they would need to evolve in a real production environment.

---

## 2. System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        INBOUND LAYER                            │
│   REST API (POST /transactions)  ──→  Kafka Producer            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      MESSAGING LAYER                            │
│              Kafka Topic: transactions.categorized              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     PROCESSING LAYER                            │
│   Kafka Consumer → Rule Engine → FraudAssessment builder        │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    PERSISTENCE LAYER                            │
│              PostgreSQL (transactions + assessments)            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                       QUERY LAYER                               │
│         REST API (GET fraud flags, rules, assessments)          │
└─────────────────────────────────────────────────────────────────┘
```

The system is deliberately split into a **write path** (ingest → process → persist) and a **read path** (query persisted results). This separation reflects CQRS (Command Query Responsibility Segregation) thinking — even without a full CQRS implementation, each path can be optimised, scaled, or replaced independently without affecting the other.

---

## 3. Inbound Layer — REST API

### Responsibility

`POST /api/v1/transactions` receives a transaction payload, validates it, and publishes it to Kafka. It does **not** evaluate fraud rules inline — it validates, acknowledges receipt with `202 Accepted`, and hands off to the messaging layer.

### Decision: Asynchronous Ingestion

Processing fraud rules synchronously within the HTTP request-response cycle would create several problems:

- **Tight coupling** between HTTP throughput and rule evaluation speed — a slow rule bottlenecks the API
- **No backpressure** — a traffic spike overwhelms the rule engine directly
- **No durability** — if rule processing fails mid-request, the transaction is lost
- **Slower client responses** — clients are blocked waiting for evaluation to complete

By publishing to Kafka and returning `202 Accepted`, the API remains fast and durable regardless of rule complexity or traffic volume.

### Trade-off

The client does not receive an immediate fraud verdict. This is an **asynchronous model** — the caller must poll `GET /transactions/{id}/assessment` to retrieve the result. For real-time card authorisation (synchronous, sub-100ms decisions) this model would be unacceptable. For post-authorisation fraud analysis — which is the scope of this service — asynchronous processing is the standard industry pattern.

### Extensibility

The controller is intentionally thin. It delegates to a `TransactionService` which owns the Kafka publishing logic. If the messaging infrastructure changes (e.g. Kafka replaced with AWS SQS), only the service layer changes. The controller remains untouched.

---

## 4. Messaging Layer — Apache Kafka

### Why Kafka

Kafka provides three capabilities that direct service-to-service calls cannot:

1. **Durability** — messages are written to disk and retained with a configurable retention period. If the rule engine crashes mid-processing, no transactions are lost — it resumes from its last committed offset.
2. **Decoupling** — the producer (REST API) and consumer (rule engine) are completely independent. Either can be redeployed, scaled, or replaced without the other being aware.
3. **Throughput** — Kafka handles millions of messages per second. The k6 load tests will validate the system sustains high transaction volume by absorbing spikes in the Kafka queue rather than dropping requests.

### Topic Design

```
transactions.raw          ← REST API publishes inbound transactions here
transactions.flagged      ← Rule engine publishes fraud assessments here (future consumer)
```

`transactions.flagged` is designed now but not consumed in this iteration. In a full system, a notification service, reporting pipeline, or real-time dashboard would consume from it independently. Defining it now avoids a breaking topology change later.

### Partitioning Strategy

`transactions.raw` is partitioned by `customerId`. This guarantees that all transactions for a given customer are routed to the same partition and processed **in order** by the same consumer thread.

This is critical for the **VelocityRule** (detecting too many transactions within a time window). Without ordered, co-located processing of a customer's transactions, two events for the same customer could be evaluated concurrently, producing a race condition in the velocity check.

### Dead Letter Topic (DLT)

Spring Kafka's `@RetryableTopic` is configured to retry failed message processing up to 3 times with exponential backoff. Messages that exhaust retries are routed to a `transactions.raw.DLT` dead letter topic for inspection and reprocessing rather than being silently dropped.

### Kafka Configuration: KRaft Mode

Kafka 3.x introduced **KRaft mode** — Kafka Raft consensus — which eliminates the Zookeeper dependency for cluster metadata and leader election. We use KRaft rather than the traditional Zookeeper setup to:
- Reduce the number of infrastructure components in docker-compose
- Reflect the current direction of the Kafka project (Zookeeper is deprecated)

### Trade-offs & Drawbacks

- **Operational complexity** — Kafka adds a non-trivial infrastructure component with its own configuration surface. Managed by docker-compose locally; in production, AWS MSK or Confluent Cloud handles this.
- **Eventual consistency** — there is a small window between transaction submission and fraud assessment completion. Systems that depend on immediate fraud verdicts cannot use this pattern.
- **Single broker** — the local setup runs a single Kafka broker with no replication. A production cluster would run 3+ brokers with replication factor 3.
- **Producer failure** — if Kafka is unavailable, the API cannot accept transactions without a local buffer/fallback. Production mitigation: Kafka client retries with idempotent producer config, circuit breaker pattern.

### Extensibility

Multiple independent consumer groups can read from the same `transactions.raw` topic simultaneously. Today it is the rule engine. Future consumers added without any changes to the producer:

- Real-time analytics / dashboard consumer
- ML-based scoring service consumer
- Audit logging consumer
- Notification service consumer (downstream of `transactions.flagged`)

---

## 5. Processing Layer — The Rule Engine

This is the architecturally most significant component and the primary demonstration of SE2-level design maturity.

### Pattern: Strategy + Chain of Responsibility

Each fraud rule implements a `FraudRule` interface:

```java
public interface FraudRule {
    RuleResult evaluate(Transaction transaction, EvaluationContext context);
    String getRuleName();
    int getPriority();
    boolean isEnabled();
}
```

The `RuleEngine` holds an ordered list of `FraudRule` implementations and evaluates each in sequence:

```java
public FraudAssessment evaluate(Transaction transaction) {
    EvaluationContext context = contextBuilder.build(transaction);

    List<RuleViolation> violations = rules.stream()
        .filter(FraudRule::isEnabled)
        .sorted(Comparator.comparingInt(FraudRule::getPriority))
        .map(rule -> rule.evaluate(transaction, context))
        .filter(RuleResult::isViolation)
        .map(RuleResult::toViolation)
        .collect(toList());

    return FraudAssessment.from(transaction, violations);
}
```

### Why This Pattern

**Open/Closed Principle** — the `RuleEngine` is closed for modification but open for extension. A new rule is added by writing a new class implementing `FraudRule` and annotating it `@Component`. Zero changes to `RuleEngine` or any existing rule.

**Testability** — each rule is independently unit-testable in complete isolation: no Spring context, no database, no Kafka. Tests are fast, deterministic, and focused.

**Runtime toggling** — the `isEnabled()` check allows rules to be disabled via configuration without redeployment. Useful for disabling a rule that is producing false positives in production while a fix is prepared.

**Priority ordering** — rules with lower cost (simple threshold checks) run before expensive rules (DB-querying velocity checks), short-circuiting evaluation early where possible.

### The EvaluationContext

Rather than each rule depending directly on repositories, an `EvaluationContext` object is built **once per transaction evaluation** and passed to all rules:

```java
public class EvaluationContext {
    private final List<Transaction> recentCustomerTransactions;
    private final Set<String> blacklistedMerchants;
    private final CustomerProfile customerProfile;
}
```

**Why:** Without context, each rule would independently query the database — producing N database round trips per transaction evaluation (one per rule). The context pre-loads all required data once, and rules read from it as a local cache. This is the **Flyweight pattern** applied to rule evaluation.

### The 5 Fraud Rules

**1. AmountThresholdRule**
Flags transactions above a configurable threshold (e.g. > £5,000). Demonstrates config-driven behaviour via `@ConfigurationProperties`.

**2. VelocityRule**
Flags customers who exceed N transactions within a configurable time window (e.g. > 5 in 10 minutes). Uses `recentCustomerTransactions` from `EvaluationContext` to count within the window. Demonstrates temporal reasoning and why partition-by-customerId matters.

**3. DuplicateTransactionRule**
Flags transactions with identical amount + merchant + customer within a short time window. Catches double-charges and replay attacks. Demonstrates idempotency awareness.

**4. BlacklistedMerchantRule**
Flags transactions against a known-bad merchant list loaded from the database and held in `EvaluationContext`. Demonstrates caching strategy (list loaded once per evaluation batch, not per rule call).

**5. GeographicAnomalyRule**
Flags transactions where a customer transacts in two geographically distant locations within an impossible time window (e.g. London and New York within 30 minutes — physically impossible travel). Demonstrates richer domain logic and use of the Haversine formula for distance calculation.

### Risk Scoring

Each rule violation carries a `severity` (LOW / MEDIUM / HIGH / CRITICAL) with an associated score weight. The `FraudAssessment` aggregates violations into a composite `riskScore` (0–100). This enables downstream consumers to apply their own thresholds rather than receiving a binary flag.

### Trade-offs

- The Strategy pattern scales comfortably to ~20 rules. Beyond that, rules may need conditional branching, dependency graphs (Rule A only if Rule B passes), or sub-pipelines. At that scale, a dedicated rules engine library such as **Drools** would be more appropriate. This is noted as a future consideration.
- All rules evaluate synchronously on the Kafka consumer thread. Rules that require external calls (e.g. a third-party ML scoring API) would block the consumer and reduce throughput. Async rule evaluation with `CompletableFuture` would be the mitigation.

### Extensibility

| Capability | How to achieve it |
|---|---|
| Add a new rule | Implement `FraudRule`, annotate `@Component` |
| Short-circuit on critical violation | Add `haltOnViolation()` to the interface |
| Rule versioning for audit trails | Add `getVersion()` to the interface |
| Non-engineer rule management | Load rule config (thresholds, enabled flags) from DB at startup |
| ML-based scoring | Add an `MlScoringRule` that calls an external model endpoint asynchronously |

---

## 6. Persistence Layer — PostgreSQL

### Schema Design

```sql
CREATE TABLE transactions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id   VARCHAR(64)    NOT NULL,
    merchant_id   VARCHAR(64)    NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    currency      VARCHAR(3)     NOT NULL,
    category      VARCHAR(64),
    location      VARCHAR(128),
    latitude      DECIMAL(9, 6),
    longitude     DECIMAL(9, 6),
    timestamp     TIMESTAMPTZ    NOT NULL,
    status        VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE fraud_assessments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID           NOT NULL REFERENCES transactions(id),
    is_fraudulent    BOOLEAN        NOT NULL,
    risk_score       INTEGER        NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    assessed_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    rule_violations  JSONB          NOT NULL DEFAULT '[]'
);

CREATE TABLE rule_violations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id   UUID        NOT NULL REFERENCES fraud_assessments(id),
    rule_name       VARCHAR(64) NOT NULL,
    rule_version    VARCHAR(16) NOT NULL,
    description     TEXT        NOT NULL,
    severity        VARCHAR(16) NOT NULL
);

CREATE TABLE blacklisted_merchants (
    merchant_id   VARCHAR(64) PRIMARY KEY,
    reason        TEXT,
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Why PostgreSQL Over a NoSQL Store

- Transaction data is **inherently relational** — customers, merchants, and transactions have clear foreign key relationships with referential integrity requirements
- **ACID guarantees** are necessary — a fraud assessment must be atomically linked to its transaction; partial writes are unacceptable
- **JSONB** on `fraud_assessments.rule_violations` provides the schema flexibility of NoSQL within a relational model — arbitrary rule metadata can be stored and queried without schema migrations every time a rule changes
- **Rich query capabilities** — filtering by customer, date range, risk score, and rule name is trivial with SQL; equivalent queries in a document store require careful index design

### Indexing Strategy

```sql
-- VelocityRule and DuplicateRule: customer's recent transactions
CREATE INDEX idx_transactions_customer_timestamp
    ON transactions(customer_id, timestamp DESC);

-- DuplicateRule: detect duplicate amount+merchant+customer
CREATE INDEX idx_transactions_duplicate_detection
    ON transactions(merchant_id, amount, customer_id, timestamp DESC);

-- Primary query: fetch all fraudulent assessments efficiently
-- Partial index only indexes rows where is_fraudulent = true, keeping it small
CREATE INDEX idx_assessments_fraudulent
    ON fraud_assessments(is_fraudulent)
    WHERE is_fraudulent = true;

-- Lookup assessment by transaction
CREATE INDEX idx_assessments_transaction_id
    ON fraud_assessments(transaction_id);
```

The **partial index** on `fraud_assessments` is a deliberate production-grade choice. Fraudulent transactions are a small minority of total volume. A partial index only indexes that minority, keeping index size proportional to fraud volume rather than transaction volume, and dramatically speeding up the most common operational query.

### Why Not Store Everything in Kafka

Kafka is a message transport, not a database. It has configurable retention limits (not indefinite), no random-access by key, no query language, and no secondary indexing. PostgreSQL is the persistent record of truth; Kafka is the transport that gets data there.

### Trade-offs

- **Single writer** — the Kafka consumer writes to PostgreSQL sequentially per partition. At very high volume, write throughput becomes the bottleneck. Mitigation: batch inserts, connection pooling (HikariCP), or sharding.
- **Single point of failure** — without a read replica, PostgreSQL outage means both writes and reads fail. On AWS, RDS Multi-AZ provides automatic failover.

### Extensibility

- Add a **Redis cache** in front of PostgreSQL for `EvaluationContext` population (blacklist lookups, recent transaction history) to reduce DB load under high throughput
- Add **read replicas** to scale the query API independently of the write path
- The JSONB `rule_violations` field absorbs new rule metadata fields without schema migrations
- Flyway manages schema migrations, providing versioned, repeatable schema evolution

---

## 7. Query Layer — REST API

### Endpoints

```
GET    /api/v1/transactions/{id}/assessment     Single fraud assessment for a transaction
GET    /api/v1/fraud-flags                      Paginated list of all flagged transactions
GET    /api/v1/fraud-flags?customerId=X         Filter flagged transactions by customer
GET    /api/v1/fraud-flags?ruleViolated=X       Filter by which rule triggered the flag
GET    /api/v1/fraud-flags?minRiskScore=X       Filter by minimum risk score
GET    /api/v1/rules                            List all rules with enabled state and thresholds
PATCH  /api/v1/rules/{ruleName}                 Update rule thresholds or toggle enabled state
```

### Pagination: Cursor-Based

All list endpoints use **cursor-based pagination**, not offset-based.

Offset pagination (`LIMIT 100 OFFSET 500`) requires the database to scan and discard the first 500 rows on every request. At large dataset sizes this becomes progressively slower — O(offset) cost per query.

Cursor-based pagination (`WHERE timestamp < :cursor ORDER BY timestamp DESC LIMIT 100`) has **constant cost** regardless of how deep into the result set the client is, because it uses the index directly.

```json
{
  "data": [...],
  "nextCursor": "2026-06-14T10:30:00Z",
  "hasMore": true
}
```

### DTOs and MapStruct

Controllers never return JPA entities directly. A mapper layer using **MapStruct** converts entities to DTOs at compile time (no reflection overhead at runtime).

**Why this matters in production:**
- Prevents accidental serialisation of Hibernate-proxied lazy-loaded relationships (which triggers N+1 queries or `LazyInitializationException`)
- The API contract can evolve independently of the database schema
- Sensitive or internal fields (audit timestamps, internal IDs) are excluded from responses by omission rather than annotation

### Trade-off

MapStruct requires maintaining separate DTO classes. For a project of this scale, this is justified — it is a strong signal of production API design awareness. The compile-time generation means zero runtime cost for the mapping.

---

## 8. Infrastructure — Docker & Docker Compose

### Services

```yaml
services:
  kafka:       # KRaft mode — no Zookeeper dependency
  postgres:    # PostgreSQL 16
  fraud-engine: # Application container
```

### Multi-Stage Dockerfile

```dockerfile
# Stage 1: Build
FROM maven:3.9-amazoncorretto-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline        # cache dependencies as a separate layer
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Runtime
FROM amazoncorretto:25-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

The multi-stage build ensures:
- The final image contains **only the JRE and the JAR** — no Maven, no source code, no build tools
- Image size is dramatically reduced (production security and storage concern)
- Each stage is independently cacheable — source changes don't invalidate the dependency download layer

### Why KRaft Over Zookeeper

Zookeeper is deprecated in Kafka 3.x and will be removed. KRaft mode uses Kafka's own Raft consensus implementation for metadata management, eliminating an entire infrastructure component from the stack. Using KRaft signals awareness of current Kafka direction.

### AWS Free Tier (Optional Deployment)

| Component | AWS Service | Free Tier |
|---|---|---|
| Database | RDS PostgreSQL db.t3.micro | 750 hrs/month |
| Container registry | ECR | 500 MB/month |
| Application host | EC2 t2.micro | 750 hrs/month |
| Kafka | EC2 t2.micro (self-hosted) | 750 hrs/month |

Deployment steps documented in `README.md`. The architecture does not require AWS — it runs fully locally via docker-compose.

---

## 9. Testing Strategy

The test suite is structured in three layers, each with a distinct purpose and scope.

### Layer 1: Unit Tests — JUnit 5 + Mockito

**Scope:** Individual rule logic, service layer orchestration, DTO mapping.

**Approach:** All dependencies are mocked with Mockito. No Spring context is loaded. Tests are fast (milliseconds) and deterministic.

**Key test cases:**
- Each `FraudRule` evaluated against transactions that should and should not trigger it
- `RuleEngine` verifies correct rule ordering, disabled rules are skipped, violations are collected correctly
- Edge cases: amount exactly at threshold, velocity window boundary, duplicate within vs. outside window

### Layer 2: Integration Tests — Testcontainers

**Scope:** Full processing pipeline — Kafka consumer receives a message, rule engine evaluates it, result is persisted, API returns it.

**Approach:** Testcontainers spins up real PostgreSQL and real Kafka Docker containers per test class. The full Spring context loads against real infrastructure.

**Why Testcontainers over mocks:**
Mocked Kafka and mocked repositories can hide serialisation bugs, SQL errors, Kafka offset commit failures, and transaction rollback behaviour — all of which have caused production incidents. Testcontainers ensures integration tests catch what unit tests cannot.

**Key test cases:**
- End-to-end: submit transaction → Kafka consumed → assessment persisted → API returns result
- DLT routing: malformed message is retried 3 times then routed to dead letter topic
- Concurrent transactions for same customer processed in order (velocity rule correctness)

### Layer 3: Load & Performance Tests — k6

**Scope:** Throughput, latency under load, backpressure behaviour, degradation profile.

**Four scenarios (in `load-tests/scenarios/`):**

**Scenario 1 — Baseline Throughput** (`01-baseline.js`)
```
100 VUs, steady state
Duration: 2 minutes
Goal: establish baseline TPS and p99 latency under normal conditions
```

**Scenario 2 — Ramp Load** (`02-ramp.js`)
```
VUs ramp from 10 → 500 over 5 minutes
Goal: identify the inflection point where latency degrades
      and whether Kafka absorbs the excess gracefully
```

**Scenario 3 — Burst / Spike** (`03-spike.js`)
```
Normal load then sudden 10x burst
Goal: validate Kafka absorbs the burst (transactions queue, not drop)
      and the system recovers without manual intervention
```

**Scenario 4 — Fraud Rules Mix** (`04-fraud-rules.js`)
```
Realistic 60/20/20 traffic mix + concurrent read path
Goal: validate rule evaluation performance under mixed workloads
```

k6 reports p50/p95/p99 latency, TPS, and error rates. **Thresholds:** p95 < 1500ms · p99 < 2000ms · error rate < 5%.

---

## 10. Extensibility & Future-Proofing Summary

| Design Decision | Future Capability Enabled |
|---|---|
| Kafka between API and rule engine | New consumers (ML scoring, notifications, analytics) added with zero changes to existing code |
| `FraudRule` interface with `@Component` registration | New rules added without touching the engine — strict Open/Closed compliance |
| `EvaluationContext` as data carrier | New data sources added to context without changing any rule signatures |
| `transactions.flagged` topic defined now | Downstream notification or reporting services plug in without topology changes |
| JSONB for rule violations | New rule metadata fields added without schema migrations |
| Cursor-based pagination | Read API scales to millions of records with constant query cost |
| DTOs + MapStruct | API contract versioned independently of DB schema |
| Multi-stage Dockerfile | CI/CD pipeline integration with minimal image size |
| KRaft mode Kafka | Zookeeper-free; aligned with Kafka's future direction |
| Partial index on fraud flags | Query performance proportional to fraud volume, not total transaction volume |
| Risk score (0–100) not binary flag | Downstream consumers apply their own thresholds; no breaking change to add nuance |
| Priority ordering on rules | Cheap rules run first; expensive rules only run when necessary |
| `isEnabled()` on each rule | Rules toggled in production without redeployment |

---

## 11. Known Drawbacks & Production Considerations

These are acknowledged explicitly because identifying limitations demonstrates production maturity — it shows awareness of the gap between a working system and a hardened one.

| Drawback | Production Mitigation |
|---|---|
| **Eventual consistency** — fraud verdict is not immediate | Webhook notification when assessment completes, or SSE endpoint for real-time clients |
| **Single Kafka broker** — no replication | 3-broker cluster with replication factor 3, min.insync.replicas=2 |
| **No authentication / authorisation** | Spring Security with OAuth2 resource server; JWT bearer tokens |
| **No circuit breaker** — slow PostgreSQL backs up consumer thread | Resilience4j circuit breaker around DB calls; timeout + fallback to partial assessment |
| **Synchronous rule evaluation** — blocking consumer thread | `CompletableFuture`-based async rule evaluation for rules that call external services |
| **In-memory blacklist** — stale if DB updated mid-evaluation | TTL-based cache invalidation (Caffeine) or Redis pub/sub for invalidation events |
| **No rate limiting on inbound API** | Bucket4j or API Gateway rate limiting to prevent producer DoS |
| **No distributed tracing** | OpenTelemetry instrumentation; trace IDs propagated through Kafka headers |
| **Schema evolution** — Kafka message format changes break consumers | Confluent Schema Registry with Avro; backward/forward compatibility enforced |
