/**
 * Scenario 2 — Ramp Load
 *
 * Ramps from 10 → 500 virtual users over 5 minutes.
 * Goal: identify the inflection point where latency degrades and
 * whether Kafka absorbs excess load gracefully rather than dropping requests.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { BASE_URL, THRESHOLDS, transactionPayload, pick, CUSTOMERS } from '../config.js';

const errorRate = new Rate('error_rate');

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '1m',  target: 100 },
        { duration: '2m',  target: 300 },
        { duration: '2m',  target: 500 },
      ],
    },
  },
  thresholds: {
    ...THRESHOLDS,
    error_rate: ['rate<0.05'],
  },
};

export default function () {
  const headers = { 'Content-Type': 'application/json' };

  const res = http.post(
    `${BASE_URL}/api/v1/transactions`,
    transactionPayload({ customerId: pick(CUSTOMERS) }),
    { headers }
  );

  const ok = check(res, {
    'status 202': (r) => r.status === 202,
  });

  errorRate.add(!ok);
  sleep(0.5);
}
