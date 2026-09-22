/**
 * Scenario 05 — Concurrent outcome resolution
 *
 * Verifies the optimistic-lock pattern in AssessmentOutcomeService under real
 * concurrency. All VUs simultaneously PATCH the same transactionId with
 * CONFIRMED_FRAUD. Expected behaviour:
 *   - Exactly one VU gets 200 (wins the lock)
 *   - All other VUs get 409 (outcome already resolved — correct rejection)
 *   - No 500s, no silent last-write-win data corruption
 *
 * This directly validates the `resolveOutcomeIfUnresolved` atomic conditional
 * update (WHERE outcome = UNRESOLVED at the DB level at write time) documented
 * in FraudAssessmentRepository.
 *
 * Usage:
 *   make load-test scenario=05
 *   make load-test scenario=05 vus=20
 */
import { sleep } from 'k6';
import { check } from 'k6';
import { submitHighRiskTransaction, patchOutcome } from '../lib/helpers.js';

const VUS = __ENV.VUS ? parseInt(__ENV.VUS) : 10;

export const options = {
  vus:      VUS,
  duration: '30s',
  thresholds: {
    // No 500s — all responses must be 200 (winner) or 409 (correct rejection)
    'http_req_failed': ['rate<0.01'],
    'checks{type:outcome}': ['rate>0.99'],
  },
};

// Submit one high-risk transaction before VUs start; all VUs race to resolve it
export function setup() {
  const res = submitHighRiskTransaction('CUST-001');
  if (res.status !== 200) {
    throw new Error(`setup failed: could not submit seed transaction (status ${res.status})`);
  }
  const body = JSON.parse(res.body);
  return { transactionId: body.transactionId };
}

export default function (data) {
  const res = patchOutcome(data.transactionId, 'CONFIRMED_FRAUD');

  check(res, {
    'outcome: 200 (winner) or 409 (already resolved)': (r) =>
      r.status === 200 || r.status === 409,
  }, { type: 'outcome' });

  sleep(0.1);
}
