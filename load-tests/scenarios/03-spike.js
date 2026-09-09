/**
 * Scenario 3 — Burst / Spike
 *
 * Baseline ~230 TPS (2 min) -> sudden burst to ~2,500 TPS (10s ramp, 30s hold) -> recovery
 * back to ~230 TPS. Override the spike peak with RATE=<n>, e.g.
 * `make k6-run SCENARIO=03-spike RATE=5000` — the baseline before/after scales
 * proportionally. ~2,500 TPS models a Black-Friday-style promotional event for a
 * 24M-customer issuer — roughly 2-3x the ~900-1,000 TPS peak-hour estimate (see
 * ../README.md's "Capacity numbers" section). This is the hardest test for Kafka
 * absorption: a well-configured producer/consumer pair should queue through the burst
 * (consumer lag growing, then draining) rather than drop messages or error.
 *
 * Only a sample of iterations poll for their assessment during the test (see
 * POLL_SAMPLE_RATE) — at 2,500 TPS, polling every iteration would make the load generator
 * itself the bottleneck. The produce step itself still runs for every iteration, so Kafka
 * absorption is fully exercised regardless of the poll sample size.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import {
  BASE_URL,
  newKafkaWriter, newSchemaRegistry, registerTransactionEventSchema,
  customerPool, merchantPool, kafkaTransactionEvent, resolveRate,
  fetchAccessToken, authHeaders,
} from '../config.js';
import { SCHEMA_TYPE_PROTOBUF } from 'k6/x/kafka';

const POLL_SAMPLE_RATE = 0.05;

// RATE overrides the spike's peak/hold target; unset falls back to ~2,500 TPS. The
// baseline before/after scales proportionally to the default 230/2500 ratio.
const SPIKE_TPS = resolveRate(2500);
const BASELINE_TPS = Math.round(SPIKE_TPS * (230 / 2500));
const EXPECTED_TOTAL_TRANSACTIONS = Math.round(
  BASELINE_TPS * 120 +
  ((BASELINE_TPS + SPIKE_TPS) / 2) * 10 +
  SPIKE_TPS * 30 +
  ((SPIKE_TPS + BASELINE_TPS) / 2) * 10 +
  BASELINE_TPS * 60
);
const VUS_PER_TPS = POLL_SAMPLE_RATE * 2.3 + (1 - POLL_SAMPLE_RATE) * 0.05;

const spikeProduceErrorRate = new Rate('spike_kafka_produce_error_rate');
const spikePollDuration     = new Trend('spike_assessment_poll_duration');

const writer = newKafkaWriter();
const schemaRegistry = newSchemaRegistry();
const customers = customerPool(EXPECTED_TOTAL_TRANSACTIONS);
const merchants = merchantPool();

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: BASELINE_TPS,
      timeUnit: '1s',
      preAllocatedVUs: Math.ceil(SPIKE_TPS * VUS_PER_TPS * 0.7),
      maxVUs: Math.ceil(SPIKE_TPS * VUS_PER_TPS * 1.5),
      stages: [
        { duration: '2m',  target: BASELINE_TPS }, // normal baseline
        { duration: '10s', target: SPIKE_TPS    }, // sudden spike
        { duration: '30s', target: SPIKE_TPS    }, // hold spike
        { duration: '10s', target: BASELINE_TPS }, // recover
        { duration: '1m',  target: BASELINE_TPS }, // confirm recovery
      ],
    },
  },
  thresholds: {
    // Spike thresholds are intentionally looser — the goal is no failures, not low latency.
    spike_kafka_produce_error_rate: ['rate<0.01'],
    spike_assessment_poll_duration: ['p(99)<5000'],
  },
};

export function setup() {
  return { schema: registerTransactionEventSchema(schemaRegistry), token: fetchAccessToken() };
}

export default function (data) {
  const event = kafkaTransactionEvent(customers, merchants, {
    amount: (Math.random() * 500 + 10).toFixed(2),
  });

  let produced = true;
  try {
    writer.produce({
      messages: [{
        key: event.customerId,
        value: schemaRegistry.serialize({
          data: event,
          schema: data.schema,
          schemaType: SCHEMA_TYPE_PROTOBUF,
        }),
      }],
    });
  } catch (e) {
    produced = false;
  }
  spikeProduceErrorRate.add(!produced);
  if (!produced) return;

  if (Math.random() < POLL_SAMPLE_RATE) {
    sleep(2);
    const headers = authHeaders(data.token);
    const start = Date.now();
    const res = http.get(
      `${BASE_URL}/api/v1/transactions/${event.transactionId}/assessment`,
      { headers }
    );
    spikePollDuration.add(Date.now() - start);
    check(res, { 'spike: sampled poll status 200 or 404': (r) => r.status === 200 || r.status === 404 });
  }
}

export function teardown() {
  writer.close();
}

export function handleSummary(data) {
  return { '/scripts/results/03-spike.html': htmlReport(data) };
}
