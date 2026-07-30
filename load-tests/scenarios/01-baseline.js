/**
 * Scenario 1 — Baseline Throughput
 *
 * 100 concurrent users, steady state for 2 minutes.
 * Establishes the normal TPS and p99 latency baseline.
 * Each VU submits a transaction then polls for its assessment.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import { BASE_URL, THRESHOLDS, transactionPayload } from '../config.js';

const assessmentLatency = new Trend('assessment_poll_duration');
const assessmentsFound  = new Counter('assessments_found');

export const options = {
  scenarios: {
    baseline: {
      executor:          'constant-vus',
      vus:               100,
      duration:          '2m',
    },
  },
  thresholds: THRESHOLDS,
};

export default function () {
  const headers = { 'Content-Type': 'application/json' };

  // Submit transaction
  const submitRes = http.post(
    `${BASE_URL}/api/v1/standalone`,
    transactionPayload(),
    { headers }
  );

  check(submitRes, {
    'submit: status 202':         (r) => r.status === 202,
    'submit: has transactionId':  (r) => r.json('transactionId') !== undefined,
  });

  if (submitRes.status !== 202) return;

  const transactionId = submitRes.json('transactionId');

  sleep(2);

  // Poll for assessment
  const start = Date.now();
  const assessRes = http.get(
    `${BASE_URL}/api/v1/transactions/${transactionId}/assessment`,
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

  sleep(1);
}

export function handleSummary(data) {
  return { '/scripts/results/01-baseline.html': htmlReport(data) };
}
