// trove-load.js — the load profile of collapse step 23 (plan §9 risk 18).
//
// k6, run in Docker; no install, no Maven module. It replaces the Gatling simulations that
// targeted the deleted API gateway (see docs/recovery/23-LOAD-AND-SECURITY.md §2 for why).
//
//   scripts/load-stack.sh k6 <workdir>          # against the stack load-stack.sh stood up
//
// or by hand (the container reaches the host's published port through host-gateway):
//
//   docker run --rm -i --add-host trove.test:host-gateway \
//     -v "$PWD/tests/performance:/scripts:ro" \
//     -e BASE_URL=https://trove.test:18443/api -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
//     grafana/k6:latest run /scripts/trove-load.js
//
// SHAPE. VUS members (default 50) of ONE household, each registered for real through an admin
// invite in setup() (registration is closed by default), each with its own manual account. The
// household gets a budget period for the current month so that `GET /v1/budgets/current` is a
// 200 and not a 404. Ramp 0 -> VUS over RAMP (1m), hold VUS for DURATION (10m). Every VU loops
// over the six journeys with THINK seconds (default 1) between requests — a person clicking,
// not a benchmark client.
//
// WHY THE RATE LIMITERS NEVER FIRE IN THE STEADY STATE, AND WHY A 429 THERE IS A FAILURE.
//   - trove-core keys an authenticated request on the USER: `/api/**` is 600/min burst 1200
//     (V12). One VU sends about 7 requests per ~8 s, i.e. ~55/min — an order of magnitude under.
//   - Traefik's `ratelimit-general` keys on the SOURCE ADDRESS: 100 req/s average, burst 200.
//     Every VU of this run shares ONE address (the load host), so the whole run must stay under
//     100 req/s: 50 VUs x ~0.9 req/s = ~45 req/s. Real members arrive from distinct addresses and
//     do not share that bucket; raising VUS or lowering THINK past ~100 req/s measures Traefik's
//     limiter, not the application.
//   - Sign-in happens ONCE per member, in setup(), paced at SETUP_PACE seconds: the app's
//     register bucket is 20/min burst 40 per address, Traefik's `ratelimit-auth` 5/s burst 10.
//     setup() is expected to meet the register bucket (member 41 onward) and waits out its
//     Retry-After; those 429s are counted in `setup_throttled` and are informational.
//   - Access tokens live 15 minutes; a member refreshes once its token is REFRESH_AFTER_S old.
//     Registration is paced, so the refreshes are paced the same way.
//   So any 429 during the run means a limit this reasoning got wrong: `rate_limited` must be 0.

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'https://trove.test:18443/api';
const VUS = parseInt(__ENV.VUS || '50', 10);
const RAMP = __ENV.RAMP || '1m';
const DURATION = __ENV.DURATION || '10m';
const THINK = parseFloat(__ENV.THINK || '1');
const SETUP_PACE = parseFloat(__ENV.SETUP_PACE || '0.4');
const REFRESH_AFTER_S = parseInt(__ENV.REFRESH_AFTER_S || '600', 10);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;

const rateLimited = new Rate('rate_limited');
const setupThrottled = new Counter('setup_throttled');

// A 429 is not an http_req_failed: it is measured on its own (rate_limited) so that it can be
// explained rather than folded into the error rate. Everything else outside 2xx is a failure.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

// PASS CRITERIA — measured first (50 VUs, 1m ramp + 10m hold, 2026-09-25, a 10-core laptop with
// Docker Desktop, -Xmx1536m in a 2 GB container: docs/recovery/23-LOAD-AND-SECURITY.md §3), then
// fixed here at about 3x the measured p50, 3x the p95 and 2x the p99 of each journey, so a slower
// machine passes and a real regression (an N+1, a missing index, a lock) does not. Milliseconds,
// at the client, through Traefik and TLS.
//   measured p50/p95/p99: dashboard 20/89/217, transactions 7/31/145, categories 5/27/120,
//   rules 4/24/120, budget 4/21/83, receipts 5/21/84, write 9/38/158
const LATENCY = {
  dashboard: ['p(50)<60', 'p(95)<270', 'p(99)<450'],
  transactions: ['p(50)<25', 'p(95)<100', 'p(99)<300'],
  categories: ['p(50)<15', 'p(95)<80', 'p(99)<250'],
  rules: ['p(50)<15', 'p(95)<80', 'p(99)<250'],
  budget: ['p(50)<15', 'p(95)<70', 'p(99)<200'],
  receipts: ['p(50)<15', 'p(95)<70', 'p(99)<200'],
  write: ['p(50)<30', 'p(95)<120', 'p(99)<320'],
};

const thresholds = {
  // Zero non-429 errors, over the whole run including setup.
  http_req_failed: ['rate==0'],
  checks: ['rate==1'],
  // Zero throttling in the steady state (see the header): the profile is inside every bucket.
  rate_limited: ['rate==0'],
  // Throughput floor: a member at THINK=1 sustains ~0.9 req/s (50 -> ~45 req/s); under 0.7 per
  // member, averaged over the whole run including setup, means requests are queueing.
  http_reqs: [`rate>${Math.round(VUS * 0.7)}`],
};
for (const [journey, rules] of Object.entries(LATENCY)) {
  thresholds[`http_req_duration{journey:${journey}}`] = rules;
}

export const options = {
  insecureSkipTLSVerify: true, // the local stack's certificate is self-signed for trove.test
  setupTimeout: '5m',
  scenarios: {
    members: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },
        { duration: DURATION, target: VUS },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function bearer(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

// setup() calls: retry on 429 after the server's Retry-After, count it, never hammer.
function setupCall(method, path, body, params) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const res = http.request(method, `${BASE}${path}`, body === undefined ? null : JSON.stringify(body),
      Object.assign({ tags: { journey: 'setup' } }, params || { headers: JSON_HEADERS }));
    if (res.status !== 429) {
      return res;
    }
    setupThrottled.add(1);
    sleep(parseInt(res.headers['Retry-After'] || '5', 10) + 1);
  }
  fail(`${method} ${path} still throttled after 20 attempts`);
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

export function setup() {
  const status = http.get(`${BASE}/v1/status`, { tags: { journey: 'setup' } });
  if (status.status !== 200) fail(`stack not up: GET /v1/status -> ${status.status}`);

  const login = setupCall('POST', '/v1/auth/login',
    { email: __ENV.ADMIN_EMAIL, password: __ENV.ADMIN_PASSWORD });
  if (login.status !== 200) fail(`admin login -> ${login.status} ${login.body}`);
  const admin = login.json('data.accessToken');

  // One budget period for the household's current month; 409 means an earlier run made it.
  const budgetParams = bearer(admin);
  budgetParams.responseCallback = http.expectedStatuses(201, 409);
  const budget = setupCall('POST', '/v1/budgets', { month: today(), totalIncome: '5000.00' },
    budgetParams);
  if (budget.status !== 201 && budget.status !== 409) fail(`budget -> ${budget.status} ${budget.body}`);

  const members = [];
  for (let i = 0; i < VUS; i++) {
    const invite = setupCall('POST', '/v1/admin/invites', {}, bearer(admin));
    if (invite.status !== 201) fail(`invite ${i} -> ${invite.status} ${invite.body}`);
    const password = `Pw!${Math.random().toString(36).slice(2)}${Math.random().toString(36).slice(2)}`;
    const reg = setupCall('POST', '/v1/auth/register', {
      inviteToken: invite.json('data.token'),
      email: `load-${RUN_ID}-${i}@trove.test`,
      password,
      displayName: `Load member ${i}`,
    });
    if (reg.status !== 201) fail(`register ${i} -> ${reg.status} ${reg.body}`);
    const access = reg.json('data.accessToken');
    const account = setupCall('POST', '/v1/accounts',
      { sourceKind: 'MANUAL', displayName: `Load cash ${i}`, accountType: 'OTHER' }, bearer(access));
    if (account.status !== 201) fail(`account ${i} -> ${account.status} ${account.body}`);
    members.push({
      access,
      refresh: reg.json('data.refreshToken'),
      issuedAt: Date.now(),
      accountId: account.json('data.id'),
    });
    sleep(SETUP_PACE);
  }
  return { members };
}

// Per-VU session state (each VU is its own JS runtime).
let session = null;

function refreshIfOld() {
  if (Date.now() - session.issuedAt < REFRESH_AFTER_S * 1000) return;
  const res = http.post(`${BASE}/v1/auth/refresh`, JSON.stringify({ refreshToken: session.refresh }),
    { headers: JSON_HEADERS, tags: { journey: 'refresh' } });
  rateLimited.add(res.status === 429);
  if (check(res, { 'refresh 200': (r) => r.status === 200 })) {
    session.access = res.json('data.accessToken');
    session.refresh = res.json('data.refreshToken');
    session.issuedAt = Date.now();
  }
}

function step(journey, method, path, body, expected) {
  const params = bearer(session.access);
  params.tags = { journey };
  const res = http.request(method, `${BASE}${path}`, body ? JSON.stringify(body) : null, params);
  rateLimited.add(res.status === 429);
  check(res, { [`${journey} ${expected}`]: (r) => r.status === expected });
  sleep(THINK);
  return res;
}

export default function (data) {
  if (session === null) {
    session = Object.assign({}, data.members[(__VU - 1) % data.members.length]);
  }
  refreshIfOld();

  step('dashboard', 'GET', '/v1/dashboard/today', null, 200);
  step('transactions', 'GET', '/v1/transactions?page=0&limit=50', null, 200);
  step('categories', 'GET', '/v1/categories', null, 200);
  step('rules', 'GET', '/v1/categories/rules', null, 200);
  step('budget', 'GET', '/v1/budgets/current', null, 200);
  step('receipts', 'GET', '/v1/receipts?page=0&limit=20', null, 200);
  step('write', 'POST', '/v1/transactions', {
    accountId: session.accountId,
    amount: `${1 + Math.floor(Math.random() * 200)}.${String(Math.floor(Math.random() * 100)).padStart(2, '0')}`,
    date: today(),
    type: 'DEBIT',
    merchant: 'Load test merchant',
  }, 201);
}
