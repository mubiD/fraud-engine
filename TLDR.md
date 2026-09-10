# TL;DR — Fraud Rule Engine

The 2-minute version. For the full picture see [README.md](./README.md) (API reference, running
every environment), [DESIGN.md](./DESIGN.md) (architecture and trade-offs), or
[TesterInfo.md](./TesterInfo.md) (QA reference).

---

## What this is

A backend service that consumes bank transaction events from Kafka, runs each one through a
12-rule fraud detection engine, persists the verdict, and routes it to a downstream topic. Think
"the system a bank's fraud team would build to flag suspicious transactions for review." It's not a
UI, not a card network, just the detection-and-scoring engine in the middle.

## Run it locally

```bash
make dev
```

That's it: one command builds the app, starts Postgres + a 3-broker Kafka cluster, and boots the
service on `http://localhost:8081`. No Kafka install, no manual setup.

```bash
# Submit one transaction and see the assessment inline (dev-only HTTP stub — see below)
curl -X POST http://localhost:8081/api/v1/standalone/submit \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-001","merchantId":"MERCH-001","amount":150.00,"currency":"ZAR","transactionType":"CARD_PRESENT"}'

# Or generate a batch of random transactions through the rule engine
make stream ENV=dev COUNT=500

# Tear down
make stop dev
```

Prerequisites: Docker, plus a local JDK 21 + Maven on `PATH`. Full detail, other environments
(`load-test`, `prod`), and the full API reference: [README.md](./README.md).

## The design thinking, briefly

- **Rules are pluggable, not hardcoded.** Each rule is a `FraudRule` implementation
  (`@Component`, Strategy pattern), so adding one means writing the class, not touching the engine.
- **Scoring is probabilistic, not a point total.** Each fired rule contributes a calibrated
  likelihood ratio; probabilities combine in log-odds space and convert back via sigmoid. This
  deliberately avoids the failure mode of a flat point sum, where "one strong signal" and "three
  weak, correlated ones" produce the same score.
- **Verdicts are three-way, not binary.** `CLEARED` / `PENDING_REVIEW` / `FLAGGED`: two weak
  signals together land in a reviewable middle band instead of being silently treated the same as
  a clean transaction.
- **Context is pre-fetched once per transaction**, not queried per-rule, and increasingly served
  from an in-memory Kafka Streams state store rather than a live Postgres query at all, closing
  most of the latency budget a real-time path would need.
- **The DB write and the Kafka publish are one atomic operation** (`ChainedKafkaTransactionManager`),
  so there's no scenario where a transaction is scored but the outcome is lost, or published without being
  persisted.
- **The likelihood ratios are domain judgment, not fitted values.** There's a real feedback
  mechanism (`PATCH /transactions/{id}/outcome`) for recording ground truth, but nothing yet
  closes the loop back into the scoring model. That's deliberate, since doing that naively has a real
  bias trap (see `DESIGN.md` §5).

## What this deliberately is

- A **post-authorisation, forensics-style** fraud detection engine: it evaluates a transaction
  *after* it has already happened.
- An **asynchronous, Kafka-driven pipeline**: durable, decoupled from producer throughput,
  horizontally scalable by partition.
- An **extensible rule engine** where new signals plug in without touching existing code.
- A **read-heavy query API** (customer/merchant risk profiles, flagged/pending-review/passed
  feeds, fraud stats) with exactly one deliberate write endpoint for analyst feedback.
- Built with **production-realistic infrastructure** (Vault-managed secrets, TLS, JWT auth,
  exactly-once Kafka+DB semantics, a Postgres read replica, Kafka Streams for hot-path state),
  not just enough to make a demo run.

## What this deliberately is not

- **Not a real-time / pre-authorisation gate.** It cannot block a transaction before it clears.
  There's no synchronous decision path into an authorisation switch. The rules themselves are fast
  enough for that; the ingestion model isn't built for it (see
  [docs/future-prospects.md](./docs/future-prospects.md)).
- **Not a calibrated ML model.** The scoring weights are reasoned starting points, not values
  fitted to labelled fraud data, and there's no confirmed-fraud/false-positive feedback loop closing
  the loop yet.
- **Not a case-management tool or analyst UI.** It's an API. Swagger is the only interface;
  working a review queue means calling endpoints, not clicking through a dashboard.
- **Not multi-tenant or white-label.** It's built for one issuer's transaction stream, not as a
  platform serving multiple banks with isolated configs.
- **Not hardened for a real production rollout as-is.** `prod`'s Kafka TLS uses a self-signed CA,
  the local IDP is a mock OIDC server standing in for the real one, and the deploy pipeline's
  actual infrastructure steps are stubbed (portfolio/showcase constraints, not oversights: see
  `DESIGN.md` §11).
