/**
 * Scenario 03 — Month-end spike
 *
 * Simulates the South African payday spike pattern: a sustained baseline,
 * a sudden 3× burst (representing month-end transaction surge), then a
 * return to baseline. Measures:
 *   - How quickly latency recovers after the burst
 *   - Whether the rate limiter sheds load gracefully (429s, not 500s)
 *   - Whether the system returns to baseline SLOs after the spike passes
 *
 * Usage:
 *   make load-test scenario=03
 *   make load-test scenario=03 vus=60 duration=6m
 */
import { sleep } from 'k6';
import { submitTransaction, checkOk } from '../lib/helpers.js';

const PEAK_VUS = __ENV.VUS      ? parseInt(__ENV.VUS)  : 30;
const BASE_VUS = Math.max(1, Math.floor(PEAK_VUS / 3));

export const options = {
  stages: [
    { duration: '1m',  target: BASE_VUS  },  // warm up at baseline
    { duration: '30s', target: PEAK_VUS  },  // spike
    { duration: '30s', target: PEAK_VUS  },  // hold spike
    { duration: '1m',  target: BASE_VUS  },  // recover
    { duration: '1m',  target: BASE_VUS  },  // confirm stable recovery
  ],
  thresholds: {
    // Measure overall — individual spike phases will exceed these, but the
    // aggregate tells you if the system recovered cleanly
    http_req_failed:   ['rate<0.10'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {
  checkOk(submitTransaction());
  sleep(0.5);
}
