# 23 — Load and security verification of the whole stack

> **Historical record, 2026-09-25.** Measurements of the stack at `821b0874` (rebuild step 23). Figures are not updated for later steps. Current state: `docs-site/guide/architecture.md`.

Collapse step 23 (plan `06-COLLAPSE-PLAN.md` §7 row 23, §9 risks 17–20, 22, 26–28, 30). Measured
2026-09-25 on branch `step/23` (base `821b0874`), on one developer machine: Apple silicon, 10 cores,
16 GB RAM, Docker Desktop 29.8.0 (linux/arm64 VM, 10 CPUs, 7.7 GiB). Every number below was
observed in this session; raw artefacts (k6 output, ZAP/Trivy reports, JVM samples) were kept in
a scratch directory and are not committed. No real secret, host name, token or address appears here:
every credential was generated for the run with `openssl rand` and died with the stack; `trove.test`
is a reserved test domain.

## 1. How the stack was run

`deploy/docker-compose.yml` exactly as committed, driven by the new `scripts/load-stack.sh`, which
writes nothing into `deploy/`:

```sh
./mvnw --batch-mode -pl core -am clean package -DskipTests      # core/Dockerfile copies core/target/*-exec.jar
scripts/load-stack.sh up   <workdir>                            # throwaway .env, Traefik render, self-signed cert, build, start
scripts/load-stack.sh k6   <workdir>                            # the load profile (§2)
scripts/stack-probes.sh    <workdir>                            # the end-to-end hardening probes (§6)
scripts/restore-drill.sh                                        # the backup/restore drill (§8)
scripts/load-stack.sh down <workdir>                            # remove containers AND volumes (only the ones this <workdir> created)
```

**It never runs as a real install's compose project.** `deploy/docker-compose.yml` says
`name: trove` and `deploy/trove` defaults to the same project, so a load stack under `-p trove` on
a host that carries Trove would recreate the live containers with throwaway secrets (core then
cannot authenticate to the live database) and `down -v` would delete `trove_postgres_data`,
`trove_trove_data` and `traefik_acme`. The measurements below were taken under `-p trove` on a
machine with no install; after review the script was changed so that cannot recur:

- the project is `TROVE_LOAD_PROJECT` (default `trove-load`; `trove`, or whatever
  `TROVE_PROJECT_NAME` says the install is called, is refused), so containers and volumes are
  `trove-load-*` / `trove-load_*`;
- `up` refuses (exit 3) while any container or volume of the install's project exists, running or
  stopped; while one of the compose file's **fixed-name networks** (`trove_edge`, `trove_data`,
  `trove_ai`, `trove_monitoring`, `trove_socket_proxy`) belongs to another project — compose only
  *warns* about that and joins the network, after which both stacks answer the `postgres` DNS
  name; or while the load project already has resources that this `<workdir>` did not create;
- `up` writes `<workdir>/.load-stack-project`; `down -v` refuses without it; `stack-probes.sh`
  reads the container name from it, and `restore-drill.sh` defaults to `trove-load-postgres-1`.

All four refusals were exercised on this machine against the earlier run's stopped `-p trove`
stack (which has exactly a real install's shape) and changed nothing. The measurements themselves
were not repeated under the new name; nothing in them depends on the project name.

What `up` does, and why it differs from `deploy/trove up`:

- **Compose refuses to start without its environment, by design.** `docker compose -f
  deploy/docker-compose.yml --env-file /dev/null config` fails on `POSTGRES_PASSWORD`,
  `TROVE_JWT_SECRET`, `TROVE_ENCRYPTION_KEY`, `TROVE_ADMIN_*`, `TROVE_DOMAIN` and also
  `GRAFANA_ADMIN_PASSWORD` (compose interpolates every service, including the ones behind the
  `monitoring` profile; `deploy/trove bootstrap` generates it, so this is not a defect).
- `<workdir>/.env` is generated (mode 0600): `TROVE_DOMAIN=trove.test`, `ACME_EMAIL=acme@trove.test`,
  `TROVE_HTTP_PORT=18080`, `TROVE_HTTPS_PORT=18443`, `TROVE_ROUTER_ENTRYPOINT=websecure`,
  `TROVE_ROUTER_TLS_LABEL=tls=true`, `POSTGRES_PASSWORD`/`TROVE_JWT_SECRET`/`TROVE_ENCRYPTION_KEY`/
  `GRAFANA_ADMIN_PASSWORD` = `openssl rand -hex`, `TROVE_ADMIN_EMAIL=admin@trove.test`,
  `TROVE_ADMIN_PASSWORD=Load-<random>`, `JAVA_OPTS=-Xmx1536m -XX:+UseZGC` (the compose default),
  `TROVE_CORE_MEM_LIMIT=2g` (the compose default). Everything else is the compose default:
  profile `prod`, `TROVE_REGISTRATION_OPEN=false`, `TROVE_RATE_LIMIT_ENABLED=true`, OIDC off,
  SMTP off, postgres `mem_limit` 512m, `TROVE_METRICS_TOKEN` unset.
- TLS: a self-signed certificate for `trove.test`, served by a Traefik file-provider entry on the
  `websecure` entrypoint (`tls=true`). A local machine cannot answer an ACME challenge, and
  `TROVE_TLS_MODE=off` would serve plain HTTP, which hides HSTS (Traefik sends it only over TLS)
  and breaks the `__Host-` cookies (they require `Secure`). `traefik.yml` is rendered with the same
  substitutions as `deploy/trove render_traefik`, HTTP→HTTPS redirect kept; `middlewares.yml` is
  copied verbatim.
- A compose override does two things only: mounts the rendered Traefik directory, and replaces the
  web container's health check (finding **F1** below).
- Clients reach the stack as `https://trove.test:18443` with `curl --resolve
  trove.test:18443:127.0.0.1 -k`. **From inside a container** (k6, ZAP) `localhost` is the
  container itself; the host is reached with `--add-host trove.test:host-gateway` (Docker
  Desktop maps `host-gateway` to the host; Traefik then sees the caller as `192.168.65.1`).
- Confirmed up: `GET /api/v1/status` → `200 {"status":"UP",...}` with no credential.
- **Seeding, for real.** Registration is closed; `FirstRunBootstrap` created one household and one
  ADMIN from `TROVE_ADMIN_EMAIL`/`TROVE_ADMIN_PASSWORD` on the first boot. Every test member was
  created the way a real one is: the admin signs in (bearer), `POST /v1/admin/invites {}` → 201 with
  a token, then `POST /v1/auth/register {inviteToken, email, password, displayName}` → 201 with a
  bearer session. The old Gatling simulation posted to a `register` endpoint the gateway did not
  have; this is the "register real" of plan row 23.

**F1 — the web container is never healthy (real, deployment).** `apps/web/Dockerfile`'s
`HEALTHCHECK` runs `wget -qO- http://localhost/`; busybox resolves `localhost` to `::1` first and
`apps/web/nginx.conf` has only `listen 80;` (IPv4), so the probe gets "connection refused"
(reproduced inside the running container). The container stays `unhealthy`, Traefik's Docker
provider does not route to an unhealthy container, and **every SPA path answers Traefik's 404** on
a stock `deploy/trove up`. `load-stack.sh` works around it at run time (probe `127.0.0.1`).
Fix (owner, `apps/web/`): probe `http://127.0.0.1/` in the Dockerfile, or add `listen [::]:80;` to
`nginx.conf`.

## 2. The load profile — k6, replacing Gatling

**Decision: replace with k6** — `tests/performance/trove-load.js`, one file, run from the
`grafana/k6` image (v2.3.0); no Maven module, no reactor entry, no install. The reasons, from reading
all six Scala simulations: every one targets the deleted gateway (`GATEWAY_URL` default
`:8080`, `/api/transactions`, `/api/dashboard`, `/api/categorisation/categorise`, `/api/receipts`,
`/actuator/health`), and `TroveSimulationBase.registerAndLogin` expects a `201` with
`$.data.token` from a register endpoint that did not exist — so "retarget" meant rewriting all of
them, in a module step 24 removed from the reactor that would build only standalone with a Scala
toolchain. **The Gatling module (`tests/performance/pom.xml`, `src/`) is obsolete and should be
deleted** — a recorded design decision left that call to this step. The deletion was attempted and
refused by this session's permission guard, so the files are still in the tree, untouched; deleting
them (plus the references in `docs-site/guide/getting-started.md:88-89`,
`docs/performance/slo-results.md` and `README.md:122`) is a documentation follow-up.

Run it (the same command `load-stack.sh k6` issues):

```sh
docker run --rm -i --add-host trove.test:host-gateway \
  -v "$PWD/tests/performance:/scripts:ro" \
  -e BASE_URL=https://trove.test:18443/api -e ADMIN_EMAIL=... -e ADMIN_PASSWORD=... \
  -e VUS=50 -e RAMP=1m -e DURATION=10m -e THINK=1 \
  grafana/k6:latest run /scripts/trove-load.js
```

**Shape (plan §9 risk 18, unchanged): 50 concurrent users, 1-minute ramp, 10-minute hold, against
the 2 GB container.** `setup()` signs the admin in, creates the household's budget period for the
current month (so the budget read is a 200, not a 404), then creates 50 members through invites,
each with its own manual account. Each virtual user then loops, with a 1 s think time between
requests, over the journeys that matter:

| journey | request |
|---|---|
| dashboard | `GET /v1/dashboard/today` |
| transactions | `GET /v1/transactions?page=0&limit=50` (paged) |
| categories | `GET /v1/categories` |
| rules | `GET /v1/categories/rules` |
| budget | `GET /v1/budgets/current` |
| receipts | `GET /v1/receipts?page=0&limit=20` (paged) |
| write | `POST /v1/transactions` (manual entry, 201) |

plus one `POST /v1/auth/refresh` per member once its access token is 10 minutes old.

**The rate limiters, and why the profile is built to stay under every bucket** (so that a 429 in
the steady state is a failure, not noise):

- trove-core keys an authenticated request on the **user** (`/api/**` 600/min, burst 1200, V12).
  A member here sends ~60/min.
- Traefik's `ratelimit-general` keys on the **source address**, 100 req/s average, burst 200. All 50
  virtual users share one address, so the whole run must stay under 100 req/s; 50 × ~0.95 req/s
  ≈ 47 req/s. Real members arrive from distinct addresses; raising `VUS` or lowering `THINK` past
  ~100 req/s measures Traefik's limiter, not the application.
- Sign-in happens once per member, in `setup()`, paced at 0.4 s: the app's register bucket is
  20/min burst 40 per address (V12) and Traefik's `ratelimit-auth` is 5/s burst 10. `setup()` met
  the register bucket once per run (member 41), waited out its `Retry-After`, and counts it in
  `setup_throttled` — that 429 is expected and is not an error.
- A login-heavy profile would be throttled by design: login is 60/min burst 120 per address in the
  app (a deliberate deviation from the planned 10/20, per a recorded design decision) and 5/s at the edge.
  The profile does not log in per iteration; it refreshes.

## 3. Measured numbers and the pass criteria now in the profile

**Run 1 — the compose defaults (`-Xmx1536m -XX:+UseZGC`, `mem_limit: 2g`), 50 VUs, 1 m + 10 m.**
k6 exit 0. `setup()` 1 m 31 s. 31 403 requests, 41.4 req/s averaged over the whole run including
setup (~47 req/s in the hold); 4 457 iterations (7.1 s each); **`http_req_failed` 0.00 %**, checks
31 249/31 249, **`rate_limited` 0.00 %**, `setup_throttled` 1.

| journey | p50 | p95 | p99 | max | criterion now in the profile (p50 / p95 / p99) |
|---|---|---|---|---|---|
| dashboard | 20.1 ms | 89.3 ms | 216.9 ms | 394.5 ms | < 60 / < 270 / < 450 |
| transactions | 7.1 | 31.0 | 145.1 | 397.4 | < 25 / < 100 / < 300 |
| categories | 4.6 | 26.8 | 119.8 | 407.9 | < 15 / < 80 / < 250 |
| rules | 4.1 | 24.0 | 120.5 | 399.3 | < 15 / < 80 / < 250 |
| budget | 4.4 | 21.3 | 83.4 | 374.9 | < 15 / < 70 / < 200 |
| receipts | 4.8 | 21.1 | 84.1 | 416.3 | < 15 / < 70 / < 200 |
| write | 9.3 | 38.3 | 157.9 | 608.7 | < 30 / < 120 / < 320 |

Latency is client-side, through Traefik and TLS. The criteria were fixed **after** this run at
roughly 3× the measured p50 and p95 and 2× the p99, so that a slower machine passes and a real
regression (an N+1, a missing index, a lock) does not. The other thresholds and their reasons:
`http_req_failed rate==0` (no non-429 error is acceptable), `checks rate==1` (every response is the
expected status), `rate_limited rate==0` (the profile is inside every bucket, §2), and
`http_reqs rate > 0.7 × VUS` (below that, requests are queueing).

Resources, sampled every 5 s (`docker stats`, the container's cgroup) and every 15 s (Micrometer,
read from inside the container, below):

| | run 1: `-Xmx1536m` (10 m hold) | run 2: `-Xmx1g` (5 m hold) | run 3: `-Xmx1280m` (5 m hold) |
|---|---|---|---|
| k6 | exit 0, 0 errors | exit 0, 0 errors, 16 541 req | exit 0, 0 errors, 16 555 req |
| container `memory.peak` (limit 2 GiB) | **1.876 GiB (93.8 %)** | 1.405 GiB (70 %) | 1.613 GiB (81 %) |
| heap committed, peak | 1.608 GB (= `-Xmx`) | 1.074 GB (= `-Xmx`) | 1.342 GB (= `-Xmx`) |
| heap used, peak | 1.363 GB | 0.979 GB | 1.193 GB |
| live set after GC, max | 243 MB | 329 MB | 367 MB |
| ZGC "Allocation Stall" cycles | **0** | **11** (33 pauses) | 6 (18 pauses) |
| ZGC pauses | 297, total 6 ms, max < 1 ms | sub-ms | sub-ms |
| OOM events (`memory.events`) | 0 | 0 | 0 |
| dashboard p50 / p99 | 20 / 217 ms | 45 / 194 ms | 39 / 171 ms |

Also in run 1: metaspace 130 MB, 78 live threads, Hikari active ≤ 1 of 20 with 0 pending, core CPU
peak 152 % (of 10 cores), postgres peak 144 MiB of its 512 MiB and 42 % CPU, Traefik 106 MiB.

Two things to know when reading these:

- **`docker stats` is not a leak detector here.** ZGC commits its heap up to `-Xmx` under this
  allocation rate and backs it with shared memory, so the cgroup counts the whole committed heap
  (`memory.stat` shows it as `shmem`) whether it holds 250 MB of live data or 1.3 GB. The number that
  matters for the limit is committed heap + ~0.35 GiB of non-heap (metaspace, code cache, thread
  stacks, direct buffers, GC metadata) — which is exactly what run 1 measured.
- **The live set grew with the ledger, and runs 2 and 3 carried more data.** The three runs shared
  one database: one household with 57 members and 4.5k → 6.8k → 9.1k transactions. The live set
  (243 → 329 → 367 MB) and the dashboard p50 (20 → 45 → 39 ms) rose with it. That is a heavier
  household than any real one (the product is one household of a few members), but it says the
  dashboard's cost scales with the household's ledger — a profiling candidate, not a defect found.

**The `-Xmx` / `mem_limit` recommendation (plan §9 risk 18's deliverable).** The evidence supports
**`-Xmx1536m -XX:+UseZGC` with `mem_limit: 2560m`** (2.5 GB): 1536m is the only heap measured with
zero allocation stalls at 50 users, and 2 GB leaves it 6 % headroom — a burst of 20 MB receipt
uploads, a data-export zip or thread growth could push the container into the OOM killer. If the
host cannot give trove-core 2.5 GB, the measured fallback is **`-Xmx1280m` inside the existing 2 GB**
(81 % peak, zero errors, six allocation-stall cycles with no measurable latency cost at this load).
**`-Xmx1g` is too small** (heap 96 % used, eleven stall cycles). Not measured, worth one run before
it is adopted: `-XX:+ZGenerational` (opt-in on JDK 21), which targets exactly these stalls.
The values belong in `deploy/docker-compose.yml` (`JAVA_OPTS` default, `TROVE_CORE_MEM_LIMIT`
default) and `.env.example` — both outside this step; the owner decides.

**Metrics access (the trap in the brief, checked first).** `/actuator/*` is on the management port
9090, which Traefik does not route: through the edge, `/api/actuator/prometheus` is a 404 JSON
envelope, `/api/v1/actuator/prometheus` a 401, and `/actuator/prometheus` falls to the SPA router
and returns `index.html`. Inside the compose network the scrape is open without a token
(`trove.management.allowed-networks` defaults to loopback + RFC 1918 — `SecurityHeadersIT` pins
this), so the metrics above were read with `docker exec trove-core-1 wget -qO-
http://127.0.0.1:9090/actuator/prometheus`. `TROVE_METRICS_TOKEN` was **not** set and no
configuration was changed.

## 4. Security headers, measured on the running stack

`curl -D -` through Traefik on `/` and `/api/v1/status`; the application's own headers read
directly from the container (`wget -S http://127.0.0.1:8080/api/v1/status`) for comparison.
Every header below arrives **exactly once** on each path — Traefik's `customResponseHeaders`
**replaces** a header the backend set, it does not add a second one.

| header | `/` (nginx sends none of these) | `/api/v1/status` | the app itself sends | owner |
|---|---|---|---|---|
| Content-Security-Policy | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` | the SPA policy (left column) — **overridden** | Traefik `csp-web` / `csp-api` |
| Permissions-Policy | `accelerometer=(), autoplay=(), camera=(self), …` | the same `camera=(self)` value | `accelerometer=(), camera=(), geolocation=(), …` — **overridden** | Traefik `security-headers` (§7) |
| Referrer-Policy | `strict-origin-when-cross-origin` | same | same | both, identical |
| Strict-Transport-Security | `max-age=31536000; includeSubDomains` (no preload, owner decision 10) | same | none (disabled on purpose) | Traefik (decision 22) |
| X-Frame-Options | `DENY` | same | none (disabled on purpose) | Traefik (decision 22) |
| X-Content-Type-Options | `nosniff` | same | `nosniff` | both |
| Server / X-Powered-By | absent | absent | — | Traefik blanks them |

Verdict for **risk 30: PASS** — `script-src 'self'` with no `'unsafe-inline'` on `/`, and the
stricter `default-src 'none'` on `/api`; the served `index.html` has no inline script (one module
`<script src>` and one stylesheet link). The app's CSP never reaches a browser behind Traefik; it
is the defence for a deployment without the proxy, and `SecurityHeadersIT` pins it.

## 5. The baseline web scan (ZAP) and its triage

`ghcr.io/zaproxy/zaproxy:stable` (ZAP 2.17.0), `zap-baseline.py -m 2` (spider + passive scan, no
attacks), with `--add-host trove.test:host-gateway`, against `https://trove.test:18443/` (8 URLs)
and `https://trove.test:18443/api/v1/status` (11 URLs; the spider also walked back to the SPA).
Both exit **2 = warnings only: 0 FAIL**, 63 and 60 rules passed. What it does not cover: anything
behind authentication (it had no session), and active attacks.

| alert | where | risk (conf.) | triage |
|---|---|---|---|
| 10055 CSP: `style-src 'unsafe-inline'` | `/`, SPA paths | Medium (High) | **Accepted**, with a recommendation. Deliberate in `middlewares.yml` (Vite may emit inline `<style>`); scripts get no such exemption and `connect-src 'self'` stops exfiltration. The served `index.html` has no inline style, so the owner should try dropping it (React's `style={}` goes through CSSOM, which CSP does not block) and let `check-csp-compat.mjs` decide. |
| 90004 COEP / COOP / CORP missing | `/`, assets, `/api/v1/status` | Low (Medium) | **Real, low.** Recommend Traefik `security-headers` add `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Resource-Policy: same-origin` (cheap isolation from cross-origin windows and embeds). `Cross-Origin-Embedder-Policy` is **accepted** as absent: nothing needs cross-origin isolation. |
| 10010 Cookie No HttpOnly Flag | `XSRF-TOKEN` | Low (Medium) | **False positive.** The double-submit token must be readable by the SPA's JavaScript; it is not a credential. The session cookies are `Secure; HttpOnly; SameSite=Lax` (checked on a cookie-mode login). |
| 10054 Cookie without SameSite | `XSRF-TOKEN` | Low (Medium) | **Real, low.** The token cookie is `Path=/; Secure` with no `SameSite`. Not exploitable (the CSRF defence is the header pair, §6), but `SameSite=Lax` would be consistent with the session cookies. Fix is in `core/src/main` (the CSRF cookie repository) — owner/next core step. |
| 10049 Non-storable / storable content | all | Info | **Accepted**: ledger and session responses are `no-store` on purpose; hashed `/assets/*` are cacheable on purpose. |
| 10109 Modern Web Application | `/` | Info | **Accepted**: a React SPA. |
| 10112 Session Management Response | `/api/v1/status` | Info | **False positive**: it is the `XSRF-TOKEN` cookie, not a session. |

## 6. The hardening claims, end to end (`scripts/stack-probes.sh`, exit 0)

Through Traefik, TLS, real cookies and a real socket. The script creates three members through
invites, enrols TOTP on one (codes computed with the Python stdlib), and deletes the failed
`login_attempt` rows its lockout probe wrote.

| claim (plan §9) | verdict | evidence on the running stack | integration test in the gate |
|---|---|---|---|
| CSP / security headers (30) | **PASS** | §4; each header once on both paths | `SecurityHeadersIT` |
| CSRF-less cookie mutation refused | **PASS** | cookie-mode `POST /v1/accounts`: no `X-XSRF-TOKEN` → 403 `forbidden`; no `X-Requested-With` → 403 `csrf-header-missing`; both → 201 | `CsrfIT` |
| `typ=totp_challenge` refused except at TOTP login (28) | **PASS** | the challenge token as a bearer → 401 on `/v1/auth/me`, `/v1/me`, `/v1/accounts`, `/v1/transactions`, `/v1/dashboard/today`, `/v1/budgets`, `/v1/receipts`, `/v1/admin/users`; `POST /v1/auth/login/totp` with it and a fresh code → 200 | `JwtTypeConfusionIT` |
| app limiter runs after auth, keyed per user (20) | **PASS** | member A × 75 on `GET /v1/ai/settings` (30/min burst 60): 60 × 200, 15 × 429 `rate-limited`; member B, **same address**, next call → 200 | `RateLimitIT` |
| forged `X-Forwarded-For` keyed on the socket (26) | **PASS** | 20 failed logins, each with a different forged `X-Forwarded-For` and `X-Real-IP`: `login_attempt` recorded all 20 against one address (the host as Traefik saw it, `192.168.65.1`, outside `trove_edge`'s `172.23.0.0/16`), none of the forged ones; the 21st login (correct password) → 429 `account-locked`. Since review the probe itself also requires exactly one non-NULL address outside `trove_edge`'s subnet, so a regression to keying on Traefik's socket address (one shared lockout key for every client) fails it rather than relying on a reader | `LockoutIT`, `ClientIpIT` |
| edge limiter on `/api/v1/auth` (20) | **PASS** | 15 concurrent `GET /api/v1/auth/providers`: 10 × 200, 5 × Traefik 429 (`Too Many Requests`, plain text) — the eleventh rapid call is refused | none (Traefik config) |
| app login bucket behind the edge (20) | **PASS** | correct-password logins paced at ~4/s (under Traefik's 5/s): attempt 206 → 429 `rate-limited` — the V12 seed 60/min burst 120 per address, not the plan's 10/20 | `RateLimitIT` |
| null/blank resolved address refused with 429 | **not reproducible end to end** | Traefik always supplies an address; the path exists only behind a misconfigured proxy | `ClientIpIT`, `LockoutServiceTest` |
| OIDC unverified email does not auto-link (27) | **not verifiable end to end here** | OIDC is off on this stack; checking it needs an identity provider that issues `email_verified=false`, i.e. an issuer configured into the environment, which this step does not do | `OidcAccountLinkerTest` (unit: `email_verified=false`, absent claim, and open registration all refuse); **there is no `OidcLinkIT`** |

## 7. Permissions-Policy: which `camera` value reaches the browser

Measured, not reasoned: the application emits `camera=()` on `/api`; Traefik's `security-headers`
middleware is on **both** routers and sets `camera=(self)`, and because `customResponseHeaders`
replaces rather than appends, **the browser receives exactly one Permissions-Policy on each path,
and on both it is Traefik's `camera=(self)`**. The app's stricter value never arrives.

**Recommendation (owner):** Traefik should own this header — it is the only layer that answers the
SPA document, which is the only thing a browser applies the policy to — and it should say
`camera=()`. Change in `deploy/traefik/dynamic/middlewares.yml`, `security-headers`:
`camera=(self)` → `camera=()`, and delete the comment that justifies `(self)`. Reasons: the web
client has **no camera feature** (the receipt page is a plain `<input type="file" accept=...>` with
no `capture` attribute and nothing calls `getUserMedia`; and a file input handing off to the OS
camera is not governed by this policy anyway); the **mobile** client's capture is `expo-image-picker`
under a native OS permission that this header does not touch at all. `camera=(self)` grants a
capability nothing uses, which is only useful to injected script. The app's own value can stay as
the no-proxy fallback. Not changed here: `deploy/` is outside this step.

## 8. What else was verified

**Restore drill (plan §9 risk 19), `scripts/restore-drill.sh`: PASS.** On the live stack after the
load runs: `pg_dump -Fc --no-owner --no-acl` (as `deploy/trove backup`) → `openssl enc
-aes-256-cbc -md sha512 -pbkdf2 -iter 600000` and back (byte-identical, 688 KB) → `pg_restore
--no-owner --no-acl` into a scratch `pgvector/pgvector:pg16` container → **46 tables, 9 861 rows
and 45 Flyway history rows identical** to the source. Not covered: the `/data` volume tar
(no logic), `deploy/trove restore` itself (it replaces the live database behind an interactive
prompt), a Playwright smoke on the restored copy, and the nightly CI schedule the plan names (CI is
off).

**Compose/Traefik wiring (part of risk 17):** `providers.docker.network: trove_edge` matches the
compose network name, and Traefik routed both routers (`/` and `/api`) once the web container was
healthy; the HTTP entrypoint answers `301` to HTTPS.

## 9. Dependency audit

**Java (core).** The OWASP check `security.yml` configures could **not** be run: the current
`dependency-check` 13.0.0 (Docker) refuses to update the NVD database without an API key
(`NvdApiException: Invalid API Key, length of 0`), the repository's key is a GitHub secret, and the
Maven route (plugin 10.0.4, `-DnvdApiKey=`) would need the same key and hold the shared Maven lock
for the whole download. **Substituted:** the same job's other half, Trivy, exactly as
`security.yml`'s `trivy-scan` job runs it (`--severity HIGH,CRITICAL --ignore-unfixed`) against the
image the stack ran, `trove-core:local` (Trivy's DB covers the Maven coordinates via GHSA). Exit 1:

| severity | id | package (installed → fixed) | reachable from what Trove runs? |
|---|---|---|---|
| CRITICAL | CVE-2026-65182 | tomcat-embed-core 11.0.22 → 11.0.25 | **No**: security-constraint ordering in Tomcat's own container security; Trove declares no servlet security constraints (Spring Security does authorisation). |
| CRITICAL | CVE-2026-65905 | tomcat-embed-core 11.0.22 → 11.0.25 | **No**: Tomcat's DIGEST authenticator, unused. |
| CRITICAL | CVE-2026-68525 | tomcat-embed-core 11.0.22 → 11.0.25 | **No**: Tomcat's FORM authenticator, unused. |
| CRITICAL | CVE-2026-8763 | bcprov-jdk18on 1.84 → 1.85 | **No**: X.509 name-constraint checking; Trove uses Bouncy Castle for Argon2 password hashing (`PasswordEncoderConfig`), not PKIX path validation. |
| HIGH | CVE-2026-13506 | bcprov-jdk18on 1.84 → 1.85 | **Unlikely**: DoS in lazy ASN.1 parsing; Trove does not hand untrusted ASN.1 to Bouncy Castle. |
| HIGH | CVE-2026-54399 | httpcore5 5.4.2 → 5.4.3 | **Yes, bounded**: `httpclient5` is the outbound client, and a member chooses their own AI endpoint and webhook targets, so a hostile server can answer with excessive headers (client-side DoS of one worker; timeouts bound it). |
| HIGH | CVE-2026-54428 | httpcore5-h2 5.4.2 → 5.4.3 | **Yes, bounded**: as above, for HTTP/2 HPACK. |
| HIGH | CVE-2026-93990 | alpine `libexpat` 2.8.4-r0 → 2.8.5-r0 (base image) | **No**: nothing on the JVM's path links expat; fixed by rebuilding on the current base. |

Recommendation (owner; `pom.xml`/`core/pom.xml`/`core/Dockerfile` are outside this step): bump
Tomcat to 11.0.25 (the Boot-managed `tomcat.version`), `bouncycastle.version` to 1.85,
`httpcore5` to 5.4.3, and rebuild the image; then re-run Trivy. **`.owasp-suppressions.xml` holds
no suppression at all** (only a commented template), so there is nothing in it to triage; the
triage above is what a future suppression would have to cite.

**Web and mobile.** `node scripts/npm-audit-gate.mjs apps/web|apps/mobile` (the existing gate,
`npm audit --omit=dev`, fails only on unallowlisted high/critical): **both PASS, exit 0.** Full
`npm audit` counts (read-only; no install, no fix):

| tree | production deps (the gated set) | all deps incl. dev |
|---|---|---|
| apps/web | 0 critical, 0 high, 2 moderate | 0 critical, 3 high, 5 moderate |
| apps/mobile | 0 critical, 2 high, 14 moderate | 0 critical, 4 high, 19 moderate |

Every high, named: **web (dev only)** — `vite` ≤ 6.4.2 (GHSA-4w7w-66w2-5vf9 path traversal in
optimized-deps `.map`, GHSA-v6wh-96g9-6wx3, GHSA-fx2h-pf6j-xcff `server.fs.deny` bypass on
Windows): the dev server only, never in the built bundle nginx serves; `js-yaml` 4.0.0–4.3.1
(GHSA-2883-xcg3-v3hh, CPU on empty merge keys) via `@redocly/openapi-core`, the spec linter at build
time. **Mobile** — `image-size` (GHSA-5p2g-fcmc-qvqq, GHSA-w3rx-r6r6-pgpr) and `ws`
(GHSA-58qx-3vcg-4xpx, GHSA-96hv-2xvq-fx4p): both allowlisted until 2026-12-31 with recorded reasons
(Metro/Expo dev-server tooling, not in the on-device bundle); plus the same `js-yaml`/
`@redocly/openapi-core` pair in dev. None is reachable from code the product runs.

## 10. The `OpenApiRouteDriftIT` params-condition blind spot (fixed, test-only)

Step 13 finding 7. The drift comparison keyed routes on method and path only, so a second handler
that Spring tells apart by a `params` condition folded silently into the first —
`ChatController` answers `POST /v1/chat/completions` with two handlers, one on `params="stream=true"`
— and a route added that way would have gone unnoticed in both directions. Three cases were added
to `core/src/test/java/finance/trove/arch/OpenApiRouteDriftIT.java` (the spec is unchanged and
passes):

- *Every params-conditioned handler names only query parameters its operation declares* — asserts
  the collector really sees `POST /v1/chat/completions ?stream` (so reverting to method-and-path
  fails it), and that every parameter a `params` condition names is a declared `in: query`
  parameter of the operation (following `#/components/parameters` references by hand).
- *Handlers sharing a route are told apart by a params condition and nothing else* — on a
  method and path with more than one handler, two handlers with the same params condition (the
  empty one included) fail the build, and so does any handler that also carries a headers,
  consumes, produces, API-version or custom condition, none of which either side of the comparison
  can see.
- *A params-conditioned route the document does not describe is caught* — a synthetic
  `POST /v1/accounts` with `params="bulk=true"` must be reported as `POST /v1/accounts ?bulk`; four
  synthetic splits must be reported as unreconciled (header only; plain + `bulk=true` with a header;
  `bulk=true` twice differing by `produces`; `bulk=true` twice), and the `ChatController` shape
  (plain + `bulk=true`) must not be.

## 11. Plan §9 as a checklist — does the named test exist, and what covers it today

All tests named "yes" ran green in the gate (surefire 1650, failsafe 698).

| # | named test | exists? | what covers it |
|---|---|---|---|
| 1 | `SecurityChainIT` | no | `ArchitectureTest` (no chain outside `app`), `SkeletonIT` (an unknown `/api/v1` route is 401), `OpenApiRouteDriftIT` (anonymous operations == permit list). No test sends an anonymous request to every served route. |
| 2 | `AccessControlIT` | yes | |
| 3 | `DeduplicationServiceIT` | no | `DeduplicationServiceTest` (unit) |
| 4 | `EnvelopeSpendServiceIT` / `RolloverServiceIT` | yes / no | `RolloverJobIT` |
| 5 | `MixedCurrencyIT` | no | `NetWorthMixedCurrencyIT`, `SourceConventionTest`, `SchemaDefaultsIT` |
| 6 | `SchemaDefaultsIT` | yes | |
| 7 | `OpenApiTest` | no | superseded by the OpenAPI-first decision: `OpenApiRouteDriftIT` + per-request `OpenApiConformance` |
| 8 | `FilterOrderIT` | yes | plus `ArchitectureTest` |
| 9 | `OutboundUrlPolicyTest` | yes | |
| 10 | `AiGatewayIT` | no | `AiPolicyIT`, `AiSettingsIT`, `CoachIT` |
| 11 | `ChatStreamIT` | yes | |
| 12 | `ReceiptPipelineIT`, `ReceiptManualFlowIT` | yes | |
| 13 | `NotificationServiceIT` | yes | |
| 14 | `SubscriptionScanFreshInstallIT` | yes | |
| 15 | `CsvExportBuilderTest`, `TaxExportIT` | yes | |
| 16 | `JwtPropertiesTest` | yes | compose `config` fails without `.env` (checked, §1) |
| 17 | ACME staging run | **not run** | §12 |
| 18 | load run for `-Xmx`/`mem_limit` | **this step** | §3 |
| 19 | restore drill | **this step, by hand** | §8 (not nightly: CI is off) |
| 20 | `RateLimitFilterIT` | no, as `RateLimitIT` | plus §6 end to end |
| 21 | `SchedulerLockTest` / `UserClockIT` | no / no | `ScheduledJobsTest` / `UserClockTest`, `DashboardIT`, `PreferencesIT` |
| 22 | Playwright e2e against built images | **not run** | §12 |
| 23 | `generate-openapi` CI diff | n/a | superseded by the hand-written spec + `OpenApiRouteDriftIT` |
| 24 | parallel-step collisions | n/a | process rule |
| 25 | CI availability | n/a | CI was unavailable during the rebuild; every gate here ran locally |
| 26 | `LoginLockoutIT` | no, as `LockoutIT` | plus `ClientIpIT` and §6 end to end |
| 27 | `OidcLinkIT` | **no** | `OidcAccountLinkerTest` (unit) only — §6 |
| 28 | `JwtTypeConfusionIT` | yes | plus §6 end to end |
| 29 | `AccessControlIT` | yes | |
| 30 | e2e CSP assertion | this step | `SecurityHeadersIT` + §4/§6 |
| 31 | `DataExportIT`, `AccountDeletionIT` | yes | |

## 12. What this step could NOT verify, and why

- **Let's Encrypt staging against a real DNS name (risk 17).** Needs a public DNS name pointing at a
  reachable host; this machine has neither, and a public deployment is out of scope. The stack ran
  on a self-signed certificate on the same entrypoint and router labels instead.
- **Playwright e2e against the built images (risk 22).** Needs `npm`/`npx` in `apps/web`, which this
  step may not run (only `npm audit`).
- **OWASP Dependency-Check.** Needs an NVD API key (§9); Trivy substituted.
- **OIDC `email_verified=false` end to end (risk 27)** and **the null-address 429** — §6.
- **Load through distinct client addresses.** All virtual users shared one source address, which is
  why the profile is capped under Traefik's per-address 100 req/s (§2); edge behaviour for many
  distinct clients was not measured.
- **Receipt upload, data export and the SSE coach under load.** Not in the profile: upload needs a
  fixture image and an OCR path (AI off here), export is one-per-member-per-day by design, the coach
  needs an AI endpoint. They are the likeliest sources of the memory spikes §3 warns about.
- **A production-shaped machine.** One laptop VM; absolute latencies will differ on the target host.
  Running `load-stack.sh` on a host that carries an install is refused by design (§1); a
  production-shaped run needs a separate machine or a host without a `trove` project.
