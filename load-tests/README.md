# Load Tests

k6 load tests against the fraud engine's HTTP API. Results stream to InfluxDB and are visualised in Grafana.

**Requires:** `make dev` must already be running.

---

## Quick start

```bash
make load-test                          # scenario 01, defaults
make load-test scenario=02              # ramp scenario
make load-test scenario=04 vus=20       # analyst workflow, 20 VUs
make load-test scenario=03 vus=40 duration=6m
```

Open Grafana at **http://localhost:3000** while a test is running to see live results.

---

## Scenarios

| # | Name | Default VUs | Default duration | What it tests |
|---|------|-------------|------------------|---------------|
| 01 | Ingestion baseline | 10 | 2m | p50/p95/p99 at steady low load — reference point |
| 02 | Ingestion ramp | 5 → 40 | 6m | Finds the saturation point and rate-limiter threshold |
| 03 | Month-end spike | 10 → 30 → 10 | 4m | Burst recovery — does latency return to baseline after spike? |
| 04 | Analyst workflow | 15 | 4m | Mixed read/write simulating real analyst usage across all endpoints |
| 05 | Concurrent outcomes | 10 | 30s | Validates optimistic-lock on `PATCH /transactions/{id}/outcome` |

---

## Configurable parameters

| Parameter | Default | Example |
|-----------|---------|---------|
| `scenario` | `01` | `scenario=04` |
| `vus` | scenario default | `vus=30` |
| `duration` | scenario default | `duration=5m` |

`vus` and `duration` override the scenario's built-in defaults. For scenario 02 (ramp), `vus` sets the *peak* VU count; for scenario 03 (spike), it sets the *peak burst* count.

---

## Infrastructure

InfluxDB and Grafana start automatically on `make load-test` and remain running until `make stop`.

| Service | URL |
|---------|-----|
| Grafana | http://localhost:3000 |
| InfluxDB | http://localhost:8086 |

The Grafana dashboard (`k6 — Fraud Engine Load Tests`) is provisioned automatically. No login required.

---

## Interpreting results

**Scenario 02 (ramp):** watch for the point where p95 climbs past 800ms and 429s start appearing — that's the rate limiter kicking in. On a single Docker Desktop machine this typically happens around 15–25 VUs; that's expected, not a failure.

**Scenario 03 (spike):** the key metric is how long after the burst ends before p95 returns to baseline. A healthy system recovers within 30–60s.

**Scenario 05 (concurrent outcomes):** every response must be either 200 or 409. A 500 here means the optimistic lock isn't working correctly.

---

## Context

These tests simulate load from a ~24M client base:

- **Baseline (~200 TPS):** ~30 VUs at 150ms avg response time (Little's Law: N = λ × W)
- **Business-hours peak (~400 TPS):** ~60 VUs
- **Month-end spike (~800 TPS):** ~160 VUs

The demo environment saturates well before production-scale targets — the saturation point and recovery characteristics are the finding, not the raw throughput number.
