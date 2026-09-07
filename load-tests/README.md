# Load Tests — k6

Performance and load tests for the Fraud Rule Engine. Runs independently of the main Maven build.

## Prerequisites

Install k6: https://k6.io/docs/get-started/installation/

```bash
# macOS
brew install k6
```

## Running

Ensure the application is running first:

```bash
# From the project root
docker compose up -d
```

### Run a single scenario

```bash
cd load-tests

k6 run scenarios/01-baseline.js
k6 run scenarios/02-ramp.js
k6 run scenarios/03-spike.js
k6 run scenarios/04-fraud-rules.js
```

### Run all scenarios

```bash
cd load-tests
chmod +x run-all.sh
./run-all.sh

# Against a remote host
BASE_URL=http://your-host:8080 ./run-all.sh
```

### Export results to JSON

```bash
k6 run --out json=results.json scenarios/01-baseline.js
```

---

## Scenarios

| File | Purpose | Users | Duration |
|---|---|---|---|
| `01-baseline.js` | Steady-state throughput + assessment poll | 100 | 2 min |
| `02-ramp.js` | Find degradation point under increasing load | 10 → 500 | 5 min |
| `03-spike.js` | Validate Kafka absorbs sudden burst | 50 → 500 → 50 | ~4 min |
| `04-fraud-rules.js` | Mixed write + read path concurrently | 100 (80W+20R) | 3 min |

---

## Thresholds (fail if breached)

| Metric | Threshold |
|---|---|
| p95 response time | < 1500ms |
| p99 response time | < 2000ms |
| Error rate | < 5% |
| Spike error rate (scenario 3) | < 1% |

---

## What Each Scenario Tests

**01-baseline** — establishes your TPS ceiling and p99 latency under comfortable load. The poll step validates the async assessment pipeline completes within the sleep window.

**02-ramp** — progressively increases users until the system strains. The Kafka consumer queue depth will grow during high load — the API should continue accepting at 202 while the engine catches up.

**03-spike** — the hardest test for Kafka absorption. A sudden 10x traffic spike hits the inbound API. A well-configured Kafka producer should handle this without connection errors or dropped messages. Recovery time is observable in the tail of the run.

**04-fraud-rules** — the most realistic simulation. 60% clean traffic, 20% high-value (triggers `AMOUNT_THRESHOLD`), 20% unrecognised device (triggers `DEVICE_FINGERPRINT`). The read path runs concurrently to verify the query API doesn't contend with writes on the DB connection pool.
