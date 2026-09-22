/**
 * Scenario 04 — Analyst workflow (mixed read/write)
 *
 * Simulates the real usage pattern: fraud analysts querying while new
 * transactions are being ingested. Traffic is weighted to reflect a typical
 * analyst session:
 *
 *   30% — submit new transactions (ingestion)
 *   20% — query flagged transactions (primary analyst view)
 *   15% — drill into a specific transaction assessment
 *   15% — check a customer's risk summary
 *   10% — check a merchant's flagged transactions
 *    5% — fraud summary stats (dashboard refresh)
 *    5% — record an outcome on a flagged transaction
 *
 * setup() seeds 100 transactions via the stream endpoint so read queries
 * return meaningful results from the start.
 *
 * Usage:
 *   make load-test scenario=04
 *   make load-test scenario=04 vus=20 duration=5m
 */
import http from 'k6/http';
import { sleep } from 'k6';
import {
  BASE_URL, CUSTOMERS, MERCHANTS,
  submitTransaction, submitHighRiskTransaction,
  getFlagged, getAssessment, getCustomerRisk,
  getMerchantFlagged, getFraudSummary,
  patchOutcome,
  checkOk, checkOkOrConflict,
  randomItem,
} from '../lib/helpers.js';

const VUS      = __ENV.VUS      ? parseInt(__ENV.VUS)  : 15;
const DURATION = __ENV.DURATION || '4m';

export const options = {
  vus:      VUS,
  duration: DURATION,
  thresholds: {
    http_req_failed:               ['rate<0.02'],
    'http_req_duration{type:read}': ['p(95)<600'],
    'http_req_duration{type:write}':['p(95)<1000'],
  },
};

// Seed the DB once before VUs start so read queries return results
export function setup() {
  http.post(`${BASE_URL}/api/v1/standalone/stream?count=100`);

  // Submit a high-risk transaction and capture its transactionId for the
  // outcome-patch slice of this scenario
  const res = submitHighRiskTransaction('CUST-001');
  if (res.status === 200) {
    const body = JSON.parse(res.body);
    return { flaggedTransactionId: body.transactionId };
  }
  return { flaggedTransactionId: null };
}

export default function (data) {
  const r = Math.random();

  if (r < 0.30) {
    // Ingestion slice
    checkOk(submitTransaction({ tags: { type: 'write' } }));

  } else if (r < 0.50) {
    // Analyst primary view — flagged queue
    const res = getFlagged({ pageSize: 20 });
    res.tag('type', 'read');
    checkOk(res);

  } else if (r < 0.65) {
    // Drill into a specific assessment — use the seeded transaction if available,
    // otherwise fall back to a fresh submit to get a valid ID
    if (data.flaggedTransactionId) {
      const res = getAssessment(data.flaggedTransactionId);
      res.tag('type', 'read');
      checkOk(res);
    } else {
      checkOk(submitTransaction());
    }

  } else if (r < 0.80) {
    // Customer risk profile
    const res = getCustomerRisk(randomItem(CUSTOMERS));
    res.tag('type', 'read');
    checkOk(res);

  } else if (r < 0.90) {
    // Merchant flagged view
    const res = getMerchantFlagged(randomItem(MERCHANTS));
    res.tag('type', 'read');
    checkOk(res);

  } else if (r < 0.95) {
    // Fraud summary dashboard
    const res = getFraudSummary();
    res.tag('type', 'read');
    checkOk(res);

  } else {
    // Analyst resolves an outcome — multiple VUs may hit the same ID,
    // so 409 (already resolved) is an expected and valid response
    if (data.flaggedTransactionId) {
      const res = patchOutcome(data.flaggedTransactionId, 'CONFIRMED_FRAUD');
      res.tag('type', 'write');
      checkOkOrConflict(res);
    }
  }

  sleep(0.5);
}
