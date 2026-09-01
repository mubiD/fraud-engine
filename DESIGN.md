# Fraud Rule Engine — System Design Document

**Author:** Mubashir
**Date:** 2026-08-31
**Stack:** Java 21 · Spring Boot 3.3 · Apache Kafka 3 (KRaft, 3-broker) · Protobuf · Confluent Schema Registry · PostgreSQL 16 (range-partitioned) · HashiCorp Vault · Prometheus · OpenTelemetry · Docker · JUnit 5 · Mockito · Testcontainers · k6
**Status:** Current — describes the production architecture as implemented

---

## Table of Contents

1. [Purpose & Scope](#1-purpose--scope)
2. [System Overview](#2-system-overview)
3. [Inbound Layer — Kafka Ingestion](#3-inbound-layer--kafka-ingestion)
4. [Messaging Layer — Apache Kafka](#4-messaging-layer--apache-kafka)
5. [Processing Layer — The Rule Engine](#5-processing-layer--the-rule-engine)
6. [Persistence Layer — PostgreSQL](#6-persistence-layer--postgresql)
7. [Query Layer — REST API](#7-query-layer--rest-api)
8. [Infrastructure — Docker, Helm & Vault](#8-infrastructure--docker-helm--vault)
9. [Testing Strategy](#9-testing-strategy)
10. [Extensibility & Future-Proofing Summary](#10-extensibility--future-proofing-summary)
11. [Known Drawbacks & Production Considerations](#11-known-drawbacks--production-considerations)

---

## 1. Purpose & Scope

This document describes the architecture, design decisions, and trade-offs of the Fraud Rule Engine Service. The system consumes categorised transaction events from Kafka, evaluates them against a configurable set of fraud rules, persists the results, and routes outcomes to dedicated downstream topics for consumption elsewhere (alerting, reporting, model training).

The system is asynchronous and **post-authorisation only** — see [§11](#11-known-drawbacks--production-considerations) for what that means in practice and what it would take to add a real-time, pre-authorisation decision path.

---

## 2. System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      MESSAGING LAYER (inbound)                  │
│              Kafka Topic: transactions.raw (Protobuf)           │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                     PROCESSING LAYER                            │
│   Kafka Consumer → EvaluationContextBuilder → Rule Engine       │
│   → FraudAssessment (Kafka TX + DB TX committed together)       │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    PERSISTENCE LAYER                             │
│      PostgreSQL, daily-partitioned (transactions + assessments) │
└─────────────────────────────────────────────────────────────────┘
                    ↓                           ↓
┌───────────────────────────────┐   ┌─────────────────────────────┐
│   MESSAGING LAYER (outbound)   │   │        QUERY LAYER          │
│  transactions.flagged/.passed  │   │        REST API (JWT)       │
└───────────────────────────────┘   └─────────────────────────────┘
```

There is no synchronous write path for transaction *ingestion* — Kafka is the only mechanism by which a transaction enters this service. The query API is almost entirely read-only, with one narrow exception: `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record an assessment's real-world ground truth after the fact (§7) — it never accepts a transaction for evaluation, so the ingestion story above is unchanged. The read path (query API) is otherwise entirely separate from the write path (Kafka consumer → rule engine → persistence) and can be scaled, deployed, or queried independently, which is the practical benefit of the CQRS-style split even without a formal CQRS implementation.

---

## 3. Inbound Layer — Kafka Ingestion

### There is no HTTP submission endpoint

Transactions enter the system exclusively via the `transactions.raw` Kafka topic. `/api/v1/**` exposes query endpoints only (§7) — nothing in it accepts a *transaction* for evaluation. The one exception to "read-only" is `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record an existing assessment's real-world ground truth; it operates on an assessment that already exists, not a new transaction, so it doesn't reopen an HTTP ingestion path.

### Decision: Kafka as the only ingress

Whatever upstream system originates a transaction (an authorisation switch, a core banking platform, a payments gateway) publishes directly to `transactions.raw`. This service never sits in that system's request/response path:

- **No coupling to producer throughput or availability** — the producer publishes and moves on; this service's uptime or backlog cannot slow it down.
- **Durability by construction** — a transaction that reaches the topic is retained regardless of whether this service is up, deploying, or degraded.
- **Backpressure absorbed by Kafka**, not by blocking producers.

### Trade-off

Because ingestion is asynchronous and decoupled, this service cannot return a fraud verdict inline with the transaction — a verdict only exists once the consumer has processed the event and persisted an assessment (`GET /transactions/{id}/assessment`, or a downstream consumer of `transactions.flagged`/`transactions.passed`). This is the correct model for post-authorisation fraud analysis; it is **not** a model that can gate a card authorisation before it completes. See [§11](#11-known-drawbacks--production-considerations).

### `standalone` / `local` profiles: a demo/dev-only synchronous stub

`StandaloneTransactionController` (`POST /api/v1/standalone/submit`, `/stream`) exists purely so the rule engine can be exercised without a full Kafka pipeline — useful for demos and local iteration. It is explicitly a stub: active only under the `standalone` (in-memory H2, no Kafka) and `local` (real Postgres + Kafka, JSON wire format) profiles, and is absent from every other environment. It calls `RuleEngine.evaluate()` synchronously and returns the assessment inline — the opposite of the production ingestion model — to prove the engine *can* run outside Kafka, not to represent how it runs in practice.

---

## 4. Messaging Layer — Apache Kafka

### Topic design

| Topic | Partitions | Direction | Message type |
|---|---|---|---|
| `transactions.raw` | 6 | Inbound (consumed) | `TransactionEvent` (Protobuf) |
| `transactions.raw-0`, `transactions.raw-1` | 6 | Internal retry | auto-created by `@RetryableTopic` |
| `transactions.raw.DLT` | 1 | Dead-letter | exhausted-retry events |
| `transactions.flagged` | 3 | Outbound (produced) | `FraudulentTransactionEvent` (Protobuf) |
| `transactions.pending-review` | 3 | Outbound (produced) | `PendingReviewTransactionEvent` (Protobuf) |
| `transactions.passed` | 3 | Outbound (produced) | `ClearedTransactionEvent` (Protobuf) |

All topics run with replication factor 2 across a 3-broker KRaft cluster (no Zookeeper dependency). Schemas are registered with and enforced by Confluent Schema Registry.

### Partitioning strategy

`transactions.raw` is partitioned by `customerId`, so every transaction for a given customer is routed to the same partition and processed in order by the same consumer. This matters for `VelocityRule` and every other window-based rule that reasons over a customer's recent transaction history — without it, two events for the same customer could be evaluated concurrently and race.

`spring.kafka.listener.concurrency` is set to 6 to match the partition count, so throughput scales with partition count while the per-customer ordering guarantee is preserved (each thread owns a disjoint subset of partitions; ordering is a per-partition property, not a single-thread property).

### Dead letter handling

`@RetryableTopic` retries a failed message 3 times with exponential backoff before routing it to `transactions.raw.DLT`; the `@DltHandler` marks the transaction `FAILED` rather than dropping it silently.

### Exactly-once semantics

The DB write and the Kafka publish commit or roll back together via `ChainedKafkaTransactionManager`:

1. Kafka TX opens
2. DB TX opens
3. `FraudAssessment` + updated `TransactionStatus` written to Postgres
4. Outcome event published to `transactions.flagged`, `transactions.pending-review`, or `transactions.passed`
5. DB TX commits; Kafka TX commits

If the DB commit fails, the Kafka TX aborts and no message is published — the consumer retries cleanly. If the Kafka commit fails after the DB commit, the consumer retries; the idempotency guard (`findByIdOnly` — skip re-save if the transaction row already exists) prevents a duplicate insert and simply re-publishes the outcome event. The consumer runs with `isolation.level=read_committed` so downstream readers never see an uncommitted write.

### Trade-offs

- **Retry-topic ordering.** `@RetryableTopic`'s auto-created retry topics get their own partition assignment on redelivery — a message that fails once and is retried isn't guaranteed to land back on the customer-ordered partition it started on. Under sustained retries this is a latent gap in the ordering guarantee the window-based rules assume; it hasn't caused an observed issue, but it's a real edge case, not a theoretical one.
- **Eventual consistency.** There is a window, bounded by consumer lag, between a transaction being published and its assessment existing. Systems needing an immediate verdict cannot use this pipeline as-is (§11).

---

## 5. Processing Layer — The Rule Engine

### Pattern: Strategy

Each fraud rule implements:

```java
public interface FraudRule {
    RuleResult evaluate(Transaction transaction, EvaluationContext context);
    String getRuleName();
    String getRuleVersion();
    int getPriority();
    boolean isEnabled();
}
```

`RuleEngine` holds every Spring-registered `FraudRule` bean, filters to enabled ones, sorts by priority, evaluates each against a shared `EvaluationContext`, and aggregates the violations into a `FraudAssessment`. Adding a rule is: implement `FraudRule`, annotate `@Component` — zero changes to `RuleEngine` or any existing rule (Open/Closed).

### The `EvaluationContext`

Built once per transaction by `EvaluationContextBuilder`, so all 13 rules read from a shared, pre-fetched snapshot instead of each rule independently hitting the database:

```java
public class EvaluationContext {
    List<Transaction> recentCustomerTransactions;   // within fraud.rules.context-lookback-minutes
    Set<String> blacklistedMerchantIds;              // Caffeine-cached, 5 min TTL
    Double merchantLatitude, merchantLongitude;       // registered-address fallback, physical channels only
    BigDecimal dailySpendTotal;                       // 24h rolling aggregate, DB query
    List<Transaction> customerBaselineTransactions;   // independent, longer window (days) for CustomerAmountAnomalyRule
}
```

Reference data (the blacklist and merchant locations) is read through `ReferenceDataCache`, a dedicated `@Cacheable`-annotated bean — deliberately *not* methods on `EvaluationContextBuilder` itself, because Spring's caching proxy only intercepts calls arriving from outside a bean; a self-invoked call bypasses the cache silently. Keeping cached reads on a separate collaborator means every call goes through the proxy and is actually cached.

`RuleProperties.validate()` (a `@PostConstruct` check) asserts that every rule's configured window is within `context-lookback-minutes` — a rule whose window is wider than the lookback would silently under-count, since `recentCustomerTransactions` simply wouldn't contain the data it needs. This check is expressed as one exhaustive map of rule name → window, specifically so adding a new window-based rule later means adding one line to that map, not writing a new one-off `if`.

### The rule catalogue

13 rules, each independently unit-tested with no Spring context, no database, no Kafka:

| Priority | Rule | Signal | Severity |
|---|---|---|---|
| 1 | `AMOUNT_THRESHOLD` | amount exceeds a (category-tiered) threshold | HIGH |
| 2 | `VELOCITY` | >5 transactions in 10 minutes | HIGH → CRITICAL (high-risk category) |
| 3 | `DUPLICATE_TRANSACTION` | same merchant+amount+currency within a channel-aware window | CRITICAL |
| 4 | `BLACKLISTED_MERCHANT` | merchant on the blacklist | CRITICAL |
| 5 | `GEOGRAPHIC_ANOMALY` | implied travel speed > 900 km/h (Haversine) | CRITICAL |
| 6 | `CARD_CLONING` | same amount at 2+ merchants within 10 minutes | MEDIUM |
| 7 | `TIME_OF_DAY_ANOMALY` | 23:00–05:00 UTC | MEDIUM |
| 8 | `HIGH_RISK_MERCHANT_CATEGORY` | crypto/gambling/money-transfer categories | HIGH / MEDIUM |
| 9 | `DEVICE_FINGERPRINT` | unseen device fingerprint for this customer | HIGH |
| 10 | `MULTI_CHANNEL_ANOMALY` | physical↔online channel switch within 5 minutes | MEDIUM |
| 11 | `CROSS_MERCHANT_VELOCITY` | ≥10 transactions across merchants within 10 minutes | MEDIUM |
| 12 | `CUMULATIVE_SPENDING` | rolling hourly or daily spend exceeds a limit | HIGH |
| 13 | `CUSTOMER_AMOUNT_ANOMALY` | amount >3 stddev above the customer's own historical mean | MEDIUM |

Full trigger conditions and configuration keys are in `README.md`, which is kept in sync with `application.yml` and should be treated as the source of truth for rule behaviour — this document covers architecture, not tunables.

### Risk scoring

Violations are combined with a log-odds (naive-Bayes) model rather than summed points: each fired rule carries a likelihood ratio (how much more likely fraud is, given that rule fired, keyed by `RULE_NAME:SEVERITY` so a rule that escalates severity at runtime, like `VelocityRule`'s high-risk-category boost, is calibrated per variant, not per rule). Posterior fraud probability is the sigmoid of the prior log-odds plus the sum of each violation's log-likelihood-ratio; `riskScore` is that probability scaled to 0–100, and `fraudulent` is set when the probability crosses a configurable threshold (default 0.5).

This directly replaces an earlier flat additive model (LOW=10/MEDIUM=25/HIGH=50/CRITICAL=100, capped at 100, fraudulent at >=50) that conflated "one strong signal fired" with "several weak, possibly-correlated signals coincided" — both produced an identical verdict once the point total crossed 50. Rules are now individually calibrated as either standalone-sufficient (`BLACKLISTED_MERCHANT`, `GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, `VELOCITY`, `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, high-risk-tier `HIGH_RISK_MERCHANT_CATEGORY`) or weak-alone, requiring corroboration (`AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, `CUSTOMER_AMOUNT_ANOMALY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`) — see `ScoringProperties.java`.

The likelihood ratios are domain-judgment starting points, not values fit to labelled outcome data. `PATCH /api/v1/transactions/{id}/outcome` (§7) now lets an analyst record whether a flagged transaction was confirmed fraud or a false positive, but nothing yet consumes those recorded outcomes to actually recalibrate these ratios — that analysis/tooling is the next step, not the recording mechanism itself (see [§11](#11-known-drawbacks--production-considerations)).

### Trade-offs

- The Strategy pattern scales comfortably to the current 13 rules. Rules needing conditional branching or dependency graphs (rule A only if rule B passes) would be better served by a dedicated rules engine (e.g. Drools).
- All 13 rules run synchronously on the Kafka consumer thread, and `EvaluationContextBuilder` issues its DB queries sequentially and inline on that same thread — acceptable for the async pipeline's latency budget, but this is precisely the part of the system that would need to be replaced (not the rules themselves, which are pure in-memory logic) to support a real-time, pre-authorisation decision path. See [§11](#11-known-drawbacks--production-considerations). `CustomerAmountAnomalyRule` adds a second such query (a longer-window customer history fetch), gated behind its own `enabled` flag specifically so it doesn't cost anything when unused.

---

## 6. Persistence Layer — PostgreSQL

### Schema

`transactions` is **range-partitioned by `timestamp` (daily)** with a composite primary key `(id, timestamp)` — Postgres requires the partition key in every unique constraint on a partitioned table. `fraud_assessments` references it via a composite FK `(transaction_id, transaction_timestamp)` rather than a single-column FK. `rule_violations`, `blacklisted_merchants`, and `merchant_locations` round out the schema. Flyway (`V1`–`V8`) manages all schema evolution; `spring.jpa.hibernate.ddl-auto=validate` means the app refuses to start if the entity model and schema have drifted apart.

### Indexing strategy

```sql
-- VelocityRule, DuplicateRule, and the context builder's recent-transaction query
CREATE INDEX idx_transactions_customer_timestamp ON transactions(customer_id, timestamp DESC);

-- DuplicateRule candidate lookup
CREATE INDEX idx_transactions_duplicate_detection ON transactions(merchant_id, amount, customer_id, timestamp DESC);

-- Composite index (disposition, assessed_at DESC) — one index serves all three
-- disposition-filtered, assessed_at-ordered query paths (flagged/pending-review/passed)
CREATE INDEX idx_assessments_disposition_assessed_at ON fraud_assessments(disposition, assessed_at DESC);

CREATE INDEX idx_assessments_transaction_id ON fraud_assessments(transaction_id);
```

### Data lifecycle

`PartitionMaintenanceJob` runs nightly at 02:00: pre-creates the partition for `today + 2 days` and drops the partition for `today − 91 days` (90-day retention). It's the one component explicitly disabled under the `standalone` profile, since the H2 in-memory database used there doesn't support table partitioning.

### Trade-offs

- **Single writer per partition** — the Kafka consumer writes to Postgres sequentially. At very high volume this becomes the throughput bound; mitigation is batch inserts or a wider connection pool, not yet needed at current scale.
- **No read replica** — a Postgres outage takes down both the write and read paths.

---

## 7. Query Layer — REST API

OAuth2/JWT-secured in every profile except `local`/`standalone`/`test`, and read-only
except for one write endpoint. Full endpoint reference, request/response shapes, and
curl examples live in `README.md`; the summary:

```
Transactions   GET   /transactions, /transactions/{id}, /transactions/{id}/assessment,
                     /transactions/flagged, /transactions/pending-review, /transactions/passed
               PATCH /transactions/{id}/outcome   (the one write endpoint — see below)
Rules          GET /rules   (live config, read-only — there is no PATCH; a threshold
                             change requires a redeploy)
Customers      GET /customers/{id}/risk-summary
Merchants      GET /merchants/{id}/flagged, /merchants/{id}/risk-summary
Stats          GET /stats/fraud-summary
```

### Three-way disposition (`CLEARED` / `PENDING_REVIEW` / `FLAGGED`)

`FraudAssessment.disposition` (replacing an earlier binary `fraudulent` boolean)
is the rule engine's real-time verdict, computed by `RuleEngine` from two
probability thresholds instead of one: `fraud.scoring.fraud-probability-threshold`
(default 0.5, unchanged) still decides `FLAGGED`; a new, lower
`fraud.scoring.review-probability-threshold` (default 0.10) decides `PENDING_REVIEW`
for everything above it but below the fraud threshold. Everything below both stays
`CLEARED`. This doesn't change what qualifies as `FLAGGED` — it only stops the old
model's silent behaviour of treating "two corroborating weak signals" identically to
"zero signals" just because neither alone crossed 0.5 (see §5's combined-signal
scenarios in `FraudEngineEffectivenessTest`, e.g. card-cloning + off-hours, which now
land in `PENDING_REVIEW` instead of being indistinguishable from a clean transaction).

`PENDING_REVIEW` is published to its own Kafka topic (`transactions.pending-review`,
§4) and exposed via `GET /transactions/pending-review`, mirroring `/flagged`'s filter
set — three genuinely disjoint buckets, not overlapping subsets of each other.

### Recording ground-truth outcomes

`PATCH /transactions/{id}/outcome` lets an analyst record whether a transaction
turned out to be actual fraud or a false positive, keyed by `transactionId` rather
than the assessment's own internal ID — that's the identifier already threaded
through Kafka, the `txn=` MDC/log correlation key (`MdcLoggingFilter`,
`TransactionConsumer`), and every DTO, so this endpoint slots into the existing
trace story instead of introducing a second identifier. `AssessmentOutcome`
(`UNRESOLVED` → `CONFIRMED_FRAUD` / `FALSE_POSITIVE`) is deliberately distinct from
`disposition` above — `disposition` is the system's verdict at assessment time;
`outcome` is the analyst's ground truth recorded afterward, and is a one-time,
non-reversible write once set. This is the ground-truth feed tier 1 of the scoring
calibration plan (§5) depends on — `ScoringProperties`' likelihood ratios are
currently domain judgment, not calibrated against confirmed outcomes, and this
endpoint is what will eventually make that possible.

### Cursor-based pagination

All list endpoints use `WHERE timestamp < :cursor ORDER BY timestamp DESC LIMIT :n` rather than `OFFSET`, so query cost is constant regardless of how deep into the result set the client is — an index range scan either way, instead of a scan-and-discard that grows with offset.

### DTOs and MapStruct

Controllers never return JPA entities. MapStruct generates the entity→DTO mapping at compile time (no runtime reflection cost), which also means the API contract can evolve independently of the database schema, and Hibernate-proxied lazy relationships can never leak into a response.

---

## 8. Infrastructure — Docker, Helm & Vault

### Multi-environment layout

Five self-contained environments (`dev`, `int`, `qa`, `load`, `prod`), each with its own app instance, Postgres, 3-broker Kafka cluster, and (in `dev`) an exposed Schema Registry/Vault/Prometheus, all on distinct host ports so multiple environments run side by side locally via `docker-compose.*.yml`. The same topology is expressed as per-environment Helm values (`helm/values-*.yaml`) for Kubernetes deployment.

### Dockerfile

The runtime image is a single-stage build from a pre-built JAR (`docker/Dockerfile`), not the multi-stage Maven build a from-scratch design would default to. The reason is environmental, not architectural: this environment's Confluent Schema Registry Maven repository now requires authentication, so `mvn dependency:go-offline` returns 403 inside a build container with no local cache or injected credentials. In a pipeline with a corporate Nexus/Artifactory mirror or Confluent credentials available as build secrets, the standard multi-stage form applies instead (documented inline in the Dockerfile). The image runs as a non-root user regardless.

### Secrets — Vault

Vault runs in dev-mode (pre-unsealed, root token) for local/lower environments and AppRole authentication (`application-prod.yml`) in production — a scoped, minimum-privilege identity rather than the root token, with `VAULT_ROLE_ID`/`VAULT_SECRET_ID` injected at deploy time. The production Kafka TLS config disables hostname verification for self-signed certificates; this is explicitly labelled showcase-only in `application-prod.yml` and would need CA-signed certs with proper SANs before it could be considered production-safe as-is.

---

## 9. Testing Strategy

### Layer 1: Unit tests (JUnit 5 + Mockito)

Every rule, the rule engine's orchestration logic, the context builder, and the service layer — all mocked dependencies, no Spring context, millisecond-fast. A dedicated effectiveness suite (`FraudEngineEffectivenessTest`) additionally measures the engine as a whole: sensitivity, specificity, precision, and F1 across a curated set of fraud/legitimate scenarios, plus per-rule pattern coverage. It includes a meta-test that reflectively scans `engine.rules` for every `FraudRule` implementation and fails if one isn't wired into the suite — a guard against exactly the kind of drift where a new rule ships without the effectiveness numbers ever accounting for it.

### Layer 2: Integration tests (Testcontainers)

Real Postgres and Kafka containers per test class, full Spring context, mock (in-process) Schema Registry. Covers the full pipeline (`transactions.raw` → consumer → rule engine → persisted assessment → query API), DLT routing after exhausted retries, and ordered concurrent processing for a single customer.

### Layer 3: Load & performance tests (k6)

Four scenarios against the `load` environment (`01-baseline`, `02-ramp`, `03-spike`, `04-fraud-rules`), reporting p50/p95/p99 latency, throughput, and error rate against thresholds of p95<1500ms / p99<2000ms / error rate<5%. These thresholds are appropriate for the current async, post-authorisation pipeline; they are not a proxy for the sub-100ms budget a pre-authorisation path would need.

---

## 10. Extensibility & Future-Proofing Summary

| Design Decision | Future Capability Enabled |
|---|---|
| Kafka between ingestion and the rule engine | New consumer groups (ML scoring, notifications, analytics) added with zero changes upstream |
| `FraudRule` + `@Component` registration | New rules added without touching the engine |
| `EvaluationContext` as a shared data carrier | New data sources added without changing any rule signature |
| `transactions.flagged` / `transactions.passed` | Downstream consumers plug in without a topology change |
| JSONB-free, first-class `rule_violations` table | Queryable per-rule breakdown without JSON parsing in SQL |
| Cursor-based pagination | Read API scales to large result sets at constant query cost |
| DTOs + MapStruct | API contract versioned independently of the DB schema |
| `RuleProperties.validate()` as one exhaustive map | A missed window-vs-lookback check is one omitted map entry, not an easy-to-miss `if` |
| `ReferenceDataCache` as a separate bean | Caching that survives a self-invocation call site, and a template for caching further reference data |

---

## 11. Known Drawbacks & Production Considerations

| Drawback | Status / Mitigation |
|---|---|
| **Post-authorisation only** — no synchronous, blocking decision path exists before a transaction is authorised. The rules themselves are pure in-memory logic and could run in a sub-100ms budget; `EvaluationContextBuilder`'s live, sequential Postgres queries are what can't. A real-time path needs a materialised/streaming view of recent-transaction and spend state kept current off the same event stream, not a live query per decision. | Open — the largest architectural gap, by design scope, not oversight. |
| **No confirmed-fraud/false-positive feedback loop** — the log-odds model's likelihood ratios are domain judgment, not calibrated against real outcomes. | Partially addressed: `AssessmentOutcome` + `PATCH /api/v1/transactions/{id}/outcome` (§7), per-customer behavioural baselining (`CustomerAmountAnomalyRule`, §5), and the three-way `disposition` (`CLEARED`/`PENDING_REVIEW`/`FLAGGED`, §7) are all implemented. Still open: nothing yet *consumes* recorded outcomes to actually recalibrate `ScoringProperties`' likelihood ratios — that analysis/tooling doesn't exist yet. The new `transactions.pending-review` Kafka topic/proto message also hasn't been integration-tested against a real broker/Schema Registry (no Docker in the dev sandbox this was built in). |
| **Retry-topic partition reassignment** — a redelivered message isn't guaranteed to land back on its original customer-ordered partition. | Latent; no observed incident. |
| **No read replica** — a Postgres outage takes down both read and write paths. | On AWS, RDS Multi-AZ would provide automatic failover; not provisioned locally. |
| **Single-writer consumer per partition** — Postgres write throughput is the eventual ceiling at very high volume. | Batch inserts / wider pool if it becomes the bottleneck; not yet needed. |
| **Fail-fast startup on IDP unavailability** — the app refuses to start if it can't fetch JWKS from the configured issuer. | Intentional (fail fast over serving unauthenticated requests), but worth knowing before a deploy that depends on IDP reachability. |
| **Self-signed TLS with hostname verification disabled in `prod`'s Kafka config** | Explicitly labelled showcase-only in `application-prod.yml`; needs CA-signed certs with correct SANs before production use. |
| **No distributed schema-compatibility gate beyond Schema Registry's own enforcement** | Confluent Schema Registry enforces backward/forward compatibility on registration; no additional CI gate on top of it. |
