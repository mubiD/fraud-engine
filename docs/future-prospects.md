# Future Prospects — Fraud Rule Engine

Not a roadmap with committed dates, but a list of features and architectural directions this
project could reasonably grow into, organized by theme. Distinct from `DESIGN.md` §11 ("Known
Drawbacks & Production Considerations"), which documents specific gaps in what's already built;
this document is about what isn't built at all yet.

---

## LLM integration

- **Analyst-facing explanation generation.** For a `PENDING_REVIEW`/`FLAGGED` assessment, feed
  the fired `RuleViolation`s plus the relevant `EvaluationContext` slice to an LLM and generate a
  plain-English narrative ("flagged because this customer's spend is 4.2σ above their 90-day
  baseline, and the transaction occurred at 02:14 local time, an hour they've never transacted in
  before"). It's additive on top of `PATCH /outcome`'s workflow: an analyst working the queue today
  has to mentally reconstruct the story from raw rule names and severities.
- **Triage/summarization for the review queue.** Given `GET /transactions/pending-review`, have
  an LLM cluster and summarize the day's queue ("14 of today's 23 pending-review items are
  CARD_CLONING + TIME_OF_DAY combos against 3 merchants, which looks like one coordinated pattern, not
  14 independent cases"). Turns a list into a prioritized worklist.
- **A genuinely new `FraudRule` implementation.** The Strategy pattern (`FraudRule` interface,
  `@Component` auto-registration) is built exactly for this: an `LlmAnomalyRule` that sends
  transaction + context to an LLM (or a cheaper embedding-similarity check) for a semantic read no
  numeric rule catches, e.g. "this category/amount/merchant combination is semantically
  inconsistent with this customer's transaction history," contributing its own
  `RULE_NAME:SEVERITY` entry to `ScoringProperties`' likelihood-ratio table like any other rule.
  Zero changes to `RuleEngine`.
- **Recalibration proposal assistant, not an autonomous recalibrator.** `DESIGN.md` §5 already
  scopes out *why* naive recalibration off `AssessmentOutcome` data is dangerous (the
  reject-inference/selection-bias spiral) and names required guardrails (independent miss-source,
  propose/apply separation, dampened updates, golden regression set). An LLM is a good fit for the
  "propose" half specifically: given a batch of confirmed outcomes, draft candidate
  likelihood-ratio adjustments with cited reasoning for a human to review, never auto-applied.
- **Natural-language query over the read API.** "Show me all flagged wire transfers over
  R10,000 for customers who joined in the last 30 days" → translated via function-calling into the
  existing filtered `GET /transactions/flagged` params. Low effort since the filter surface
  already exists; this is a translation layer, not new backend logic.
- **Synthetic fraud-scenario generation for testing.** `FraudEngineEffectivenessTest`'s scenarios
  are hand-authored. An LLM generating structurally novel adversarial patterns (ones a human
  author didn't think to write) would stress-test the rule catalogue more broadly than the
  current fixed scenario set.

---

## Real-time / pre-authorisation path

The one deliberately out-of-scope item (`DESIGN.md` §11): rules already run in-memory sub-100ms,
but there's still no synchronous request/response entry point: `TransactionConsumer` is
fire-and-forget Kafka. A gRPC or REST endpoint called from inside an authorisation switch's own
decision path, with an explicit fail-open/fail-closed policy and a concurrency model built for
spiky synchronous load instead of steady consumer throughput, is the actual remaining gap. Not a
small change, but the natural "next tier" of this project.

---

## Scoring & data feedback

- Close the loop `DESIGN.md` already scoped: build the outcome→recalibration pipeline with its
  named guardrails once there's a real chargeback/dispute feed to serve as an independent
  ground-truth source for false negatives.
- Per-customer/per-merchant *adaptive* thresholds instead of global config defaults (a merchant
  with naturally high transaction variance shouldn't share `AMOUNT_THRESHOLD`'s flat default with
  a low-variance one).
- Shadow-mode scoring: run a candidate `ScoringProperties` config in parallel against real
  traffic, log what it *would* have produced, compare against the live model before promoting it:
  the safe way to test recalibration changes.
- Graph-based features (shared device fingerprints or nearby geolocations across *different*
  customer IDs) to catch fraud rings, not just single-customer anomalies. A natural extension of
  the Kafka Streams state store, which already tracks per-customer state.

---

## Observability & operations

- Alerting rules (Prometheus Alertmanager) on top of the existing metrics. Right now there's no
  alerting layer at all, just scrape-able counters.
- SLO dashboards for consumer lag, assessment latency, and the `STREAMS`/`POSTGRES` fallback-rate
  metric (`fraud.context.source.total`) that already exists but isn't visualized anywhere.
- A real canary/blue-green deploy path. The CI workflow's deploy job is entirely stubbed right
  now ("no self-hosted runners registered"); worth deciding if that's staying a portfolio artifact
  or becoming real.

---

## Security & compliance

- Vault's DB secrets engine for dynamic, rotating Postgres credentials instead of the current
  static `DB_PASSWORD`.
- A real corporate CA for prod's Kafka TLS, replacing the self-signed one
  `scripts/gen-kafka-certs.sh` issues (`DESIGN.md` §11 already names this as the one remaining gap
  after hostname verification was fixed).
- A data-retention story beyond `PartitionMaintenanceJob`'s hard 90-day drop: archive-then-delete
  to cold storage (S3/Glacier) if this ever needs to support compliance audits or disputes older
  than 90 days.
- Right-to-erasure support for `customer_id`. PII policy currently just means "don't log it," but
  the ID itself still lives in Postgres indefinitely.

---

## API & UX

- A lightweight analyst dashboard (the API + Swagger UI is the whole interface today). Even a
  simple React app driving the existing read endpoints plus `PATCH /outcome` would make the review
  workflow usable by a non-technical analyst.
- Server-push (SSE/WebSocket) for the flagged/pending-review queues instead of polling, a natural
  pairing with the LLM triage-summarization idea above.
- GraphQL as an alternative read surface for ad-hoc dashboard queries, alongside (not replacing)
  the REST API.

---

## Testing & resilience

- Property-based testing (jqwik) for the rule catalogue's numeric edge cases instead of purely
  example-based unit tests.
- Mutation testing (PIT) to check whether the test suite actually catches logic regressions, not
  just exercises code paths.
- Chaos testing: kill a Kafka broker or the Postgres primary mid-load-test and confirm the
  retry/DLT and read-replica-routing behavior holds under real failure, not just unit-tested in
  isolation.
