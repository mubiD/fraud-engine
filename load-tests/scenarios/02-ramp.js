/**
 * Scenario 02 — Ingestion ramp
 *
 * Linearly increases VUs from 5 to 40 over 6 minutes. Finds the point where:
 *   - Resilience4j's rate limiter starts returning 429s
 *   - p95 latency climbs above the 800ms threshold
 *   - Error rate spikes
 *
 * The saturation point and recovery behaviour are the findings here —
 * on a single Docker Desktop machine this will saturate well before 40 VUs,
 * which is expected and informative.
 *
 * Usage:
 *   make load-test scenario=02
 *   make load-test scenario=02 vus=60 duration=8m
 */
import { sleep } from 'k6';
import { submitTransaction, checkOk } from '../lib/helpers.js';

const MAX_VUS  = __ENV.VUS      ? parseInt(__ENV.VUS)  : 40;
const DURATION = __ENV.DURATION || '6m';

export const options = {
  stages: [
    { duration: DURATION, target: MAX_VUS },
  ],
  thresholds: {
    http_req_failed:   ['rate<0.05'],   // allow up to 5% errors during ramp
    http_req_duration: ['p(95)<2000'],  // generous ceiling — we expect degradation
  },
};

export default function () {
  checkOk(submitTransaction());
  sleep(1);
}
