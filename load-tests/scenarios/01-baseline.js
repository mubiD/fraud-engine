/**
 * Scenario 1 — Baseline Throughput
 *
 * Sustained ~230 TPS for 2 minutes — the daily-average load estimate for a 24M-customer
 * issuer (24M customers x ~60% active x ~1.5 txns/day / 86,400s ≈ 230 TPS; see
 * ../README.md's "Capacity numbers" section for the full derivation). Produces real
 * Confluent-Protobuf TransactionEvent messages to transactions.raw — the actual production
 * ingestion path (TransactionConsumer), not the standalone/local HTTP stub, which isn't even
 * loaded under SPRING_PROFILES_ACTIVE=load (StandaloneTransactionController's @Profile).
 * Each iteration produces a transaction then polls for its assessment, establishing the
 * real end-to-end (produce -> consume -> evaluate -> persist -> queryable) latency baseline.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import {
  BASE_URL, THRESHOLDS, RAW_TOPIC,
  newKafkaWriter, newSchemaRegistry, registerTransactionEventSchema,
  customerPool, merchantPool, kafkaTransactionEvent, resolveRate,
  fetchAccessToken, authHeaders,
} from '../config.js';
import { SCHEMA_TYPE_PROTOBUF } from 'k6/x/kafka';

// RATE overrides the target concurrent load (make k6-run SCENARIO=01-baseline RATE=500);
// unset falls back to the ~230 TPS daily-average estimate for a 24M-customer issuer.
const TARGET_TPS = resolveRate(230);
const DURATION_S = 120;
// Every iteration here polls (sleep(2) + a real HTTP round-trip) — held for ~2.3s — so VU
// capacity must scale with TARGET_TPS, not a number sized for one specific rate. Undersizing
// this caused real "Insufficient VUs" warnings and dropped iterations when first run at 230.
const VUS_PER_TPS = 2.3;

const assessmentLatency = new Trend('assessment_poll_duration');
const assessmentsFound  = new Counter('assessments_found');
const produceErrorRate  = new Rate('kafka_produce_error_rate');

const writer = newKafkaWriter();
const schemaRegistry = newSchemaRegistry();
const customers = customerPool(TARGET_TPS * DURATION_S);
const merchants = merchantPool();

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: TARGET_TPS,
      timeUnit: '1s',
      duration: `${DURATION_S}s`,
      preAllocatedVUs: Math.ceil(TARGET_TPS * VUS_PER_TPS * 0.7),
      maxVUs: Math.ceil(TARGET_TPS * VUS_PER_TPS * 1.5),
    },
  },
  thresholds: {
    ...THRESHOLDS,
    kafka_produce_error_rate: ['rate<0.01'],
  },
};

export function setup() {
  const schema = registerTransactionEventSchema(schemaRegistry);
  const token = fetchAccessToken();
  return { schema, token };
}

export default function (data) {
  const event = kafkaTransactionEvent(customers, merchants);

  let produced = true;
  try {
    writer.produce({
      messages: [{
        key: event.customerId, // preserves transactions.raw's customer-partitioned ordering
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

  // Give the async pipeline (consume -> evaluate -> persist) time to land before polling.
  sleep(2);

  const headers = authHeaders(data.token);
  const start = Date.now();
  const assessRes = http.get(
    `${BASE_URL}/api/v1/transactions/${event.transactionId}/assessment`,
    { headers }
  );
  assessmentLatency.add(Date.now() - start);

  check(assessRes, {
    'assessment: status 200 or 404': (r) => r.status === 200 || r.status === 404,
  });

  if (assessRes.status === 200) {
    assessmentsFound.add(1);
    check(assessRes, {
      'assessment: has riskScore': (r) => r.json('riskScore') !== undefined,
    });
  }
}

export function teardown() {
  writer.close();
}

export function handleSummary(data) {
  return { '/scripts/results/01-baseline.html': htmlReport(data) };
}
