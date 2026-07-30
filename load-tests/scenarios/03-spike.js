/**
 * Scenario 3 — Burst / Spike
 *
 * Normal load (50 VUs) for 2 minutes, sudden spike to 500 VUs for 30 seconds,
 * then recovery back to normal for 1 minute.
 *
 * Validates that Kafka absorbs the burst — transactions queue rather than drop,
 * and the system recovers without manual intervention.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { htmlReport } from '../lib/reporter.js';
import { BASE_URL, transactionPayload, pick, CUSTOMERS, MERCHANTS } from '../config.js';

const spikeErrorRate  = new Rate('spike_error_rate');
const spikeDuration   = new Trend('spike_response_time');

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-vus',
      startVUs: 50,
      stages: [
        { duration: '2m',  target: 50  },  // normal baseline
        { duration: '10s', target: 500 },  // sudden spike
        { duration: '30s', target: 500 },  // hold spike
        { duration: '10s', target: 50  },  // recover
        { duration: '1m',  target: 50  },  // confirm recovery
      ],
    },
  },
  thresholds: {
    // Spike thresholds are intentionally looser — the goal is no failures, not low latency
    http_req_duration: ['p(99)<5000'],
    http_req_failed:   ['rate<0.01'],
    spike_error_rate:  ['rate<0.01'],
  },
};

export default function () {
  const headers = { 'Content-Type': 'application/json' };

  const start = Date.now();
  const res = http.post(
    `${BASE_URL}/api/v1/standalone`,
    transactionPayload({
      customerId: pick(CUSTOMERS),
      merchantId: pick(MERCHANTS.clean),
      amount:     (Math.random() * 500 + 10).toFixed(2),
    }),
    { headers }
  );
  spikeDuration.add(Date.now() - start);

  const ok = check(res, {
    'spike: status 202': (r) => r.status === 202,
  });

  spikeErrorRate.add(!ok);
  sleep(0.2);
}

export function handleSummary(data) {
  return { '/scripts/results/03-spike.html': htmlReport(data) };
}
