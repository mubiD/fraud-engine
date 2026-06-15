/**
 * Scenario 4 — Fraud Rule Trigger Mix
 *
 * Simulates realistic traffic: a mix of clean transactions, high-value
 * transactions (triggers AmountThresholdRule), and blacklisted merchant
 * transactions (triggers BlacklistedMerchantRule).
 *
 * Also exercises the read path: listing fraud flags and querying by rule.
 * Validates that the read API scales independently of the write path.
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, transactionPayload, pick, CUSTOMERS, MERCHANTS } from '../config.js';

const flaggedCount = new Counter('flagged_transactions_returned');

export const options = {
  scenarios: {
    mixed_write: {
      executor: 'constant-vus',
      vus:      80,
      duration: '3m',
    },
    read_path: {
      executor:   'constant-vus',
      vus:        20,
      duration:   '3m',
      startTime:  '30s',
    },
  },
  thresholds: THRESHOLDS,
};

export default function () {
  const headers = { 'Content-Type': 'application/json' };
  const roll = Math.random();

  group('write path', () => {
    let payload;

    if (roll < 0.6) {
      // 60%: normal clean transaction
      payload = transactionPayload({ customerId: pick(CUSTOMERS) });
    } else if (roll < 0.8) {
      // 20%: high-value — triggers AmountThresholdRule
      payload = transactionPayload({
        customerId: pick(CUSTOMERS),
        amount: (Math.random() * 10000 + 5001).toFixed(2),
      });
    } else {
      // 20%: blacklisted merchant — triggers BlacklistedMerchantRule
      payload = transactionPayload({
        customerId: pick(CUSTOMERS),
        merchantId: pick(MERCHANTS.blacklisted),
        amount: (Math.random() * 200 + 10).toFixed(2),
      });
    }

    const res = http.post(`${BASE_URL}/api/v1/transactions`, payload, { headers });
    check(res, { 'submit: 202': (r) => r.status === 202 });
    sleep(0.5);
  });

  group('read path', () => {
    // List all fraud flags
    const listRes = http.get(`${BASE_URL}/api/v1/fraud-flags?pageSize=20`, { headers });
    check(listRes, { 'list flags: 200': (r) => r.status === 200 });

    if (listRes.status === 200) {
      const data = listRes.json('data') || [];
      flaggedCount.add(data.length);
    }

    sleep(0.3);

    // Filter by specific rule
    const ruleRes = http.get(
      `${BASE_URL}/api/v1/fraud-flags?ruleViolated=AMOUNT_THRESHOLD&pageSize=10`,
      { headers }
    );
    check(ruleRes, { 'filter by rule: 200': (r) => r.status === 200 });

    sleep(0.2);
  });
}
