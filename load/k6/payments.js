// Load test: create a payment, then read it back — at a fixed arrival rate.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e API_KEY=yk_… \
//     -e RATE=100 -e DURATION=2m grafana/k6:1.3.0 run - < load/k6/payments.js
//
// Point it at an instance using the demo provider to measure Yoon itself (API, database,
// idempotency, routing, ledger, events) without provider network latency.
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY;
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    checkout: {
      executor: 'constant-arrival-rate',
      rate: RATE,               // iterations (one create + one read) per second
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(20, RATE),
      maxVUs: RATE * 4,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{op:create}': ['p(95)<500'],
    'http_req_duration{op:read}': ['p(95)<200'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const headers = { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' };

export default function () {
  const key = uuidv4();
  const created = http.post(`${BASE_URL}/v1/payments`, JSON.stringify({
    amount: 5000, currency: 'XOF', country: 'SN', method: 'wave',
    customer: { phone: '+221771234567' }, reference: `load_${key}`,
  }), { headers: { ...headers, 'Idempotency-Key': key }, tags: { op: 'create' } });

  const ok = check(created, { 'created 201': (r) => r.status === 201 });
  if (!ok) return;

  const read = http.get(`${BASE_URL}/v1/payments/${created.json('id')}`, { headers, tags: { op: 'read' } });
  check(read, { 'read 200': (r) => r.status === 200 });
}
