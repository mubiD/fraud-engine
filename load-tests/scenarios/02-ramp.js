/**
 * Scenario 2 — Ramp Load
 *
 * Ramps ~100 -> ~1,200 TPS (override the peak with RATE=<n>, e.g.
 * `make k6-run SCENARIO=02-ramp RATE=2000`) over 5 minutes via real Kafka production to
 * transactions.raw. The ~1,200 TPS default is above the ~900-1,000 TPS "typical peak hour"
 * estimate for a 24M-customer issuer (see ../README.md's "Capacity numbers" section) —
 * deliberately overshoots the expected peak so the test finds the actual degradation point
 * (Kafka consumer lag growing, assessment latency climbing) relative to that target, not just
 * confirms it copes at exactly the estimated number.
 *
 * Only a sample of iterations poll for their assessment (see POLL_SAMPLE_RATE below) — at
 * 1,200 TPS, holding every iteration open for a multi-second poll would make the load
 * generator itself the bottleneck rather than the system under test. The sample is large
 * enough to be a representative latency trend without that cost.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import {
  BASE_URL, THRESHOLDS,
  newKafkaWriter, newSchemaRegistry, registerTransactionEventSchema,
  customerPool, merchantPool, kafkaTransactionEvent, resolveRate,
  fetchAccessToken, authHeaders,
} from '../config.js';
import { SCHEMA_TYPE_PROTOBUF } from 'k6/x/kafka';

const POLL_SAMPLE_RATE = 0.1;

// RATE overrides the ramp's peak (final-stage) target (make k6-run SCENARIO=02-ramp
// RATE=2000); unset falls back to ~1,200 TPS. Intermediate stages and the start rate scale
// proportionally to the same ratios as the ~100->400->800->1,200 default profile, so an
// override still ramps through comparable relative steps, not a flat jump to the peak.
const PEAK_TPS = resolveRate(1200);
const START_TPS = Math.round(PEAK_TPS * (100 / 1200));
const STAGE1_TPS = Math.round(PEAK_TPS * (400 / 1200));
const STAGE2_TPS = Math.round(PEAK_TPS * (800 / 1200));
// Trapezoid estimate of total transactions across the ramp stages, for customer-pool sizing.
const EXPECTED_TOTAL_TRANSACTIONS = Math.round(
  ((START_TPS + STAGE1_TPS) / 2) * 60 +
  ((STAGE1_TPS + STAGE2_TPS) / 2) * 120 +
  ((STAGE2_TPS + PEAK_TPS) / 2) * 120
);
// Only a sample of iterations poll — at peak rate, holding every iteration open for a
// multi-second poll would make the load generator itself the bottleneck. VU capacity below
// accounts for the sampled-poll fraction plus near-instant produce-only iterations.
const VUS_PER_TPS = POLL_SAMPLE_RATE * 2.3 + (1 - POLL_SAMPLE_RATE) * 0.05;

const produceErrorRate  = new Rate('kafka_produce_error_rate');
const assessmentLatency = new Trend('assessment_poll_duration');

const writer = newKafkaWriter();
const schemaRegistry = newSchemaRegistry();
const customers = customerPool(EXPECTED_TOTAL_TRANSACTIONS);
const merchants = merchantPool();

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: START_TPS,
      timeUnit: '1s',
      preAllocatedVUs: Math.ceil(PEAK_TPS * VUS_PER_TPS * 0.7),
      maxVUs: Math.ceil(PEAK_TPS * VUS_PER_TPS * 1.5),
      stages: [
        { duration: '1m', target: STAGE1_TPS },
        { duration: '2m', target: STAGE2_TPS },
        { duration: '2m', target: PEAK_TPS   },
      ],
    },
  },
  thresholds: {
    ...THRESHOLDS,
    kafka_produce_error_rate: ['rate<0.05'],
  },
};

export function setup() {
  return { schema: registerTransactionEventSchema(schemaRegistry), token: fetchAccessToken() };
}

export default function (data) {
  const event = kafkaTransactionEvent(customers, merchants);

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
  produceErrorRate.add(!produced);
  if (!produced) return;

  if (Math.random() < POLL_SAMPLE_RATE) {
    sleep(2);
    const headers = authHeaders(data.token);
    const start = Date.now();
    const res = http.get(
      `${BASE_URL}/api/v1/transactions/${event.transactionId}/assessment`,
      { headers }
    );
    assessmentLatency.add(Date.now() - start);
    check(res, { 'sampled poll: status 200 or 404': (r) => r.status === 200 || r.status === 404 });
  }
}

export function teardown() {
  writer.close();
}

export function handleSummary(data) {
  return { '/scripts/results/02-ramp.html': htmlReport(data) };
}
