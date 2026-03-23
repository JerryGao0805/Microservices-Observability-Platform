import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate = new Rate('errors');
const orderDuration = new Trend('order_creation_duration');

export const options = {
  vus: 10,
  duration: '2m',
  thresholds: {
    http_req_failed: ['rate<0.05'],       // Error rate < 5%
    http_req_duration: ['p(95)<500'],     // p95 < 500ms
    errors: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';

const currencies = ['USD', 'EUR', 'GBP'];
const amounts = [50, 100, 200, 500, 750, 1000, 1500, 2000];

export default function () {
  const payload = JSON.stringify({
    userId: `loadtest-user-${__VU}`,
    amount: amounts[Math.floor(Math.random() * amounts.length)],
    currency: currencies[Math.floor(Math.random() * currencies.length)],
  });

  const res = http.post(`${BASE_URL}/api/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  const success = check(res, {
    'status is 201': (r) => r.status === 201,
    'has orderId': (r) => JSON.parse(r.body).id !== undefined,
  });

  errorRate.add(!success);
  orderDuration.add(res.timings.duration);

  sleep(0.1);
}

export function handleSummary(data) {
  const totalReqs = data.metrics.http_reqs.values.count;
  const failRate = data.metrics.http_req_failed.values.rate;
  const p95 = data.metrics.http_req_duration.values['p(95)'];

  console.log('\n=== Load Test Summary ===');
  console.log(`Total Requests: ${totalReqs}`);
  console.log(`Error Rate: ${(failRate * 100).toFixed(2)}%`);
  console.log(`p95 Latency: ${p95.toFixed(0)}ms`);
  console.log(`Pass: ${totalReqs > 5000 ? 'YES' : 'NO'} (>5000 reqs)`);
  console.log(`Pass: ${failRate < 0.05 ? 'YES' : 'NO'} (<5% errors)`);
  console.log(`Pass: ${p95 < 500 ? 'YES' : 'NO'} (p95 < 500ms)`);

  return {
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

function textSummary(data, opts) {
  // k6 built-in summary is used by default
  return '';
}
