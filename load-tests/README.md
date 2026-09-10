# Load Tests — k6

Performance and load tests for the Fraud Rule Engine. Runs independently of the main Maven build.

All four scenarios produce real Confluent-Protobuf `TransactionEvent` messages directly to
`transactions.raw`, the actual production ingestion path (`TransactionConsumer`), not the
standalone/local HTTP stub. That stub isn't even loaded under `SPRING_PROFILES_ACTIVE=load-test`
(`StandaloneTransactionController`'s `@Profile("standalone | local")`), so a Kafka producer is the
only way to generate real traffic against the `load-test` environment.

## Prerequisites

None to install locally: `make k6-run` runs k6 fully containerised, using the
[xk6-kafka](https://github.com/mostafa/xk6-kafka) build (`mostafamoradian/xk6-kafka` on Docker Hub,
`k6/x/kafka` module) instead of stock `grafana/k6`, since producing real Confluent-Protobuf messages
via Schema Registry needs that extension. No local k6 install, and no custom image build needed, since
the extension's Protobuf + Schema Registry support has been fully implemented since v2.1.0.

## Running

Start the `load-test` environment first (brings up the app, Postgres, a 3-broker Kafka cluster,
Schema Registry, InfluxDB, and Grafana; the `k6` service itself stays down until explicitly
triggered):

```bash
make load-test
```

### Run a single scenario

```bash
make k6-run                         # runs 01-baseline
make k6-run SCENARIO=02-ramp
make k6-run SCENARIO=03-spike
make k6-run SCENARIO=04-fraud-rules
make k6-run SCENARIO=01-baseline RATE=500   # override target concurrent load
```

(Equivalent to `docker compose -f docker/docker-compose.yml -f docker/docker-compose.load-test.yml
-p fraud-load-test --profile k6 run --rm k6 run /scripts/scenarios/<name>.js`, if you need to run it
directly.)

### Run all scenarios

```bash
make k6-run-all
```

### View results

Each scenario writes a self-contained HTML report (`k6-reporter`, `lib/reporter.js`) to
`results/<scenario>.html` on the host: open it directly in a browser, no server needed. Live
metrics during a run are also visible in Grafana at `http://localhost:3000` (InfluxDB-backed
dashboard, auto-provisioned).

---

## Capacity numbers

Target throughput for each scenario is derived from an explicit estimate for a 24M-customer card
issuer, not an arbitrary number:

- 24,000,000 customers × ~60% active in a given period × ~1.5 txns/day for an active customer ≈
  **~20M transactions/day**
- Average: 20,000,000 / 86,400s ≈ **~230 TPS** sustained baseline
- Peak-hour multiplier for card-payment traffic (lunch/evening/weekend peaks) is typically 3–5x
  average → **~900–1,000 TPS peak sustained**
- Promotional/event spike (Black-Friday-style), commonly 2–3x peak-hour → **~2,000–2,500 TPS burst**
- Sanity check: Visa's publicly-cited global peak capacity is ~65,000 TPS across *all* issuers
  combined, so a single 24M-customer issuer at ~1,000–2,500 TPS peak is a proportionate slice of that,
  not an inflated number.

These numbers size each scenario below; they are **not** a claim about what this deployment's current
Kafka topology (6 partitions on `transactions.raw`, 6 consumer threads, per `KafkaConfig.java`) can
actually sustain. That's deliberately left for the ramp/spike scenarios to reveal, not assumed
beforehand: partition/consumer-concurrency tuning based on the results is a follow-up, not part of
this test suite.

---

## Scenarios

| File | Purpose | Target throughput | Duration |
|---|---|---|---|
| `01-baseline.js` | Sustained daily-average load + assessment poll | ~230 TPS (constant) | 2 min |
| `02-ramp.js` | Find the real degradation point relative to peak-hour load | ~100 → ~1,200 TPS (ramp) | 5 min |
| `03-spike.js` | Validate Kafka absorbs a Black-Friday-style burst | ~230 → ~2,500 → ~230 TPS | ~4 min |
| `04-fraud-rules.js` | Mixed write + read path at peak-hour rate | ~900 TPS write + 20 VUs read | 3 min |

Every scenario produces to Kafka for every iteration; only a sample of iterations additionally poll
`GET /transactions/{id}/assessment` to measure end-to-end latency (`01-baseline` polls every
iteration since its rate is low enough; `02`/`03`/`04` sample 5–10%, see each script's
`POLL_SAMPLE_RATE`) so the load generator itself doesn't become the bottleneck at higher throughput.

---

## Thresholds (fail if breached)

| Metric | Threshold |
|---|---|
| Assessment poll p95 | < 1500ms |
| Assessment poll p99 | < 2000ms |
| Kafka produce error rate | < 5% (< 1% for the spike scenario) |
| Spike assessment poll p99 | < 5000ms (deliberately looser: the goal is no failures, not low latency, during a burst) |

---

## What Each Scenario Tests

**01-baseline** establishes the real end-to-end (produce → consume → evaluate → persist →
queryable) latency baseline at the estimated daily-average TPS. The poll step validates the async
assessment pipeline completes within the sleep window.

**02-ramp** ramps past the estimated peak-hour rate to find where Kafka consumer lag and
assessment latency actually start to climb, rather than assuming the current 6-partition topology
copes at that rate.

**03-spike** is the hardest test for Kafka absorption. A sudden burst to the estimated
promotional-event rate hits `transactions.raw` directly. A well-configured producer/consumer pair
should queue through it (lag growing, then draining) rather than drop messages or error. Recovery
time is observable in the tail of the run.

**04-fraud-rules** is the most realistic simulation, at the estimated peak-hour rate: 60% clean
traffic, 20% high-value (triggers `AMOUNT_THRESHOLD`), 20% high-risk merchant category (triggers
`HIGH_RISK_MERCHANT_CATEGORY`, which replaces an earlier "unrecognised device" bucket, since
`DEVICE_FINGERPRINT` can only ever fire via the standalone/local HTTP stub; `transaction_event.proto`
carries no `device_fingerprint` field, so it's structurally unreachable over real Kafka ingestion).
The read path runs concurrently, on its own dedicated VUs (`exec: 'readPath'`), to verify the query
API doesn't contend with writes on the DB connection pool.
