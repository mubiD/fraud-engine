# Fraud Rule Engine — System Design Document

**Author:** Mubashir
**Date:** 2026-08-31
**Stack:** Java 21 · Spring Boot 3.3 · Apache Kafka 3 (KRaft, 3-broker) · Protobuf · Confluent Schema Registry · PostgreSQL 16 (range-partitioned) · HashiCorp Vault · Prometheus · OpenTelemetry · Docker · JUnit 5 · Mockito · Testcontainers · k6
**Status:** Current: describes the production architecture as implemented

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

The system is asynchronous and **post-authorisation only**. See [§11](#11-known-drawbacks--production-considerations) for what that means in practice and what it would take to add a real-time, pre-authorisation decision path.

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

There is no synchronous write path for transaction *ingestion*: Kafka is the only mechanism by which a transaction enters this service. The query API is almost entirely read-only, with one narrow exception: `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record an assessment's real-world ground truth after the fact (§7); it never accepts a transaction for evaluation, so the ingestion story above is unchanged. The read path (query API) is otherwise entirely separate from the write path (Kafka consumer → rule engine → persistence) and can be scaled, deployed, or queried independently, which is the practical benefit of the CQRS-style split even without a formal CQRS implementation.

---

## 3. Inbound Layer — Kafka Ingestion

### There is no HTTP submission endpoint

Transactions enter the system exclusively via the `transactions.raw` Kafka topic. `/api/v1/**` exposes query endpoints only (§7); nothing in it accepts a *transaction* for evaluation. The one exception to "read-only" is `PATCH /api/v1/transactions/{id}/outcome`, which lets an analyst record an existing assessment's real-world ground truth; it operates on an assessment that already exists, not a new transaction, so it doesn't reopen an HTTP ingestion path.

### Decision: Kafka as the only ingress

Whatever upstream system originates a transaction (an authorisation switch, a core banking platform, a payments gateway) publishes directly to `transactions.raw`. This service never sits in that system's request/response path:

- **No coupling to producer throughput or availability**: the producer publishes and moves on; this service's uptime or backlog cannot slow it down.
- **Durability by construction**: a transaction that reaches the topic is retained regardless of whether this service is up, deploying, or degraded.
- **Backpressure absorbed by Kafka**, not by blocking producers.

### Trade-off

Because ingestion is asynchronous and decoupled, this service cannot return a fraud verdict inline with the transaction: a verdict only exists once the consumer has processed the event and persisted an assessment (`GET /transactions/{id}/assessment`, or a downstream consumer of `transactions.flagged`/`transactions.passed`). This is the correct model for post-authorisation fraud analysis; it is **not** a model that can gate a card authorisation before it completes. See [§11](#11-known-drawbacks--production-considerations).

### `standalone` / `local` profiles: a demo/dev-only synchronous stub

`StandaloneTransactionController` (`POST /api/v1/standalone/submit`, `/stream`) exists purely so the rule engine can be exercised without a full Kafka pipeline, useful for demos and local iteration. It is explicitly a stub: active only under the `standalone` (in-memory H2, no Kafka) and `local` (real Postgres + Kafka, JSON wire format) profiles, and is absent from every other environment. It calls `RuleEngine.evaluate()` synchronously and returns the assessment inline (the opposite of the production ingestion model) to prove the engine *can* run outside Kafka, not to represent how it runs in practice.

---

## 4. Messaging Layer — Apache Kafka

### Topic design

| Topic | Partitions | Direction | Message type |
|---|---|---|---|
| `transactions.raw` | 6 | Inbound (consumed) | `TransactionEvent` (Protobuf) |
| `transactions.raw-retry-0`, `transactions.raw-retry-1` | 6 | Internal retry | auto-created by `@RetryableTopic` |
| `transactions.raw.DLT` | 6 | Dead-letter | exhausted-retry events |
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

If the DB commit fails, the Kafka TX aborts and no message is published — the consumer retries cleanly. If the Kafka commit fails after the DB commit (or the offset commit fails after a successful, fully-committed transaction), the consumer redelivers the message. Two idempotency guards handle that: `findByIdOnly` skips re-inserting the `Transaction` row if it already exists, and — as of 2026-09-03 — a `fraudAssessmentRepository.findByTransactionId` check runs before rule evaluation and skips the entire evaluate → save → publish sequence if an assessment already exists for that transaction. Before that second guard was added, a redelivery of an already-fully-committed transaction would silently insert a second `fraud_assessments` row and re-publish a duplicate outcome event on every retry; `fraud_assessments.transaction_id` now also carries a `UNIQUE` constraint (`uq_assessments_transaction_id`) as a database-level backstop for the same guarantee. The consumer runs with `isolation.level=read_committed` so downstream readers never see an uncommitted write.

### Trade-offs

- **Retry-topic partition alignment.** Fixed 2026-09-03. Auto-created retry topics default to 1 partition, and Spring Kafka's retry-topic publisher preserves the original record's partition index by default — worse than a soft ordering gap, this meant any failing message not originally on partition 0 (5 of 6 partitions, most traffic) would throw on the retry/DLT publish rather than just losing customer-ordering. `TransactionConsumer`'s `@RetryableTopic` now sets `numPartitions = "6"` to match `transactionsRawTopic()`, and `transactionsDltTopic()` was bumped from 1 to 6 partitions to match — Spring Kafka requires the DLT to have at least as many partitions as the original topic for the same reason. Cross-checked by `KafkaConfigTest`. **Unverified against a real broker** — no Docker in this environment; same caveat as the `transactions.pending-review` topic and the outcome/idempotency work.
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

Built once per transaction by `EvaluationContextBuilder`, so all 12 rules read from a shared, pre-fetched snapshot instead of each rule independently hitting the database:

```java
public class EvaluationContext {
    List<Transaction> recentCustomerTransactions;   // within fraud.rules.context-lookback-minutes
    Double merchantLatitude, merchantLongitude;       // registered-address fallback, physical channels only
    BigDecimal dailySpendTotal;                       // 24h rolling aggregate
    AmountBaselineStats customerAmountBaseline;        // precomputed count/mean/stdDev, independent longer window (days) for CustomerAmountAnomalyRule
}
```

Reference data (merchant locations) is read through `ReferenceDataCache`, a dedicated `@Cacheable`-annotated bean — deliberately *not* methods on `EvaluationContextBuilder` itself, because Spring's caching proxy only intercepts calls arriving from outside a bean; a self-invoked call bypasses the cache silently. Keeping cached reads on a separate collaborator means every call goes through the proxy and is actually cached.

`RuleProperties.validate()` (a `@PostConstruct` check) asserts that every rule's configured window is within `context-lookback-minutes` — a rule whose window is wider than the lookback would silently under-count, since `recentCustomerTransactions` simply wouldn't contain the data it needs. This check is expressed as one exhaustive map of rule name → window, specifically so adding a new window-based rule later means adding one line to that map, not writing a new one-off `if`.

### Materialised recent-activity state (Kafka Streams)

`recentCustomerTransactions`, `dailySpendTotal`, and (as of the sub-100ms follow-up work) `CustomerAmountAnomalyRule`'s baseline — the three pieces of `EvaluationContext` that used to come from live, sequential Postgres queries on every single evaluation (`findRecentByCustomer` ×2, `sumAmountByCustomerSince` ×1) — are now served by a Kafka Streams topology (`VelocityStreamsTopologyConfig`, package `com.fraudengine.streams`) that consumes `transactions.raw` as a second, independent consumer group and maintains a changelog-backed, per-customer state store (`customer-activity-store`). `EvaluationContextBuilder` reads it via Interactive Queries instead of SQL, covering `VelocityRule`, `CrossMerchantVelocityRule`, `DuplicateTransactionRule`, `CardCloningRule`, `MultiChannelAnomalyRule`, `GeographicAnomalyRule`, both legs of `CumulativeSpendingRule`, and `CustomerAmountAnomalyRule`.

This is a custom Processor API state store, not native `SlidingWindows`/`TimeWindows` KTables: one shared, changelog-backed record list (pruned to `context-lookback-minutes`, mirroring the DB-backed version exactly) serves every rule's differently-sized window, a small 24-hourly-bucket rolling sum for `CumulativeSpendingRule`'s daily leg, and a small 90-daily-bucket rolling `(count, sum, sumOfSquares)` aggregate (`DailyAmountStats`) for `CustomerAmountAnomalyRule`'s baseline — the same shape `EvaluationContext` already had, just backed by RocksDB instead of Postgres. The baseline bucket deliberately stores aggregates, not 90 days of raw transactions per customer (a much larger and mostly-unused piece of hot state for two derived numbers); `AmountBaselineStats` derives mean/stdDev from the aggregate via the sum-of-squares identity on this path specifically, versus the exact definitional formula on the Postgres fallback path, which does have the raw list — see `AmountBaselineStats`'s own javadoc for why the two paths compute it differently and why that's an acceptable trade for a heuristic signal. Native windowed aggregations were considered and rejected: they'd need a separate topology per rule-shape (a count KTable for `VELOCITY`, a sum KTable for `CUMULATIVE_SPENDING`, …) and would force every rule from "filter a list" to "read a pre-aggregated number" — a much larger rewrite for what are, today, simple list-filtering rules.

**Not in scope:** `DeviceFingerprintRule` (`transaction_event.proto`, the production wire format, carries no `device_fingerprint` field at all — this rule is unreachable in production regardless of data source, only exercised via the standalone/local HTTP stub). Merchant location (`ReferenceDataCache`) also stays outside this topology — it's Caffeine-cached, eagerly warmed at startup, not per-transaction state — see the sub-100ms row in §11.

**Fallback:** `EvaluationContextBuilder` catches `StoreUnavailableException` (thrown when the local store isn't yet `RUNNING`, or its partitions are still restoring from the changelog after a restart/rebalance) and falls back to the original Postgres queries for that one evaluation. `standalone`/`local` never register a `RecentActivityStore` bean at all (`@Profile("!standalone & !local")`, matching `KafkaConfig`/`TransactionConsumer`) and always use the Postgres path, unchanged. Which path served a given evaluation is recorded via `fraud.context.source.total{source=STREAMS|POSTGRES}` (`FraudMetrics`).

**Known limitation, accepted rather than solved:** the Streams app and `TransactionConsumer` are independent consumer groups on the same customer-partitioned topic — per-customer *ordering* is preserved by partitioning, but *staleness* isn't: if the Streams group lags behind `TransactionConsumer` (restart, load, rebalance), a read can return state missing the most recent prior transaction(s) for that customer. This is a different failure mode than "store not queryable" and does not trigger the Postgres fallback in this design — it under-counts rather than over-counts, so it's a false-negative risk (a rule fails to fire that should have), not a false-flag risk. Kafka Streams consumer lag is exposed as a metric so this is observable, not silent; automatically detecting and falling back on lag specifically is future work.

### The rule catalogue

12 rules, each independently unit-tested with no Spring context, no database, no Kafka:

| Priority | Rule | Signal | Severity |
|---|---|---|---|
| 1 | `AMOUNT_THRESHOLD` | amount exceeds a (category-tiered) threshold | HIGH |
| 2 | `VELOCITY` | >5 transactions in 10 minutes | HIGH → CRITICAL (high-risk category) |
| 3 | `DUPLICATE_TRANSACTION` | same merchant+amount+currency within a channel-aware window | CRITICAL |
| 5 | `GEOGRAPHIC_ANOMALY` | implied travel speed > 900 km/h (Haversine) | CRITICAL |
| 6 | `CARD_CLONING` | same amount at 2+ merchants within 10 minutes | MEDIUM |
| 7 | `TIME_OF_DAY_ANOMALY` | 23:00–05:00 UTC | MEDIUM |
| 8 | `HIGH_RISK_MERCHANT_CATEGORY` | crypto/gambling/money-transfer categories | HIGH / MEDIUM |
| 9 | `DEVICE_FINGERPRINT` | unseen device fingerprint for this customer | HIGH |
| 10 | `MULTI_CHANNEL_ANOMALY` | physical↔online channel switch within 5 minutes | MEDIUM |
| 11 | `CROSS_MERCHANT_VELOCITY` | ≥10 transactions across merchants within 10 minutes | MEDIUM |
| 12 | `CUMULATIVE_SPENDING` | rolling hourly or daily spend exceeds a limit | HIGH |
| 13 | `CUSTOMER_AMOUNT_ANOMALY` | amount >3 stddev above the customer's own historical mean | MEDIUM |

Priority 4 (`BLACKLISTED_MERCHANT`) is deliberately absent — the rule, and all supporting infrastructure (`blacklisted_merchants` table/migration, `BlacklistedMerchant` entity, `BlacklistedMerchantRepository`, `ReferenceDataCache.getBlacklistedMerchantIds()`, the `blacklistedMerchants` Caffeine cache, and `EvaluationContext.blacklistedMerchantIds`), were removed outright. Rationale: this is a strictly post-authorisation system (§3) — the transaction has already gone through by the time any rule evaluates it, so the rule could never block the fraud it detects, only report on it after the fact. Its `CRITICAL` severity and near-standalone likelihood ratio (see §5's Risk scoring) correctly captured "how certain is this evidence," but implicitly oversold "how much can this system do about it" — the honest answer being: improve a compliance report and prioritise an analyst's review queue, not prevent loss, unless some system outside this one consumes `transactions.flagged` and acts on it fast enough to matter, which nothing here demonstrates.

Full trigger conditions and configuration keys are in `README.md`, which is kept in sync with `application.yml` and should be treated as the source of truth for rule behaviour — this document covers architecture, not tunables.

### Risk scoring

Violations are combined with a log-odds (naive-Bayes) model rather than summed points: each fired rule carries a likelihood ratio (how much more likely fraud is, given that rule fired, keyed by `RULE_NAME:SEVERITY` so a rule that escalates severity at runtime, like `VelocityRule`'s high-risk-category boost, is calibrated per variant, not per rule). Posterior fraud probability is the sigmoid of the prior log-odds plus the sum of each violation's log-likelihood-ratio; `riskScore` is that probability scaled to 0–100, and `fraudulent` is set when the probability crosses a configurable threshold (default 0.5).

This directly replaces an earlier flat additive model (LOW=10/MEDIUM=25/HIGH=50/CRITICAL=100, capped at 100, fraudulent at >=50) that conflated "one strong signal fired" with "several weak, possibly-correlated signals coincided" — both produced an identical verdict once the point total crossed 50. Rules are now individually calibrated as either standalone-sufficient (`GEOGRAPHIC_ANOMALY`, `DUPLICATE_TRANSACTION`, `VELOCITY`, `DEVICE_FINGERPRINT`, `CUMULATIVE_SPENDING`, high-risk-tier `HIGH_RISK_MERCHANT_CATEGORY`) or weak-alone, requiring corroboration (`AMOUNT_THRESHOLD`, `CARD_CLONING`, `TIME_OF_DAY_ANOMALY`, `MULTI_CHANNEL_ANOMALY`, `CROSS_MERCHANT_VELOCITY`, `CUSTOMER_AMOUNT_ANOMALY`, gambling-tier `HIGH_RISK_MERCHANT_CATEGORY`) — see `ScoringProperties.java`.

The likelihood ratios are domain-judgment starting points, not values fit to labelled outcome data. `PATCH /api/v1/transactions/{id}/outcome` (§7) now lets an analyst record whether a flagged transaction was confirmed fraud or a false positive, but nothing yet consumes those recorded outcomes to actually recalibrate these ratios — that analysis/tooling is the next step, not the recording mechanism itself (see [§11](#11-known-drawbacks--production-considerations)).

### Future work: the selection-bias risk in recalibration

Before any pipeline is built to recalibrate `ScoringProperties`' likelihood ratios from recorded `AssessmentOutcome`s, it's worth naming the specific way that goes wrong — the fraud-detection analogue of "reject inference" in credit scoring. An outcome only ever gets recorded when someone reviews a transaction, and today nothing prompts a review of a `CLEARED` transaction. A `CLEARED` transaction that was actually fraud (a false negative) therefore never generates a `CONFIRMED_FRAUD` outcome unless something external forces it back open. A recalibration pipeline built naively off the outcome table would only ever see two kinds of evidence: "flagged, and was fraud" (which reinforces the rule that fired) and "flagged, and was fine" (which weakens it). A rule's likelihood ratio can go down from that pool but never up from a miss it caused, because misses are structurally absent from the training signal. Run that for a few recalibration cycles and the model drifts toward agreeing with its own past dispositions — growing steadily less sensitive to precisely the fraud patterns it already under-detects, until it eventually stops flagging them at all.

Two things already in place keep this from being worse than it has to be: `FraudAssessment` rows are created for every disposition, including `CLEARED` (not only `FLAGGED`/`PENDING_REVIEW`), and `PATCH /outcome` isn't restricted to reviewed transactions — so a `CLEARED` transaction later discovered to be fraud (e.g. via a chargeback reported weeks later) *can* be marked `CONFIRMED_FRAUD` without a data-model change. What's missing is any process that ever surfaces a `CLEARED` transaction for re-examination in the first place — without that, the capability the data model already allows never gets exercised.

Guardrails that would need to exist before any recalibration pipeline is built, not after:

1. **An independent source of truth for misses** — a chargeback/dispute feed, or at minimum random-sampled audits of `CLEARED` transactions — rather than relying solely on the analyst-reviewed queue. Without this, no amount of guarding the recalibration math fixes the underlying censored-data problem; it's the one guardrail the others depend on.
2. **Propose/apply separation** — recalibration as an offline batch job that computes candidate likelihood ratios and diffs them against the current `ScoringProperties` values, requiring human sign-off before deploy, never a live update applied per outcome.
3. **Dampened updates** — a minimum sample size per `RULE_NAME:SEVERITY` bucket before its ratio is allowed to move at all, shrinkage (EMA) toward the current value rather than a raw re-estimate, and a capped maximum delta per cycle.
4. **A golden regression set** — a fixed batch of known-fraud transaction patterns that must still be flagged after any recalibration; block the deploy if recall against that set drops.
5. **Trend monitoring on the ratios themselves**, not just precision/recall — alert if any rule's likelihood ratio is moving monotonically downward across cycles, since that's the earliest observable symptom of the spiral described above.

None of this is implemented. It's being kept as a documented design decision rather than partially built, for the same reason §5 already treats the likelihood ratios themselves as "domain judgment, not fitted" rather than pretending otherwise: building guardrails around a recalibration pipeline that doesn't exist yet is speculative, and the guardrail that matters most — an independent ground-truth source for false negatives — has no real feed to wire into in this environment. Recording it here is meant to make the risk a known, considered trade-off rather than a blind spot if recalibration is ever built.

### Trade-offs

- The Strategy pattern scales comfortably to the current 12 rules. Rules needing conditional branching or dependency graphs (rule A only if rule B passes) would be better served by a dedicated rules engine (e.g. Drools).
- All 12 rules run synchronously on the Kafka consumer thread. `EvaluationContextBuilder`'s data is now an in-memory Interactive Query against the Kafka Streams state store (see above) for every rule that needs the DB at all, with merchant location (Caffeine-cached, eagerly warmed at startup, not DB-per-call) covering the rest — no rule's context fetch issues an unconditional live Postgres query on the hot path anymore. What's left to support a real-time, pre-authorisation decision path is not a data-fetch optimisation but the actual ingestion model: there is still no synchronous request/response entry point into this service (`TransactionConsumer` is fire-and-forget Kafka). See [§11](#11-known-drawbacks--production-considerations).

---

## 6. Persistence Layer — PostgreSQL

### Schema

`transactions` is **range-partitioned by `timestamp` (daily)** with a composite primary key `(id, timestamp)` — Postgres requires the partition key in every unique constraint on a partitioned table. `fraud_assessments` references it via a composite FK `(transaction_id, transaction_timestamp)` rather than a single-column FK. `rule_violations` and `merchant_locations` round out the schema. Flyway manages schema evolution — a single consolidated `V1` migration, since this service hasn't gone live yet and there's no deployed history to preserve; `spring.jpa.hibernate.ddl-auto=validate` means the app refuses to start if the entity model and schema have drifted apart.

### Indexing strategy

```sql
-- VelocityRule, DuplicateRule, and the context builder's recent-transaction query
CREATE INDEX idx_transactions_customer_timestamp ON transactions(customer_id, timestamp DESC);

-- DuplicateRule candidate lookup
CREATE INDEX idx_transactions_duplicate_detection ON transactions(merchant_id, amount, customer_id, timestamp DESC);

-- Composite index (disposition, assessed_at DESC) — one index serves all three
-- disposition-filtered, assessed_at-ordered query paths (flagged/pending-review/passed)
CREATE INDEX idx_assessments_disposition_assessed_at ON fraud_assessments(disposition, assessed_at DESC);

-- Also doubles as the Kafka redelivery idempotency backstop — see §4
ALTER TABLE fraud_assessments ADD CONSTRAINT uq_assessments_transaction_id UNIQUE (transaction_id);
```

### Data lifecycle

`PartitionMaintenanceJob` runs nightly at 02:00: pre-creates the partition for `today + 2 days` and drops the partition for `today − 91 days` (90-day retention). It's the one component explicitly disabled under the `standalone` profile, since the H2 in-memory database used there doesn't support table partitioning.

### Trade-offs

- **Single writer per partition** — the Kafka consumer writes to Postgres sequentially. At very high volume this becomes the throughput bound; mitigation is batch inserts or a wider connection pool, not yet needed at current scale.
- **Read replica** — the query API (all of `TransactionQueryService`) is routed to a separate reader `DataSource` (`DataSourceConfig`, §11), so read-heavy query load no longer contends with the write path's connection pool. A *primary* outage still takes down the write path — a read replica addresses read/write contention and read availability during a write-side degradation, not primary failover; that would need RDS Multi-AZ or equivalent automatic promotion, not provisioned here.

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

### API versioning

Every endpoint lives under `/api/v1/**` — URI versioning, chosen over a header/media-type
scheme (`Accept: application/vnd.fraudengine.v1+json`) for the same reason most public
REST APIs default to it: the version is visible in a curl command, a browser tab, a log
line, and a load balancer routing rule without needing to inspect headers, which matters
more for a forensics/ops tool whose consumers include dashboards and ad-hoc debugging as
much as other services.

There is deliberately no `v2` and no dual-serving of old and new response shapes. This
project has no external consumers with a deployed dependency on a prior contract to
protect — `disposition` replacing the boolean `fraudulent` field (below) is the concrete
example: it was a breaking DTO change shipped straight into `/api/v1/**` with no
migration path, which is the right call *only* because nothing real depends on the old
shape. Were this API to gain actual external consumers, the policy would be: breaking
changes bump the URI prefix (`/api/v2/**`) and the old prefix keeps serving until
consumers migrate off it — not header negotiation, and not silent field renames on a
version that's supposed to be stable. Until then, `v1` is understood to be the single
version under active development, not a frozen contract.

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

Three self-contained environments (`dev`, `load-test`, `prod`), each with its own app instance, Postgres, 3-broker Kafka cluster, and (in `dev`) an exposed Schema Registry/Vault/Prometheus, all on distinct host ports so multiple environments run side by side locally via `docker-compose.*.yml`. The same topology is expressed as per-environment Helm values (`helm/values-*.yaml`) for Kubernetes deployment. (`int`/`qa` existed earlier in the project and were removed outright — they never got real use as distinct environments; `load-test` was renamed from `load` for clarity.)

### Dockerfile

The runtime image is a single-stage build from a pre-built JAR (`docker/Dockerfile`), not the multi-stage Maven build a from-scratch design would default to. The reason is environmental, not architectural: this environment's Confluent Schema Registry Maven repository now requires authentication, so `mvn dependency:go-offline` returns 403 inside a build container with no local cache or injected credentials. In a pipeline with a corporate Nexus/Artifactory mirror or Confluent credentials available as build secrets, the standard multi-stage form applies instead (documented inline in the Dockerfile). The image runs as a non-root user regardless.

### Secrets — Vault

Vault runs in dev-mode (pre-unsealed, root token) for local/lower environments and AppRole authentication (`application-prod.yml`) in production — a scoped, minimum-privilege identity rather than the root token, with `VAULT_ROLE_ID`/`VAULT_SECRET_ID` injected at deploy time. The production Kafka TLS config enforces hostname verification (`ssl.endpoint.identification.algorithm: https`) against certs `scripts/gen-kafka-certs.sh` issues with correct per-broker SANs; the remaining gap is that the CA itself is self-signed rather than issued by a real corporate CA — see [§11](#11-known-drawbacks--production-considerations).

---

## 9. Testing Strategy

### Layer 1: Unit tests (JUnit 5 + Mockito)

Every rule, the rule engine's orchestration logic, the context builder, and the service layer — all mocked dependencies, no Spring context, millisecond-fast. A dedicated effectiveness suite (`FraudEngineEffectivenessTest`) additionally measures the engine as a whole: sensitivity, specificity, precision, and F1 across a curated set of fraud/legitimate scenarios, plus per-rule pattern coverage. It includes a meta-test that reflectively scans `engine.rules` for every `FraudRule` implementation and fails if one isn't wired into the suite — a guard against exactly the kind of drift where a new rule ships without the effectiveness numbers ever accounting for it.

### Layer 2: Integration tests (Testcontainers)

Real Postgres and Kafka containers per test class, full Spring context, mock (in-process) Schema Registry. Covers the full pipeline (`transactions.raw` → consumer → rule engine → persisted assessment → query API), DLT routing after exhausted retries, and ordered concurrent processing for a single customer.

### Layer 3: Load & performance tests (k6)

Four scenarios against the `load-test` environment (`01-baseline`, `02-ramp`, `03-spike`, `04-fraud-rules`), producing real Confluent-Protobuf `TransactionEvent` messages directly to `transactions.raw` via `k6/x/kafka` (the `mostafamoradian/xk6-kafka` image, `docker-compose.load-test.yml`) — the actual production ingestion path, not the standalone/local HTTP stub, which isn't even loaded under `SPRING_PROFILES_ACTIVE=load-test`. Target throughput is derived from a stated estimate for a 24M-customer issuer (~230 TPS daily-average baseline, ~900–1,000 TPS peak-hour, ~2,000–2,500 TPS promotional-event spike) rather than an arbitrary number — full derivation and the per-scenario table are in `load-tests/README.md`, which is kept in sync as the source of truth for tunables (§5's existing convention). Reports p50/p95/p99 assessment-poll latency, Kafka produce error rate, and throughput against thresholds of p95<1500ms / p99<2000ms / error rate<5% (looser for the spike scenario, matching its "no failures, not low latency" goal). These thresholds are appropriate for the current async, post-authorisation pipeline; they are not a proxy for the sub-100ms budget a pre-authorisation path would need. Deliberately not addressed by this test suite: whether `transactions.raw`'s current 6-partition/6-consumer-thread topology (`KafkaConfig.java`) actually sustains the ramp/spike targets — that's left for the tests to reveal, not assumed.

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
| **Post-authorisation only** — no synchronous, blocking decision path exists before a transaction is authorised. The rules themselves are pure in-memory logic and could run in a sub-100ms budget. | The context-fetch side of this is now closed: `recentCustomerTransactions`/`dailySpendTotal`/`CustomerAmountAnomalyRule`'s 90-day baseline are all served by a Kafka Streams state store (`com.fraudengine.streams`, §5) instead of live Postgres queries, and `ReferenceDataCache`'s merchant-location cache is eagerly warmed at startup (`ReferenceDataCache.warmMerchantLocationCache()`) rather than blocking on a lazy cache-miss DB read. `EvaluationContextBuilder.build()` no longer issues an unconditional Postgres query on the hot path for any rule. What remains is the actual architectural gap, unaffected by any of this: there is still no synchronous request/response entry point — `TransactionConsumer` is fire-and-forget Kafka, not a blocking call the payment switch's authorisation flow could wait on. Closing *that* needs a new API surface (gRPC/REST called from inside the switch's own auth decision), an explicit fail-open/fail-closed policy for when this service is unavailable, and a scaling model built for spiky synchronous request concurrency instead of steady consumer-group throughput — a materially larger change than a context-fetch optimisation, not started here. Residual gap from the Streams work itself: the Streams app is an independent consumer group from `TransactionConsumer` on the same topic, so it can lag behind it under restart/load; a read in that window under-counts rather than fails, which is a false-negative risk this doesn't close (see §5's Known Limitation), only makes observable via a lag metric. |
| **No confirmed-fraud/false-positive feedback loop** — the log-odds model's likelihood ratios are domain judgment, not calibrated against real outcomes. | Partially addressed: `AssessmentOutcome` + `PATCH /api/v1/transactions/{id}/outcome` (§7), per-customer behavioural baselining (`CustomerAmountAnomalyRule`, §5), and the three-way `disposition` (`CLEARED`/`PENDING_REVIEW`/`FLAGGED`, §7) are all implemented. Still open: nothing yet *consumes* recorded outcomes to actually recalibrate `ScoringProperties`' likelihood ratios — that analysis/tooling doesn't exist yet, and deliberately so: recalibrating naively off the outcome table risks a false-negative selection-bias spiral (outcomes only exist for reviewed transactions, so misses that were never reviewed can never push a ratio back up) — see §5's "Future work: the selection-bias risk in recalibration" for the mechanism and the guardrails (independent ground-truth source, propose/apply separation, dampened updates, golden regression set, ratio-trend monitoring) required before this is built, not after. The new `transactions.pending-review` Kafka topic/proto message also hasn't been integration-tested against a real broker/Schema Registry (no Docker in the dev sandbox this was built in). |
| **Retry-topic partition reassignment** — a redelivered message isn't guaranteed to land back on its original customer-ordered partition. | Fixed 2026-09-03 (§4). Turned out to be worse than a soft ordering gap: auto-created retry topics default to 1 partition against a publisher that preserves the original partition index, which throws on publish for most traffic rather than degrading gracefully. `numPartitions`/DLT partition count now match `transactions.raw`'s 6, cross-checked by `KafkaConfigTest`. Unverified against a real broker (no Docker). |
| **No read replica** — a Postgres outage takes down both read and write paths. | Addressed at the application layer: `DataSourceConfig` routes every `@Transactional(readOnly = true)` call (all of `TransactionQueryService`, i.e. the entire query API) through a reader `DataSource` via `ReplicationRoutingDataSource` (`AbstractRoutingDataSource` keyed on `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`), separate from the writer the Kafka consumer path uses. `docker-compose.yml`'s `postgres-replica` service stands up real Postgres streaming replication locally (`pg_basebackup`-based, not just a second empty database) for `make dev` to exercise. Still open: this doesn't yet solve the stated problem end-to-end — a *primary* outage still takes down the write path (unchanged, out of scope for a read replica), and prod (`values-prod.yaml`) doesn't yet have `DB_REPLICA_HOST` wired to a real RDS reader endpoint (same externally-supplied-value gap `DB_HOST` already has there). Unverified against a live replication stream in any sandbox here — no Docker daemon reachable — verified instead by `docker compose config` successfully merging/rendering the compose files (structurally correct) and a unit test on the routing logic itself (`ReplicationRoutingDataSourceTest`). |
| **Single-writer consumer per partition** — Postgres write throughput is the eventual ceiling at very high volume. | Batch inserts / wider pool if it becomes the bottleneck; not yet needed. |
| **Fail-fast startup on IDP unavailability** — the app refuses to start if it can't fetch JWKS from the configured issuer. | Intentional (fail fast over serving unauthenticated requests), but worth knowing before a deploy that depends on IDP reachability. |
| **`DeviceFingerprintRule` cannot fire outside the standalone/local demo path.** Found live 2026-09-10: `transaction_event.proto` (the real Kafka wire format) carried no `device_fingerprint` field, so every transaction ingested via `TransactionConsumer` in `prod`/`load-test` always had `deviceFingerprint = null` — the rule's `knownFingerprints` set could never be non-empty, so it could never violate. HIGH severity, likelihood ratio 160.0 (one of the highest-calibrated signals in `ScoringProperties`), fully implemented and tested, silently inert in every real environment. Undocumented until now. | Partially addressed 2026-09-10: added `device_fingerprint` as field 12 (additive, Schema-Registry-compatible) and wired it through `ProtoMapper`, `RecentTransactionRecord`, `CustomerActivityProcessor`, and `EvaluationContextBuilder.toTransaction` — the pipe is ready end-to-end, streaming path included. Still open: `transactions.raw` is published by an external upstream system this repo doesn't own (the authorisation switch/core banking platform, per §3); the rule stays inert in practice until that producer actually starts populating the field. Not something this codebase can finish alone. |
| **Self-signed CA for `prod`'s Kafka TLS** | `scripts/gen-kafka-certs.sh` issues each broker a cert with a SAN matching its advertised hostname, so `ssl.endpoint.identification.algorithm` is set to Kafka's default `https` and hostname verification is actually enforced — not disabled. The remaining gap is narrower than before: the CA itself is self-signed (script-generated), not issued by a real corporate/public CA; swap in certs from Acme Bank's internal CA before production use, same trust-chain concern as any self-managed CA. |
| **No distributed schema-compatibility gate beyond Schema Registry's own enforcement** | Confluent Schema Registry enforces backward/forward compatibility on registration; no additional CI gate on top of it. |

See [docs/future-prospects.md](./docs/future-prospects.md) for features and directions not yet built at all — this table is about gaps in what's already implemented, that document is the broader roadmap (including LLM integration ideas).
