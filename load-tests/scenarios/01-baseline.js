/**
 * Scenario 01 — Ingestion baseline
 *
 * Steady low concurrency against the submit endpoint. Establishes p50/p95/p99
 * latency as a reference point for all other scenarios. No assertions beyond
 * error rate and p95 — this is the ruler, not a stress test.
 *
 * Usage:
 *   make load-test scenario=01
 *   make load-test scenario=01 vus=20 duration=5m
 */
import { sleep } from 'k6';
import { submitTransaction, checkOk } from '../lib/helpers.js';

const VUS      = __ENV.VUS      ? parseInt(__ENV.VUS)  : 10;
const DURATION = __ENV.DURATION || '2m';

export const options = {
  vus:      VUS,
  duration: DURATION,
  thresholds: {
    http_req_failed:   ['rate<0.01'],   // <1% errors
    http_req_duration: ['p(95)<800'],   // p95 under 800ms
  },
};

export default function () {
  checkOk(submitTransaction());
  sleep(1);
}
