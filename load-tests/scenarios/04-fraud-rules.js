/**
 * Scenario 4 — Fraud Rule Trigger Mix
 *
 * Realistic mixed traffic at ~900 TPS — the ~900-1,000 TPS "typical peak hour" estimate for
 * a 24M-customer issuer (see ../README.md's "Capacity numbers" section) — via real Kafka
 * production to transactions.raw: 60% clean, 20% high-value (triggers AMOUNT_THRESHOLD),
 * 20% high-risk merchant category (triggers HIGH_RISK_MERCHANT_CATEGORY).
 *
 * The high-risk-category bucket replaces this scenario's earlier "unrecognised device"
 * bucket: DEVICE_FINGERPRINT can only ever fire via the standalone/local HTTP stub —
 * transaction_event.proto (the real production wire format this scenario now produces)
 * carries no device_fingerprint field at all, so that rule is structurally unreachable over
 * real Kafka ingestion regardless of load (see DESIGN.md §5's rule catalogue notes). A
 * category-based trigger is reachable through the real schema and demonstrates the same
 * "corroborating weak signal" scoring behaviour.
 *
 * Also exercises the read path concurrently: listing fraud flags and querying by rule.
 * Validates that the read API scales independently of the write path — unaffected by this
 * scenario's ingestion-path change, since it was always HTTP against the query API.
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import {
  BASE_URL, THRESHOLDS,
  newKafkaWriter, newSchemaRegistry, registerTransactionEventSchema,
  customerPool, merchantPool, kafkaTransactionEvent, resolveRate,
  fetchAccessToken, authHeaders,
} from '../config.js';
import { SCHEMA_TYPE_PROTOBUF } from 'k6/x/kafka';

const POLL_SAMPLE_RATE = 0.1;
// RATE overrides mixed_write's target (make k6-run SCENARIO=04-fraud-rules RATE=1500);
// unset falls back to ~900 TPS, the peak-hour estimate.
const WRITE_TPS = resolveRate(900);
const EXPECTED_TOTAL_TRANSACTIONS = WRITE_TPS * 180; // mixed_write scenario: 3 minutes
const VUS_PER_TPS = POLL_SAMPLE_RATE * 2.3 + (1 - POLL_SAMPLE_RATE) * 0.05;

const flaggedCount      = new Counter('flagged_transactions_returned');
const produceErrorRate  = new Rate('kafka_produce_error_rate');
const assessmentLatency = new Trend('assessment_poll_duration');

const writer = newKafkaWriter();
const schemaRegistry = newSchemaRegistry();
const customers = customerPool(EXPECTED_TOTAL_TRANSACTIONS);
const merchants = merchantPool();

export const options = {
  scenarios: {
    mixed_write: {
      executor: 'constant-arrival-rate',
      rate: WRITE_TPS,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: Math.ceil(WRITE_TPS * VUS_PER_TPS * 0.7),
      maxVUs: Math.ceil(WRITE_TPS * VUS_PER_TPS * 1.5),
      exec: 'writePath',
    },
    read_path: {
      executor:   'constant-vus',
      vus:        20,
      duration:   '3m',
      startTime:  '30s',
      exec: 'readPath',
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

// Explicit exec: 'writePath'/'readPath' above — without it, k6 routes every scenario to the
// same default() export, which would mean read_path's 20 VUs also generate write traffic
// (and vice versa), silently inflating mixed_write's actual rate past the intended 900 TPS.
// The original version of this file had exactly that bug (both groups in one unrouted
// default()) — fixed here since it directly affects whether the target throughput is real.
export function writePath(data) {
  const roll = Math.random();

  group('write path', () => {
    let overrides;

    if (roll < 0.6) {
      overrides = {}; // 60%: normal clean transaction
    } else if (roll < 0.8) {
      // 20%: high-value — triggers AmountThresholdRule
      overrides = { amount: (Math.random() * 10000 + 5001).toFixed(2) };
    } else {
      // 20%: high-risk merchant category — triggers HighRiskMerchantCategoryRule
      overrides = { category: 'CRYPTO', amount: (Math.random() * 200 + 10).toFixed(2) };
    }

    const event = kafkaTransactionEvent(customers, merchants, overrides);

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

    if (produced && Math.random() < POLL_SAMPLE_RATE) {
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
  });
}

export function readPath(data) {
  group('read path', () => {
    const headers = authHeaders(data.token);

    const listRes = http.get(`${BASE_URL}/api/v1/transactions/flagged?pageSize=20`, { headers });
    check(listRes, { 'list flags: 200': (r) => r.status === 200 });

    if (listRes.status === 200) {
      const body = listRes.json('data') || [];
      flaggedCount.add(body.length);
    }

    sleep(0.3);

    const ruleRes = http.get(
      `${BASE_URL}/api/v1/transactions/flagged?ruleViolated=AMOUNT_THRESHOLD&pageSize=10`,
      { headers }
    );
    check(ruleRes, { 'filter by rule: 200': (r) => r.status === 200 });

    sleep(0.2);
  });
}

export function teardown() {
  writer.close();
}

export function handleSummary(data) {
  return { '/scripts/results/04-fraud-rules.html': htmlReport(data) };
}
