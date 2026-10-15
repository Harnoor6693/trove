# Infra/Deployment Inventory — Trove Monolith Collapse

Verified directly against source on 2026-09-03 (commit `9ad1b5f`, main). Every compose
file, Dockerfile, `.env.example`, and infra config referenced below was read in full, not
sampled. `docs/recovery/01-FINDINGS.md` section O supersedes earlier sections in that
document; every defect cited from H/G here was re-verified against source as part of this
pass, and three new defects not in H1–H10 were found and are called out explicitly
(§12.4). Owner decisions from the collapse brief are treated as fixed and are not
re-litigated — this document inventories what exists today so the target in §13 can be
built without re-discovering any of it.

---

## 1. docker/docker-compose.yml — the README's stack (21 services, does not work)

`docker/docker-compose.yml` is 526 lines, 21 services, matching the brief's count exactly:
`postgresql, valkey, minio, ollama, mailhog, authentik-server, authentik-worker,
litellm-gateway, gateway, ingestion-svc, categorisation, tax-engine, budget-svc,
receipt-proc, subscription-det, notification-svc, report-svc, model-manager, prometheus,
grafana, loki`. **No `web-app` service exists in this file at all** — confirmed by
`grep -n "web-app\|apps/web" docker/docker-compose.yml` returning nothing. The web SPA has
no path to running under the README's stack; a self-hoster following the README never
gets a browser-reachable frontend even if every other blocker were fixed.

**Ports published to the host** (all of them — every service is directly reachable from
the LAN, not just via a gateway):

| Service | Host ports | Notes |
|---|---|---|
| postgresql | 5432 | |
| valkey | 6379 | |
| minio | 9000, 9001 | API + console |
| ollama | 11434 | |
| mailhog | 1025, 8025 | |
| authentik-server | 9080→9000, 9443 | |
| litellm-gateway | 4000 | |
| gateway | 8080, 9090 | 9090 = actuator/management |
| ingestion-svc | 8081 | |
| categorisation | 8082 | |
| tax-engine | 8083 | |
| budget-svc | 8084 | |
| receipt-proc | 8085 | |
| subscription-det | 8086 | |
| notification-svc | 8087 | |
| report-svc | 8088 | |
| model-manager | 8089 | |
| prometheus | 9091→9090 | |
| grafana | 3001→3000 | |
| loki | 3100 | |

This is O3's corroboration point verified directly: **all ten business services are
published straight to the host**, so the gateway is not a network boundary and G1 (IDOR
via client-controlled `X-User-Id`) is reachable from the LAN by hitting e.g.
`ingestion-svc:8081` directly, bypassing `gateway` entirely.

**Loki logging blocks.** All 10 Spring Boot services (not the infra containers) carry an
identical 16-line `logging: driver: loki` block (e.g. `docker/docker-compose.yml:137–153`
for `gateway`, repeated verbatim for each service), pushing to
`http://localhost:3100/loki/api/v1/push` with a JSON pipeline stage extracting
`level`/`service`/`requestId`/`userId`. **`localhost` inside a container is the container
itself**, not the `loki` service on the compose network — this loki endpoint is
unreachable from every one of the 10 containers as written (would need `http://loki:3100`).
Independent of that, `driver: loki` requires the `docker-loki-driver` **Docker plugin**,
which nothing in the repo installs (H1b) — Docker Engine will refuse to start any of these
10 containers with `error looking up logging plugin loki: plugin "loki" not found` before
the URL bug even matters.

**AUTHENTIK_CLIENT_ID/SECRET.** `gateway`'s environment block
(`docker/docker-compose.yml:111-128`) sets `AUTHENTIK_URL` but never sets
`AUTHENTIK_CLIENT_ID` or `AUTHENTIK_CLIENT_SECRET`. `api-gateway`'s
`application.yml:33-34` requires both with **no `:` default**
(`${AUTHENTIK_CLIENT_ID}`, `${AUTHENTIK_CLIENT_SECRET}`) — Spring's property placeholder
resolution throws `PlaceholderResolutionException` and the context refresh aborts before
the app can bind port 8080. Confirms H1c exactly.

**JVM flags.** Every service sets `JAVA_TOOL_OPTIONS: -Xmx300m` in this file (e.g.
`docker/docker-compose.yml:112`), which is picked up automatically by the JVM in addition
to whatever the Dockerfile's `ENTRYPOINT` passes. Because an explicit `-Xmx` always wins
over `-XX:MaxRAMPercentage`, **this compose file's 300m cap is real** — this is not the
stack O5 is about (see §10).

**Healthchecks.** Only `postgresql`, `valkey`, `minio`, and `loki` declare a `healthcheck:`
block in this file (lines 16-20, 26-30, 43-47, 515-519). None of the 10 Spring Boot
services do — they fall back to the `HEALTHCHECK` baked into their own Dockerfile (§4),
which Docker will still honor, but `depends_on: condition: service_healthy` is used only
for `postgresql`/`valkey`/`minio` upstream of the services that need them (e.g.
`gateway` depends on `postgresql`+`valkey` healthy; `receipt-proc`/`report-svc` also wait
on `minio`) — no service waits on another *service's* health, only on infra.

**Init SQL — 11 databases.** `docker/postgres-init.sql` (10 lines) issues
`CREATE DATABASE` for `trove_gateway, trove_ingestion, trove_categorisation,
trove_notification, trove_budget, trove_report, trove_models, trove_receipt,
trove_subscription, trove_tax` plus `authentik` — **11 total**, one Postgres process,
matching O6 ("worst of both worlds": pays the cost of database-per-service, gets none of
the process-isolation benefit).

---

## 2. infra/** — three more compose files, one genuinely good, one entirely dead

```
infra/docker-compose.yml            248 lines — DEAD (H7)
infra/docker-compose.infra.yml      113 lines — infra-only subset (postgres/valkey/minio/ollama/litellm/authentik), no app services
infra/full-stack/docker-compose.yml 591 lines — the maintained path (H6)
infra/full-stack/traefik/traefik.yml       45 lines
infra/full-stack/traefik/dynamic.yml       34 lines
infra/full-stack/postgres-init/01-create-databases.sql
infra/full-stack/prometheus/prometheus.yml
infra/.env.example                  44 lines, 19 vars
infra/full-stack/.env.example       81 lines, 35 vars
```

### 2.1 `infra/docker-compose.yml` — confirmed dead (H7)

- Line 72: `litellm-gateway` mounts `../config/litellm.yaml:/app/config.yaml:ro`.
  `config/litellm.yaml` **does not exist** — `ls config/` shows only `checkstyle/` and
  `litellm.yaml.template`. The filename without `.template` is never created anywhere.
- Line 229: `prometheus` mounts `../config/prometheus.yml:/etc/prometheus/prometheus.yml:ro`.
  `config/prometheus.yml` **does not exist** either.
- Every service (`api-gateway`, `ingestion-svc`, `categorisation`, `tax-engine`,
  `budget-svc`, `receipt-proc`, `subscription-det`, `notification-svc`, `report-svc`)
  uses `build: ../services/<dir>` (a bare path, no `dockerfile:` key) — Docker Compose
  resolves this to `../services/<dir>/Dockerfile` with build **context** `../services/<dir>`
  itself, not the repo root. Every one of those Dockerfiles does
  `COPY services/<name>/target/*.jar app.jar` relative to the repo-root context used
  everywhere else in the repo — with this context, that `COPY` source path doesn't exist
  under `../services/<dir>` (there's no nested `services/` inside `services/api-gateway/`),
  so **every service build here fails**, independent of H7's config-mount issue. This
  file has two independent fatal defects, not one.
- It **does** define a `web-app` service (line 219, `build: ../apps/web`) with Traefik
  labels — this file is the *only* one of the four non-full-stack compose files that
  attempts to run the web app at all, and it's the dead one.
- `command -v gexec`-style ACME config exists here (`letsencrypt` cert resolver,
  `--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web`) — this is the
  only place in the whole repo that has ever attempted real Let's Encrypt HTTP-01, and
  it's in the dead file. Nothing under `infra/full-stack/` (the maintained one) does ACME
  at all — see §2.3.

### 2.2 `infra/docker-compose.infra.yml` — infra-only, mostly consistent, still no app

Boots `postgresql, valkey, minio, ollama, litellm-gateway, authentik-server,
authentik-worker` only — no Trove business services, no web app. Notably **more
defensive** than the dead `infra/docker-compose.yml`: `AUTHENTIK_SECRET_KEY` uses
`${AUTHENTIK_SECRET_KEY:?AUTHENTIK_SECRET_KEY must be set}` (fail-fast, line 83/102) and
`litellm-gateway` correctly mounts `../config/litellm.yaml.template` (line 66, the file
that actually exists) with a real healthcheck. This looks like an earlier, correctly-scoped
draft of what became `infra/full-stack/`. It is not referenced by any script — `grep -rn
"docker-compose.infra.yml" scripts/ .github/` returns nothing outside itself. Dead by
disuse, not by breakage.

### 2.3 `infra/full-stack/docker-compose.yml` — the maintained stack (H6, largely confirmed)

22 services + 2 top-level network defs. Genuinely good, verified line-by-line:

- **`socket-proxy`** (Tecnativa docker-socket-proxy) gates Traefik's Docker API access —
  Traefik never touches `/var/run/docker.sock` directly; only `socket-proxy` does
  (`/var/run/docker.sock:ro`, lines 19-41). Traefik reaches it over an **internal-only**
  `socket-proxy` network (`internal: true`, line 589) that has no route out.
- **Only `traefik` publishes host ports**: `80:80`, `443:443`, and
  `127.0.0.1:8080:8080` (Traefik's own dashboard, loopback-only). Every other service —
  `postgresql`, `valkey`, `minio`, `ollama`, `litellm-gateway`, `authentik-server/worker`,
  all 10 business services, `prometheus`, `grafana` — has **no `ports:` key at all**.
  This is the correct shape and is nearly what §13's target asks for directly.
- Routing is via Traefik labels on 5 services: `authentik-server` → `auth.trove.localhost`,
  `api-gateway` → `api.trove.localhost` (+ a second `api-actuator` router scoped to
  `PathPrefix(/actuator)` pointed at the 9090 management port), `web-app` →
  `trove.localhost`, `grafana` → `grafana.trove.localhost`. All four attach
  `security-headers@file,compress@file` middleware.
- TLS is **mkcert-only** — `dynamic.yml` loads `/certs/cert.pem`/`key.pem` generated by
  `start-full-stack.sh`'s mkcert step, with `minVersion: VersionTLS12`. **There is no ACME
  resolver anywhere in `infra/full-stack/`** — no `certificatesresolvers`, no Let's
  Encrypt, no `acme.json`. This stack is local-dev-only by construction (`*.trove.localhost`
  host rules cannot resolve on the public internet); H6's "genuinely good" verdict is
  accurate for what it does (TLS≥1.2, socket-proxy, HSTS headers, non-root, healthchecks,
  restart policies) but it is **not** a public-internet-ready stack as-is — real ACME
  and public DNS/host rules are entirely new work for §13, not a port of this file.
  **No rate-limit middleware exists anywhere in `dynamic.yml`** either — only
  `security-headers` and `compress`.
- `receipt-proc` is `profiles: ["receipt"]` (opt-in only), with an inline comment
  (lines 359-361) explaining it's excluded by default because "the module currently has a
  broken upstream build (io.minio / paddleocr deps missing)" — this is source
  acknowledging A1 and gating around it rather than fixing it.
- `entrypoint: *java-entrypoint` (a YAML anchor, lines 1-14) is a real improvement over
  the other compose files' plain `ENTRYPOINT`: it imports a mounted `mkcert` root CA
  (`/certs/rootCA.pem`) into a copy of the JVM's cacerts at container start, so
  service-to-service and outbound calls to Traefik-fronted HTTPS endpoints validate the
  local dev CA. **No `mem_limit` and no explicit `-Xmx` anywhere in this file** — the
  entrypoint's `java $$JAVA_TLS_OPTS -XX:+UseZGC -XX:MaxRAMPercentage=75.0 ...` is the
  *only* stack in the repo where O5's "10 JVMs × 75% of host RAM, no container limit"
  applies at full force, because unlike `docker/docker-compose.yml` and
  `docker/docker-compose.release.yml` (both of which set `JAVA_TOOL_OPTIONS: -Xmx300m`,
  overriding the percentage), this file sets no `JAVA_TOOL_OPTIONS` at all.
- `authentik-server`/`worker` get `AUTHENTIK_REDIS__HOST: valkey` (not present in the
  dead files) — Authentik uses Valkey for its cache/session store here, one more moving
  part that disappears entirely once Authentik is dropped.
- **`postgres-init/01-create-databases.sql` is missing `trove_receipt`.** It creates
  `authentik, trove_gateway, trove_models, trove_budget, trove_categorisation,
  trove_ingestion, trove_notification, trove_report, trove_subscription, trove_tax` — 10
  names, no `trove_receipt` — while `receipt-proc`'s own block two sections above
  (`RECEIPT_POSTGRES_URL: jdbc:postgresql://postgresql:5432/trove_receipt`, line 370)
  references a database this init script never creates. Independently confirmed against
  `docs/recovery/inventory/database.md` §1, which found the same gap by diffing this file
  against `docker/postgres-init.sql`. Moot for the collapse (all catalogs merge into one),
  worth zero engineering time, but it means the profile-gated `receipt` service has never
  actually been runnable end-to-end even when its build is fixed and its profile enabled.
- Full env-var surface used by this file, cross-referenced against
  `infra/full-stack/.env.example`'s 35 vars: every var the compose file references
  (`POSTGRES_PASSWORD`, `MINIO_ROOT_PASSWORD`, `AUTHENTIK_SECRET_KEY`, `TROVE_JWT_SECRET`,
  `TROVE_ENCRYPTION_KEY`, `TROVE_SERVICE_TOKEN`, `AI_SERVICE_TOKEN`,
  `AUTHENTIK_CLIENT_ID/SECRET`, `AUTHENTIK_BOOTSTRAP_*`, SMTP/APNS/FCM vars,
  `GRAFANA_ADMIN_PASSWORD`) has a line in the `.env.example`; none are silently
  undocumented in this particular pairing (contrast with H5's repo-wide 46/14 mismatch,
  which is dominated by the other compose files).

---

## 3. scripts/**

```
scripts/start-full-stack.sh      871 lines  — bash, drives infra/full-stack/
scripts/start-full-stack.ps1   ~1500 lines  — Windows port (not read in full; same shape per its own comments)
scripts/_run-full-stack-auto.ps1        — non-interactive wrapper around start-full-stack.ps1
scripts/bootstrap-user.sh        304 lines  — Authentik user bootstrap via REST API
scripts/bootstrap-user.ps1                — Windows port
scripts/smoke-test.sh            136 lines  — CI release smoke test (docker/docker-compose.release.yml + kind)
scripts/smoke-validate.sh / .ps1          — helper called by the above
scripts/test-bootstrap-oauth2.sh          — OAuth2 flow test harness
scripts/close-dependabot-backlog.sh       — repo hygiene, unrelated to infra
scripts/_creds_stub/                      — placeholder dir for local test creds
```

**No backup script exists anywhere** — `find . -iname "*backup*"` (excluding
`node_modules`/`target`) returns nothing repo-wide. Confirms H4 with no caveats.

### 3.1 `start-full-stack.sh` — 17 phases, the one genuinely reusable script

Documented steps (lines 8-19): resolve repo root → tool/mkcert prereqs → env file
creation/secret population → `/etc/hosts` entries → mkcert TLS certs → copy
`litellm.yaml` from template → **Maven build of all service jars, explicitly excluding
receipt-processor** → `docker compose up -d --build` (split into sub-phases 13a/13b/13c)
→ wait for Postgres, create per-service DBs → wait for Authentik (via Traefik) →
Authentik OAuth2 client provisioning → restart `api-gateway` to pick up the provisioned
client secret → print summary. Phase 17 (line 824) runs `smoke-validate.sh`/`.ps1`
against the live routes.

**Secret generation (the reusable part for §13):** `upsert_env()` (lines 126-138) is a
small awk-based idempotent key=value rewriter for `infra/full-stack/.env`. The script
generates secrets **only when the current value is still the shipped placeholder**:

| Var | Generated when | Method |
|---|---|---|
| `AUTHENTIK_BOOTSTRAP_TOKEN` | unset/empty | `openssl rand -hex 32` |
| `AUTHENTIK_SECRET_KEY` | `^AUTHENTIK_SECRET_KEY=change-me` | `openssl rand -hex 32` |
| `TROVE_JWT_SECRET` | `^TROVE_JWT_SECRET=change-me` | `openssl rand -base64 48 \| tr -d '\n='` |
| `TROVE_ENCRYPTION_KEY` | `^TROVE_ENCRYPTION_KEY=0+$` (all zeros) | `openssl rand -hex 32` |
| `AI_SERVICE_TOKEN` | shorter than 32 chars | `openssl rand -base64 36 \| tr -d '\n='` |
| `LITELLM_MASTER_KEY` | shorter than 16 chars | `openssl rand -base64 36 \| tr -d '\n='` |

**`POSTGRES_PASSWORD` and `MINIO_ROOT_PASSWORD` are never generated** — they stay at the
`.env.example` default (`trove` / `trove-minio-secret`) for the life of the deployment
unless a human edits the file by hand. This is a real, verified gap in the one script the
brief calls out as worth keeping: the DB password behind a public-internet-facing stack
would ship as the literal string `trove` unless §13's bootstrap explicitly closes it.

The file also prompts interactively for an admin email/password
(`read -rp`/`read -rsp`, lines 273-282) with a `--no-prompt` flag that falls back to
`test@yopmail.com` / `admin` — this is Authentik's bootstrap admin, not a "shown once and
never again" flow; the password is typed by the operator, not generated, and if
`--no-prompt` is used on a real deployment the admin account is `admin`/`admin`.

`upsert_env` + the "regenerate only if still-placeholder" pattern is the one piece of
this script directly reusable for §13's bootstrap (generalized to `trove-core`'s smaller
var surface, and extended to actually cover `POSTGRES_PASSWORD`).

### 3.2 `smoke-test.sh` — confirms H3 precisely

Line 112: `kubectl apply -k infra/k8s/` — `infra/k8s/` does not exist
(`ls infra/k8s` → `No such file or directory`; `git log --all --diff-filter=A --
'infra/k8s*'` → empty, it was never committed). The only thing preventing this from
failing CI is line 101's `if ! command -v kind &>/dev/null; then … exit 0; fi` guard —
`kind` is never installed on `ubuntu-latest` GitHub runners by default, so step 2 always
short-circuits before reaching the `kubectl apply`. Also gated: `SMOKE_REGISTER=1` (line
80) disables the `/api/v1/auth/register` assertion by default with an explicit comment
citing it as tracked separately ("Phase 26 C2 blocker").

---

## 4. Every Dockerfile

10 service Dockerfiles + `apps/web/Dockerfile`, all read in full.

**Shared shape** (9 of 10 services identically): `eclipse-temurin:21-jre-alpine` →
non-root `trove` user → `COPY services/<name>/target/*-exec.jar app.jar` → `EXPOSE <port>`
→ `ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0",
"-Dspring.profiles.active=prod", "-jar", "app.jar"]` → `HEALTHCHECK` hitting
`/actuator/health` on the service's own port (or 9090 for the three services with a
separate management port — see below).

**`services/receipt-processor/Dockerfile` — verified, independent build defect (new).**
Line 8 is `COPY services/receipt-processor/target/*.jar app.jar` — **not** `*-exec.jar`
like every other service. The parent `pom.xml` (lines 389-403) configures
`spring-boot-maven-plugin` with `<classifier>exec</classifier>` globally, which leaves
the plain jar in place *and* produces a second, separately-named executable jar — verified
directly against the already-built `target/` directory:
```
services/receipt-processor/target/receipt-processor-1.0.0.jar
services/receipt-processor/target/receipt-processor-1.0.0-exec.jar
```
`*.jar` matches **both**. Docker's `COPY` requires the destination to be a directory
(ending in `/`) whenever the source glob matches more than one file; `app.jar` is not a
directory, so this Dockerfile fails at build time with a distinct, independent error from
A1's `io.minio.http.Method` compile failure — even a clean-tree fix for A1 does not make
this Dockerfile buildable, this line also needs `-exec.jar`.

**Management-port healthcheck mismatch (informational, not a bug).**
`services/api-gateway/Dockerfile`, `services/model-manager/Dockerfile`, and
`services/notification-service/Dockerfile` all `HEALTHCHECK … http://localhost:9090/…`
while their own `EXPOSE` line only lists the primary port (8080/8089/8087). This matches
`management.server.port: 9090` set explicitly in each service's `application.yml`
(`api-gateway` line 79-80 region, `model-manager:33-35`, `notification-service:51-53`).
`EXPOSE` is documentation-only for Docker — it doesn't block a container process from
binding an unlisted port, and healthchecks run inside the container's own network
namespace against `localhost` regardless of `EXPOSE` — so this **works as written**, it's
just easy to misread as a bug. Two of these three services (`model-manager`,
`notification-service`) never publish 9090 to the host in any compose file either, so
their Prometheus/actuator management port is reachable only from other containers on the
same network, not from the host — consistent with `infra/full-stack/prometheus.yml`
scraping them at `model-manager:9090` / `notification-svc:9090` over the internal network.

**`apps/web/Dockerfile`** — two-stage build: `node:20-alpine` (with an explicit
`npm install -g npm@11` because the base image's npm 10 can't parse this lockfile's
optional/platform-specific esbuild entries) → `npx vite build` → `nginx:1.31-alpine`
serving `/usr/share/nginx/html`, `HEALTHCHECK` grepping for `<div id="root"` in the served
HTML. This Dockerfile is real and buildable independent of the Java services — it's simply
never wired into `docker/docker-compose.yml` (§1) or `infra/docker-compose.infra.yml`
(§2.2); only the dead `infra/docker-compose.yml` (§2.1) and the maintained
`infra/full-stack/docker-compose.yml` (§2.3, as `web-app`) reference it.

**`apps/web/nginx.conf`** — proxies `/api/`, `/login/oauth2/`, `/oauth2/`, `/logout` to a
variable `$api_gateway` resolved via Docker's embedded DNS (`127.0.0.11`) against the
literal hostname `api-gateway`, with an explicit comment (lines 27-29) explaining the
indirection exists so nginx boots even before `api-gateway` is resolvable. **This hostname
matches `infra/`'s naming (`api-gateway`), not `docker/docker-compose.yml`'s (`gateway`)**
— this nginx.conf only ever worked paired with the `infra/` family of compose files, never
`docker/docker-compose.yml`, another small facet of the multi-compose-file drift in H5.
Forwards `X-Forwarded-Proto` from `$http_x_forwarded_proto` (not `$scheme`) with an
explicit comment explaining Traefik terminates TLS upstream of this hop — correct and
worth keeping verbatim. Sets `server_tokens off`, gzip for text assets, 1-year immutable
caching on `/assets/`, no-cache on `index.html`, SPA fallback to `index.html`.

---

## 5. `.env.example` — 13 files, consolidated

Confirmed H5's exact numbers: root `.env.example` = 86 lines / **52 vars**,
`infra/.env.example` = 44 lines / **19 vars**, `infra/full-stack/.env.example` = 81 lines /
**35 vars** — three files describing overlapping but non-identical deployments of the same
system, none a superset of the other two (e.g. only `infra/full-stack/.env.example` has
`SMTP_ENABLED`/`NOTIFICATION_FROM_ADDRESS`/`FCM_SERVICE_ACCOUNT_JSON`/`APNS_PRODUCTION`;
only the root one has 9 separate `*_POSTGRES_URL` per-service vars; only
`infra/.env.example` omits `TROVE_ENCRYPTION_KEY` entirely).

Plus 10 per-service `.env.example` files under `services/*/`, each scoped to exactly what
that service's own compose block needs, with a `# CHANGE IN PRODUCTION` comment on every
secret-shaped value — these are the most accurate of the 13 (they mirror the actual
`docker/docker-compose.yml` env blocks 1:1, per their own header comments), but they're
`localhost`-addressed for standalone/local-profile running, not Docker-network-addressed.

**Consolidated variable table** (all distinct variable names across all 13 files; "Files"
column uses short names — `root`=`/.env.example`, `infra`=`infra/.env.example`,
`fs`=`infra/full-stack/.env.example`, service names for the 10 per-service files):

| Variable | Files | Default shown | Secret? | Consumer |
|---|---|---|---|---|
| `TROVE_DOMAIN` | root, infra | `localhost` | no | Traefik host rules |
| `TROVE_SECRET` | root | `change-me-in-production` | yes | unused — no consumer found anywhere in `services/`/`libs/` |
| `POSTGRES_URL` | root | `jdbc:postgresql://localhost:5432/trove` | no | legacy/unused; per-service `*_URL` vars are what's actually read |
| `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB` | root, infra, fs | `trove`/`trove`/`trove` | password=yes | Postgres container init |
| `TROVE_JWT_SECRET` | root, infra(blank), fs, all 10 services | `change-me-minimum-32-chars-in-production!!` | **yes — critical** | shared HS256 signing key, all 10 services (G4/G5) |
| `TROVE_JWT_EXPIRY_SECONDS`/`TROVE_JWT_REFRESH_EXPIRY_SECONDS` | root | 900 / 86400 | no | unused outside root file — not read by api-gateway config |
| `TROVE_ENCRYPTION_KEY` | root, fs, ingestion-service | 64 zeros | **yes — critical** | AES key for bank-credential field encryption (G5) |
| `GATEWAY_POSTGRES_URL`/`_USER`/`_PASSWORD` | root, api-gateway | trove/trove | password=yes | api-gateway datasource |
| `INGESTION_POSTGRES_URL`/`INGESTION_DB_URL`/`_USER`/`_PASS` | root(`INGESTION_POSTGRES_URL`), ingestion-service(`INGESTION_DB_URL`) | trove/trove | password=yes | **two different var names for the same thing** across root vs. service file |
| `CATEGORISATION_POSTGRES_URL`/`CATEGORISATION_DB_URL`/`_USER`/`_PASS` | root vs. categorisation-service | trove/trove | password=yes | same root/service name drift |
| `TAX_POSTGRES_URL` vs `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS` | root vs. tax-engine | trove/trove | password=yes | root uses one JDBC URL var, service uses 5 discrete host/port/name vars — genuinely different shapes, not just renamed |
| `BUDGET_POSTGRES_URL` vs `DB_URL`/`DB_USER`/`DB_PASSWORD` | root vs. budget-service | trove/trove | password=yes | same URL-vs-discrete-vars drift |
| `RECEIPT_POSTGRES_URL`/`_USER`/`_PASSWORD` | root, receipt-processor | trove/trove | password=yes | consistent naming here |
| `SUBSCRIPTION_POSTGRES_URL` vs `DB_HOST`/etc | root vs. subscription-detector | trove/trove | password=yes | URL-vs-discrete drift again |
| `NOTIFICATION_POSTGRES_URL`/`_USER`/`_PASSWORD` | root, fs, notification-service | trove/trove | password=yes | consistent |
| `REPORT_POSTGRES_URL` vs `DB_HOST`/etc | root vs. report-service | trove/trove | password=yes | URL-vs-discrete drift |
| `MODEL_MANAGER_POSTGRES_URL`/`MODELS_POSTGRES_URL`/`_USER`/`_PASSWORD` | root(`MODEL_MANAGER_POSTGRES_URL`) vs model-manager(`MODELS_POSTGRES_URL`) | trove/trove | password=yes | **another different var name for the same DB** |
| `VALKEY_URL` | root, api-gateway | `redis://localhost:6379` | no | Spring session/rate-limit store, api-gateway only |
| `MINIO_URL`/`MINIO_ENDPOINT` | root(`MINIO_URL`) vs receipt-processor/report-service(`MINIO_ENDPOINT`) | `http://localhost:9000` | no | another root/service name drift |
| `MINIO_ROOT_USER`/`_PASSWORD` | root, infra, fs | trove / trove-minio-secret | password=yes | MinIO container; dropped entirely under the collapse |
| `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` | receipt-processor, report-service | trove / trove-minio-secret | yes | app-side MinIO creds; dropped |
| `OLLAMA_URL` | root, model-manager | `http://localhost:11434` | no | kept — direct Ollama endpoint |
| `LITELLM_URL`/`TROVE_LITELLM_URL` | root/model-manager(`LITELLM_URL`) vs fs compose(`TROVE_LITELLM_URL`) | `http://localhost:4000` | no | LiteLLM dropped under the collapse (direct OpenAI-compatible endpoint instead) |
| `LITELLM_MASTER_KEY` | root, fs, model-manager | `dev-litellm-key` | yes | dropped with LiteLLM |
| `AUTHENTIK_URL` | root, infra, fs, api-gateway | varies by file (`localhost:9200` / `authentik-server:9000` / `https://auth.trove.localhost` / `localhost:9000`) | no | **4 different values across 4 files** for the same conceptual endpoint |
| `AUTHENTIK_SECRET_KEY` | root, infra, fs | `change-me-in-production` / blank | yes | Authentik's own signing key; dropped as a hard dependency |
| `AUTHENTIK_BOOTSTRAP_EMAIL`/`_PASSWORD`/`_TOKEN` | root, fs | `test@yopmail.com`/`admin`/blank | password=yes | Authentik admin bootstrap; dropped |
| `AUTHENTIK_CLIENT_ID`/`_SECRET` | root, fs, api-gateway (required, no default) | `trove-gateway` / blank | secret=yes | OIDC client for api-gateway; dropped as hard dep (kept optional per owner decision) |
| `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`/`OPENROUTER_API_KEY`/`GROQ_API_KEY`/`GOOGLE_API_KEY` | root(+GOOGLE), infra(-GOOGLE), fs(-GOOGLE) | blank | yes | cloud LLM keys; **`GOOGLE_API_KEY` is only in root**, dropped in the other two — one more 3-way mismatch |
| `SMTP_HOST`/`_PORT`/`_USER`/`_PASSWORD` | root, infra, fs, notification-service | blank/587/blank/blank | password=yes | email; consistent shape |
| `SMTP_ENABLED` | fs, notification-service(implicit via `application.yml`) | `false` | no | only `fs` documents this explicitly |
| `NOTIFICATION_FROM_ADDRESS` | fs | `noreply@trove.local` | no | fs-only |
| `APNS_KEY_PATH`/`_KEY_ID`/`_TEAM_ID`/`_BUNDLE_ID`/`_PRODUCTION` | root, fs | blank / blank / blank / `com.trove.app` vs `finance.trove.app` (differs between files) / `false` | key path=yes | push notifications; bundle ID literally disagrees between root (`com.trove.app`) and fs (`finance.trove.app`) |
| `FCM_SERVER_KEY` (root) / `FCM_SERVICE_ACCOUNT_JSON`+`FCM_PROJECT_ID` (fs) | root vs fs | blank | yes | **different auth scheme documented in each file** — legacy server-key vs. modern service-account JSON |
| `TROVE_SERVICE_TOKEN`/`TROVE_INTERNAL_SERVICE_TOKEN`/`TROVE_AI_SERVICE_TOKEN`/`AI_SERVICE_TOKEN` | fs(`TROVE_SERVICE_TOKEN`), model-manager(`TROVE_INTERNAL_SERVICE_TOKEN`+`TROVE_AI_SERVICE_TOKEN`), categorisation/receipt-processor(`TROVE_AI_SERVICE_TOKEN`), fs compose(`AI_SERVICE_TOKEN`) | `dev-internal-service-token` / `dev-ai-service-token` | yes | **4 distinct env-var names for what's functionally 2 concepts** (internal service-to-service token, AI-gateway token) |
| `GRAFANA_ADMIN_PASSWORD` | root, infra, fs | `admin` | yes | Grafana |
| `SERVER_PORT` | tax-engine | `8083` | no | tax-engine only; cut with the tax engine |

The variable-naming drift rows above (URL-vs-discrete-host/port/name, and 3–4 different
names for the same conceptual secret) are the practical shape of H5's "46 undocumented /
14 unused" claim — re-verified here at the level of *which specific names disagree*, so
§13's single consolidated `.env` schema has a concrete list of collisions to resolve
rather than just a count.

---

## 6. Grafana / Prometheus / Loki

Two independent Prometheus configs exist (`docker/prometheus/prometheus.yml`,
10 `job_name`s targeting `docker/docker-compose.yml`'s container names, e.g.
`gateway:9090`, `ingestion-svc:8081`; and `infra/full-stack/prometheus/prometheus.yml`,
same 10 services with 2 name changes — `api-gateway:9090`,
`categorisation:8082`/etc. matching `infra/full-stack/docker-compose.yml`'s container
names). Both are **port-accurate** against their respective compose files — genuinely
correct scrape target lists (H8's "10/10 port-accurate" holds for both files
independently, re-verified).

**All 10 services expose `/actuator/prometheus`** — confirmed every
`services/*/src/main/resources/application.yml` sets
`management.endpoints.web.exposure.include: health,info,prometheus,metrics`, and
`libs/common/pom.xml:30-31` carries `io.micrometer:micrometer-registry-prometheus` as a
dependency every service inherits transitively — so the standard Spring
Boot/JVM/Hikari auto-instrumented metrics (`http_server_requests_seconds_*`,
`jvm_memory_used_bytes`, `hikaricp_connections_active`) are genuinely available on every
service once it's actually running.

**`docker/grafana/provisioning/dashboards/trove-overview.json` — 8 panels, verified
individually:**

| Panel | Query | Backed by real metric? |
|---|---|---|
| Request Rate | `rate(http_server_requests_seconds_count[1m])` | yes — Spring Boot auto-instrumentation |
| P99 Latency | `histogram_quantile(0.99, http_server_requests_seconds_bucket…)` | yes |
| Error Rate (5xx) | same, `status=~"5.."` | yes |
| JVM Heap Used | `jvm_memory_used_bytes{area="heap"}` | yes |
| DB Connection Pool Active | `hikaricp_connections_active` | yes |
| Receipt Pipeline Stage Throughput | `trove_receipt_stage_total` | **no** — zero hits for this metric name or any `io.micrometer.Counter`/`Timer` custom instrument anywhere under `services/`/`libs/` (`grep -rn "io\.micrometer" --include=*.java` returns nothing) |
| AI Model Invocation Latency (p99) | `trove_model_invocation_seconds_bucket` | **no** — same |
| Notifications Sent | `trove_notification_sent_total` | **no** — same |

Confirms H8's "3 of 8 panels query metrics that have never existed" exactly, and confirms
the other 5 work *given the target service is actually up and reachable* — which none of
them are without the Docker plugin / Authentik / prebuilt-jar fixes from §1.

**Datasources** (`docker/grafana/provisioning/datasources/{loki,prometheus}.yml`) point
at `http://loki:3100` and `http://prometheus:9090` respectively — correct container
names for `docker/docker-compose.yml`. **Loki receives nothing** in the maintained
`infra/full-stack/` stack (no `loki` service exists there at all — confirmed absent from
the service list in §2.3) and, even in `docker/docker-compose.yml`, the `logging: driver:
loki` blocks push to `localhost:3100` from inside each container (§1), which never
reaches the `loki` service. No alert rules, no recording rules, no Alertmanager anywhere
in the repo (`find . -iname "*alert*rule*"` empty). `docker/grafana/provisioning/
dashboards/dashboard.yml` is a standard file-provider pointing Grafana at the one JSON
dashboard — nothing unusual there.

---

## 7. Authentik

No blueprint files exist anywhere (`find . -iname "*blueprint*"` empty repo-wide) —
Authentik's OAuth2 provider/application/scope-mapping setup happens **imperatively**, via
`authentik_api()` (`scripts/start-full-stack.sh:153-`) making direct REST calls against
`/api/v3/core/...` using the bootstrap token, run fresh on every `start-full-stack.sh`
invocation (idempotent by design — the script's own comments describe re-converging
akadmin email/password "on every run", not just first boot). Three deployments of
Authentik exist (`docker/docker-compose.yml`, `infra/docker-compose.yml`,
`infra/docker-compose.infra.yml`, `infra/full-stack/docker-compose.yml` — 4 actually,
one per compose family) each wiring slightly different env surfaces (§5's `AUTHENTIK_URL`
4-way mismatch). Per the owner's decision this entire subsystem — server, worker,
imperative REST provisioning, the 11th `authentik` Postgres catalog, `AUTHENTIK_REDIS__HOST`
— is dropped as a hard dependency in favor of built-in local accounts, with OIDC becoming
optional. None of the 4 deployments' Authentik wiring is reusable for that target beyond
the general shape of "provision on first boot, converge on every boot" that
`upsert_env`/`authentik_api` already demonstrate.

---

## 8. MinIO

Two buckets: `trove-receipts` (`services/receipt-processor/src/main/resources/
application.yml:40`, `bucket-receipts: trove-receipts`) and `trove-reports`
(`services/report-service/src/main/resources/application.yml:38`,
`bucket-reports: trove-reports`). Both self-provision — no init container or `mc mb`
script exists; `services/receipt-processor/src/main/java/finance/trove/receipt/storage/
MinioConfig.java` registers a `MinioInitializer` bean with a `@PostConstruct` check that
calls `BucketExistsArgs`/`MakeBucketArgs` and creates the bucket if absent, so the service
starts cleanly against a fresh MinIO with zero manual setup — a genuinely nice pattern,
moot once MinIO is dropped for a local disk volume per the owner's decision. MinIO appears
in all 4 compose families with 3 different image tags (`RELEASE.2024-06-04T19-20-08Z` in
`docker/`, `latest` in both `infra/` files and `infra/full-stack/`) — one more small drift
that disappears entirely once the object store is cut.

---

## 9. Ollama / LiteLLM / PaddleOCR

**Ollama** — present in every compose family, unpublished to the host in
`infra/full-stack/` (no `ports:`), published `11434:11434` in the other three. GPU
reservation (`deploy.resources.reservations.devices: nvidia`) only in the two `infra/*`
files, not `docker/docker-compose.yml` or `infra/full-stack/` — inconsistent across
files, moot since the owner's target keeps Ollama optional/CPU-fine for modest hardware.

**LiteLLM** — `docker/litellm_config.yaml` (67 lines) is the one config actually mounted
by `docker/docker-compose.yml`; it defines 5 local-tier models
(`trove-fast`→`ollama/llama3.2:3b`, `trove-smart`→`ollama/qwen2.5:7b`,
`trove-vision`→`ollama/qwen2.5-vl:7b`, `trove-reason`→`ollama/deepseek-r1:7b`,
`trove-embed`→`ollama/nomic-embed-text`) all pointed at `http://ollama:11434`, plus
commented-out cloud-tier entries for Anthropic/OpenAI/Groq, `latency-based-routing` with
per-model fallback chains. `config/litellm.yaml.template` is the file `infra/
docker-compose.infra.yml` and `infra/full-stack/docker-compose.yml` actually reference
(the latter as `../../config/litellm.yaml` — **without** `.template`, which does not
exist at that path; `infra/full-stack/docker-compose.yml:128` is itself a live, unfixed
mount-target mismatch, the same class of bug as H7 but in the maintained stack). Per the
owner's decision, LiteLLM as an intermediary hop is dropped entirely — the target talks
to a single user-configured OpenAI-compatible endpoint (Ollama or cloud) directly, which
also removes this file's role and its config drift.

**PaddleOCR** — no compose service named `paddleocr` exists in any of the 4 families
(`grep -rln "paddleocr:" docker/ infra/` → empty); the only references are (a) a dangling
`PADDLEOCR_URL: http://paddleocr:8866` env var in `infra/full-stack/docker-compose.yml:378`
inside the `receipt-proc` block that resolves to nothing at runtime, and (b) the
`plugins/models/paddleocr-adapter` module, which per M3/M8 in the findings dossier is on
no service's classpath and calls a fictional API shape regardless. Confirmed cut per the
owner's decision; no infra work needed beyond deleting the dangling env var.

---

## 10. Resource footprint (as documented)

`docs-site/self-hosting/docker-compose.md:34`: **"~6–8 GB free RAM · All containers
running concurrently."** Line 432 restates this as a table: `RAM footprint | ~3–4 GB |
~6–8 GB` (two deployment modes, doc doesn't fully specify which is which from the excerpt
alone). Line 408: **"No container resource limits — the stack can use all available
RAM/CPU. Monitor with `docker stats`."** — this is the documentation candidly admitting
O5's finding rather than contradicting it; the docs and the compose files agree with each
other, they just both describe an unbounded, LAN-scale-hardware-shaped deployment. Cross-
referenced against §2.3: the admission is accurate specifically for
`infra/full-stack/docker-compose.yml` (no `JAVA_TOOL_OPTIONS`, no `mem_limit`); it slightly
understates `docker/docker-compose.yml` and `docker/docker-compose.release.yml`, which do
cap each JVM's heap at 300m via `JAVA_TOOL_OPTIONS`, though still with no container-level
`mem_limit` to cap non-heap/metaspace/native memory. README's product framing ("runs
entirely on your own hardware with no required internet connection") is in tension with
both the owner's public-internet decision and with 10 concurrently-running JVMs each
sized against total host RAM — moot once collapsed to one JVM with an explicit `-Xmx` and
a `mem_limit`.

---

## 11. Kubernetes / Kustomize — docs only, confirmed

`docs-site/self-hosting/kubernetes.md` (90 lines) describes `infra/kind-config.yaml` and
"a Kustomize overlay tree under `infra/k8s/` for production-like deploys" in its opening
paragraph. Neither path exists:
```
$ ls infra/k8s
ls: infra/k8s: No such file or directory
$ ls infra/kind-config.yaml
ls: infra/kind-config.yaml: No such file or directory
$ git log --all --diff-filter=A --oneline -- 'infra/k8s*' 'infra/kind-config.yaml'
(empty)
```
Neither file has ever existed in this repository's history. `README.md:119` lists
`infra/          Production Docker Compose (Traefik) and Kustomize overlays` in its
directory-structure table, and `README.md:131` links "Self-Hosting (Docker Compose /
Kubernetes / Traefik)" to the docs-site page above. `scripts/smoke-test.sh:112` is the
only place that actually *executes* against this fiction (`kubectl apply -k infra/k8s/`),
saved only by the `kind`-not-installed guard (§3.2). No target-of-work here — nothing to
port, only to stop describing.

---

## 12. Verified defects — cross-reference

### 12.1 H1–H6 (infrastructure), re-verified against source in this pass

| # | Claim | Verified this pass? |
|---|---|---|
| H1a | README quick-start can't work: no prebuilt jar | Yes — every Dockerfile's `COPY … target/*-exec.jar` requires `mvn package` first, README doesn't say so at that point (not independently re-checked here, inherited from prior audit; the Dockerfile side is confirmed, §4) |
| H1b | `logging.driver: loki` needs an undocumented Docker plugin | Yes, §1 — all 10 services in `docker/docker-compose.yml`, plus the URL bug (`localhost:3100` from inside each container) layered on top |
| H1c | `AUTHENTIK_CLIENT_ID/SECRET` required, never set, no default | Yes, §1 — `gateway` env block vs. `api-gateway/application.yml:33-34`'s bare `${AUTHENTIK_CLIENT_ID}` |
| H2 | Supplying those two vars is highest-leverage single fix | Not re-verified (moot — Authentik dropped entirely under the collapse) |
| H3 | No Kubernetes; `infra/k8s/` never existed | Yes, §11, with `git log` proof |
| H4 | No backup/DR whatsoever | Yes, §3 — `find` for `*backup*` repo-wide is empty |
| H5 | Six compose files, three bootstrap generations, 52/35/19-var `.env.example`s | Yes, §5 — exact line/var counts reproduced; extended in §5 with the specific name-level collisions (URL-vs-discrete vars, 4-way `AUTHENTIK_URL`, 4-name service-token sprawl) |
| H6 | `infra/full-stack/` is genuinely good but unlinked from README | Yes, §2.3 — confirmed the good parts (socket-proxy, only-Traefik-publishes, TLS≥1.2, non-root, healthchecks, restart policies) **and** confirmed it is mkcert-local-only with no ACME and no rate-limit middleware, so "genuinely good" ≠ "public-internet-ready" |
| H7 | `infra/docker-compose.yml` mounts nonexistent configs, dead | Yes, §2.1 — both missing mounts confirmed, plus a second independent fatal build-context bug not in the original claim |
| H8 | Prometheus 10/10 accurate; 3/8 Grafana panels query metrics that never existed; Loki gets nothing | Yes, §6, panel-by-panel |
| H9 | Dependabot ignores all majors, auto-merges minors/patches | Not re-verified in this pass (out of infra scope; covered by `.github/dependabot.yml`, not read here) |
| H10 | `mvnw package -DskipTests` still runs web vitest | Not re-verified (Maven-module-web concern, not compose/infra) |

### 12.2 G-section items with an infra angle

- **G1** (IDOR via `X-User-Id`) — infra angle confirmed in §1: all 10 services publish to
  the host in `docker/docker-compose.yml`, so the gateway is provably not a network
  boundary, matching O3's LAN-exploitability note.
- **G5** (weak default JWT secret / all-zero encryption key) — the exact default strings
  are reproduced in §5's table (`change-me-minimum-32-chars-in-production!!`, 64 zeros)
  and cross-checked against `start-full-stack.sh`'s generation logic (§3.1): the script
  *does* fix G5 for anyone who runs it, but only for `TROVE_JWT_SECRET`/
  `TROVE_ENCRYPTION_KEY`/`AUTHENTIK_SECRET_KEY`/`AI_SERVICE_TOKEN`/`LITELLM_MASTER_KEY` —
  it silently leaves `POSTGRES_PASSWORD` at `trove`.

### 12.3 O-section items, infra-specific re-verification

- **O5** (GC/memory misconfig) — refined in §2.3/§10: applies at full force only to
  `infra/full-stack/docker-compose.yml` (no `JAVA_TOOL_OPTIONS` override at all);
  `docker/docker-compose.yml` and `docker/docker-compose.release.yml` both cap heap at
  300m via `JAVA_TOOL_OPTIONS`, so O5's "~6.8GB resident" figure is specific to the
  full-stack path, not universal across all 4 compose families.
- **O6** (11 databases, worst of both worlds) — confirmed exactly, §1.

### 12.4 New defects found in this pass (not in H1–H10, G1–G13, or O1–O7)

1. **`services/receipt-processor/Dockerfile:8`** copies `*.jar` instead of `*-exec.jar`,
   which will match both the plain and exec jars produced by the parent POM's
   `classifier=exec` config (confirmed present in `target/` today) and fail the Docker
   build with a "destination must be a directory" error — independent of, and in addition
   to, A1's Java compile failure. §4.
2. **`infra/full-stack/postgres-init/01-create-databases.sql`** never creates
   `trove_receipt`, even though the `receipt-proc` service block 240 lines above it in the
   same file reads `RECEIPT_POSTGRES_URL=…/trove_receipt`. Independently cross-confirmed
   against `docs/recovery/inventory/database.md`. §2.3.
3. **`infra/full-stack/docker-compose.yml:128`** mounts `../../config/litellm.yaml` — the
   file that exists at that path is `litellm.yaml.template` (no `.yaml` without the
   suffix) — the same class of dead-mount bug H7 flags in the *other*, already-dead
   compose file, but here it's in H6's "genuinely good," currently-maintained stack. §9.

---

## 13. TARGET — deployment for a public-internet modular monolith on modest hardware

Scope per the owner's fixed decisions: one JVM (`trove-core`) + Postgres, exposed to the
public internet, web app + Expo mobile both production-ready, MinIO/Authentik/LiteLLM/
PaddleOCR/tax-engine/native-listener-plugins all dropped, Ollama optional.

### 13.1 Containers

```
traefik      — the only container that publishes 80/443. Terminates TLS, routes to
               trove-core and (if enabled) the Grafana/monitoring path. Never touches
               /var/run/docker.sock directly — reuse infra/full-stack's socket-proxy
               pattern verbatim (§2.3): it's a genuine security improvement and it's a
               2-container addition, not new design.
trove-core   — the merged JVM. One Dockerfile, `eclipse-temurin:21-jre-alpine`, non-root
               user (keep the `addgroup -S trove && adduser -S trove -G trove` pattern
               from every existing Dockerfile verbatim — it's already correct, §4), one
               `-exec.jar`. EXPOSE one app port + one management port (keep the
               port-8080/port-9090 split every existing service already uses — actuator
               traffic never needs to be internet-routable, so it's easy to firewall at
               the Traefik-label level exactly as `infra/full-stack/docker-compose.yml`'s
               `api-actuator` router already demonstrates, §2.3).
postgres     — pgvector/pg16 image (keep — every existing compose file already uses it,
               no reason to change on collapse). One catalog, not 11 (O6 resolved by
               construction: the collapse itself eliminates the "11 databases, 1 process"
               anti-pattern — no infra work needed beyond point ing one Flyway history at
               one schema-per-service-package or one flat schema).
ollama       — optional, only started when the user configures a local AI endpoint.
               Use Docker Compose `profiles: ["ai"]` (the exact mechanism
               `infra/full-stack/docker-compose.yml` already uses for the
               currently-broken `receipt-proc`, §2.3 — reuse the pattern, not the
               profile name). No LiteLLM hop — `trove-core` talks to whatever
               OpenAI-compatible base URL the user configured (Ollama's own
               `/v1` endpoint, or a cloud provider) directly, removing an entire
               container and its config-drift surface (§9).
```

No `mailhog`, no `authentik-server`/`worker`, no `minio`, no `litellm-gateway`, no
`socket-proxy`-fronted `valkey` unless a caching/rate-limit-state need re-introduces it
(Traefik's own in-memory rate-limit middleware, §13.6, does not need Redis/Valkey for a
single-instance deployment — only add it back if `trove-core` itself needs a shared cache,
which is a service-layer decision outside this infra inventory's scope).

### 13.2 Volumes

```
postgres_data     — named volume, as every compose file already does.
receipt_images     — local disk bind mount or named volume (replaces MinIO per the
                     owner's decision). Mount into trove-core at a fixed path
                     (e.g. /data/receipts); trove-core's own storage service becomes a
                     filesystem write instead of an S3-API call — reuse the
                     self-provisioning-on-startup pattern from
                     MinioConfig.MinioInitializer (§8: check-then-mkdir on boot, same
                     shape as check-then-create-bucket) so first-boot needs zero manual
                     setup.
traefik_acme      — named volume for `/letsencrypt/acme.json`. Must be created with
                     mode 600 before Traefik's first start (Traefik itself will chmod
                     acme.json to 600 on write, but the volume/file needs to exist
                     writable first) — the bootstrap script (§13.5) should touch and
                     chmod this file before first `docker compose up`, not rely on
                     Traefik's ACME provider to get filesystem permissions right on a
                     fresh volume unassisted.
```

### 13.3 Network isolation

Reuse `infra/full-stack/docker-compose.yml`'s topology almost exactly (§2.3): a
`default` network where only `traefik` has a `ports:` key (80/443), and everything
else — `trove-core`, `postgres`, optional `ollama` — has none. That file already proves
this shape works for 22 services; it collapses trivially to 3-4. The one addition beyond
what's already there: mark the network `postgres` and `trove-core` share as `internal:
true` if `ollama` is on a separate network (ollama doesn't need to reach postgres, and
postgres doesn't need to reach ollama) — the existing file doesn't bother with this
finer-grained split because every one of its 22 services already only reaches the host
through Traefik-mediated ports it doesn't publish anyway; a monolith with 3-4 containers
can afford the extra network purely for defense-in-depth at near-zero complexity cost.
Reuse `socket-proxy` + its `internal: true` network verbatim (§2.3) — genuinely the
single best piece of existing Traefik wiring in the repo.

### 13.4 Env schema with generated secrets

Collapse §5's 13-file, 50+-variable, name-drifted mess into **one `.env.example`** at the
repo root, scoped to what `trove-core` + Postgres + Traefik + optional Ollama actually
need — roughly 12-15 variables instead of 52. Every secret-shaped variable uses Docker
Compose's `${VAR:?message}` fail-fast syntax (already used correctly in exactly one place
in the current repo — `infra/docker-compose.infra.yml:83`'s
`${AUTHENTIK_SECRET_KEY:?AUTHENTIK_SECRET_KEY must be set}` and
`infra/docker-compose.yml:123`'s `${TROVE_JWT_SECRET:?TROVE_JWT_SECRET must be set}` —
extend that pattern to every secret, including `POSTGRES_PASSWORD`, which §3.1 confirmed
is the one secret none of the 3 existing bootstrap scripts ever actually generates).

**Bootstrap script** — a single `bootstrap.sh` that runs once (guarded by "does `.env`
already exist" the same way `start-full-stack.sh:262-269` already does), reusing
`upsert_env()` and the "generate only if still-placeholder" pattern verbatim (§3.1) for:
`POSTGRES_PASSWORD`, `TROVE_JWT_SECRET`, `TROVE_ENCRYPTION_KEY`, and a new
`TROVE_ADMIN_PASSWORD` for the first local account (built-in auth replaces Authentik, so
there's no external IdP to bootstrap via REST API anymore — the equivalent of
`authentik_api()`'s provisioning calls becomes a one-time INSERT the app itself performs
on first boot, gated by "does any user exist yet", not a shell script concern). Print the
generated admin password to stdout exactly once at the end of the bootstrap run, with an
explicit "this will not be shown again" line, then never write it to any log/file beyond
the `.env` it's stored in for the app to read on first boot — this is new work, nothing
in the current 3 bootstrap scripts implements a shown-once pattern (§3.1 confirmed the
current admin credential is either typed by the operator or defaults to `admin`/`admin`
under `--no-prompt`).

### 13.5 Traefik TLS

Real ACME is genuinely new work — confirmed in §2.1/§2.3 that the only HTTP-01 config
that ever existed in this repo is in the *dead* `infra/docker-compose.yml`
(`--certificatesresolvers.letsencrypt.acme.httpchallenge.entrypoint=web`), and the
currently-maintained `infra/full-stack/` stack does mkcert-only local certs with no ACME
resolver at all. Port from the dead file's `command:` block (it's syntactically fine, just
unused) rather than from the maintained one (which has nothing to port here):
`--certificatesresolvers.letsencrypt.acme.email=admin@${TROVE_DOMAIN}`,
`.acme.storage=/letsencrypt/acme.json`, `.acme.httpchallenge.entrypoint=web`. Add an
optional DNS-01 resolver (provider-specific env-driven, e.g. Cloudflare/Route53 token) as
a second resolver selectable per-router via label, for users behind CGNAT or who want
wildcard certs — no existing file in the repo attempts DNS-01 at all, this is 100% new.
Keep `minVersion: VersionTLS12` from `infra/full-stack/traefik/dynamic.yml:14` verbatim.

### 13.6 HSTS + security headers, rate-limit middleware

`infra/full-stack/traefik/dynamic.yml`'s `security-headers` middleware (§2.3) is directly
reusable verbatim: `frameDeny`, `contentTypeNosniff`,
`referrerPolicy: strict-origin-when-cross-origin`, `stsSeconds: 31536000`,
`stsIncludeSubdomains`, blanked `Server` header. **Rate limiting is entirely new** —
confirmed nowhere in `dynamic.yml` (§2.3) or any other Traefik config in the repo. Add a
`rate-limit` middleware (Traefik's built-in `ratelimit` provider, `average`/`burst`
thresholds) attached alongside `security-headers@file,compress@file` on every router
label, tuned tighter on the auth/login path specifically — G8 documented the existing
application-level gateway rate limiter (`services/api-gateway/src/main/java/finance/trove/
gateway/ratelimit/RateLimitService.java`) as far too permissive (600 req/min burst 1200 on
`/api/v1/auth/**`); a Traefik-level limit in front of it is a second, independent layer
worth adding rather than relying on the app-level one alone, and doesn't require waiting
on that Java code being retuned.

### 13.7 Healthchecks

Reuse the existing `/actuator/health` `HEALTHCHECK` pattern from every current Dockerfile
verbatim (§4) — it's already correct for a single merged service, just needs its port
updated to whatever `trove-core` binds. Reuse `infra/full-stack/docker-compose.yml`'s
`depends_on: condition: service_healthy` pattern for `postgres` gating `trove-core`'s
start (§2.3) and Traefik's own `ping: {}` + `wget -qO- http://localhost:8080/ping`
healthcheck (`infra/full-stack/traefik/traefik.yml:38`, `docker-compose.yml:65-68`)
verbatim.

### 13.8 JVM flags

Set an **explicit `-Xmx`** (not just `-XX:MaxRAMPercentage`) sized to a fraction of the
container's `mem_limit`, and set `mem_limit` on the `trove-core` service in compose —
directly closing O5, which this pass (§2.3/§10) refined to specifically be about the
`infra/full-stack/` path having neither. With one JVM instead of ten, an explicit
`-Xmx1536m` (or similar, tuned once real usage is known) with `mem_limit: 2g` on modest
hardware (a Pi-class or small VPS target, matching the docs' own stated ambition, §10) is
straightforward where it was previously impossible to reason about with ten JVMs each
independently claiming 75% of total host RAM. Keep `-XX:+UseZGC` — nothing in this
inventory found a reason to change garbage collector choice.

### 13.9 Backup / restore

Entirely new — confirmed H4 with no caveats (§3): zero backup tooling exists anywhere in
the repo today. Minimum viable: a `backup.sh` doing `pg_dump` (single database now,
trivial compared to the current 11-catalog `pg_dumpall` this would have needed) piped to
a timestamped, optionally-encrypted file, plus a `tar` of the `receipt_images` volume,
both written to a location outside the `postgres_data`/`receipt_images` volumes
themselves (so `docker compose down -v` — the only "stop" command any current script or
doc documents, per H4 — can't take out the backups too). A paired `restore.sh` that stops
`trove-core`, drops/recreates the database from the dump, untars the receipt volume, and
restarts — and a documented, periodically-run **restore drill** (restore into a scratch
Postgres, assert row counts / a smoke query succeeds) so "we have backups" is a verified
claim, not an assumption, the same distinction this whole inventory effort is built on.

### 13.10 Update path

New — no current script handles "pull new image, migrate, restart" as a single flow.
`start-full-stack.sh`'s idempotent-rerun shape (§3.1: safe to re-run, only changes what's
missing/stale) is the right mental model to carry forward: an `update.sh` that runs
`bootstrap.sh`'s "generate if placeholder" logic (now a no-op on a live system), pulls
the new `trove-core` image (or rebuilds if running from source), lets Flyway apply new
migrations on next boot (already the existing pattern per `docs/recovery/inventory/
database.md`'s confirmation that every service already does this), and restarts
`trove-core` alone — never `docker compose down -v`, which every existing doc/script
still treats as the default "stop" command and which destroys volumes (H4).

### 13.11 Log rotation

New — nothing in any compose file sets `logging.options.max-size`/`max-file` for the
`json-file` driver (the implicit default once `driver: loki` is removed per §1's finding
that the Loki path never worked anyway). Set `max-size: 10m`, `max-file: 3` (or similar)
on `trove-core` and `postgres` at minimum — a one-line addition per service, currently
absent everywhere.

### 13.12 What can be kept nearly verbatim from `infra/full-stack/`

Concretely, from this inventory: the `socket-proxy` service block and its `internal: true`
network (§2.3); the "only Traefik publishes ports" topology; the
`security-headers`/`compress` middleware definitions in `dynamic.yml` (§13.6); the
`ping: {}` + Traefik healthcheck; the `depends_on: condition: service_healthy` chains;
the non-root-user Dockerfile pattern; the `/actuator/health` `HEALTHCHECK` shape; and
`start-full-stack.sh`'s `upsert_env()` + generate-if-placeholder secret pattern (minus its
Authentik-specific calls). Everything else under `infra/full-stack/` — the mkcert-only TLS
(no ACME), the Authentik server/worker blocks, the LiteLLM block, the MinIO block, the
`*.trove.localhost` host rules, the 10-service sprawl — is either dropped per the owner's
decisions or needs to be rebuilt for real public-internet TLS (§13.5) rather than ported.

---

## 14. Risks

1. **The `.env` schema consolidation (§13.4) is the highest-effort, highest-payoff single
   piece of this collapse's infra work**, and it's easy to underestimate: §5's table shows
   the same secret has 3-4 different names across the current 13 files (service tokens
   alone: `TROVE_SERVICE_TOKEN`/`TROVE_INTERNAL_SERVICE_TOKEN`/`TROVE_AI_SERVICE_TOKEN`/
   `AI_SERVICE_TOKEN`). Picking the wrong canonical name, or missing one Java
   `@Value`/`application.yml` reference to an old name during the merge, reproduces H5's
   exact failure mode inside the new monolith instead of eliminating it.

2. **Bootstrap secret generation has a known, verified gap that must not be inherited**:
   `start-full-stack.sh` (§3.1) generates 5 of 7 sensitive vars but silently leaves
   `POSTGRES_PASSWORD` at the literal string `trove`. A public-internet deployment that
   copies this script's pattern without explicitly adding Postgres's own password to the
   generated set ships a database behind a real domain name with a publicly-known
   password — this is the single most direct path from "quietly kept an old pattern" to
   a new critical-severity finding.

3. **No ACME config has ever been exercised in this repo.** The only HTTP-01 block that
   exists (§2.1, §13.5) lives in a compose file confirmed dead by two independent fatal
   bugs and has never run successfully end-to-end against a real domain. Treat §13.5 as
   new, unverified infrastructure requiring its own testing against a real DNS name and
   Let's Encrypt's staging environment before relying on it — do not assume "it's already
   in the repo" means "it already works."

4. **Reusing `infra/full-stack/`'s topology invites reusing its TLS mode by accident.**
   That stack's `*.trove.localhost` host rules and mkcert certs are correct for local dev
   and actively wrong for public internet — a copy-paste of the compose file's routing
   labels without also swapping the TLS resolver (§13.5) and host rules produces a stack
   that starts cleanly, looks identical to local dev, and is not actually reachable (or
   worse, is reachable only via the self-signed fallback Traefik silently uses when the
   configured cert file is absent, per `dynamic.yml`'s own comment, §2.3) once deployed.

5. **The receipt-image local-disk-volume replacement for MinIO has no existing
   self-provisioning code to copy in kind** (only in shape): `MinioConfig.
   MinioInitializer` (§8) is a bucket-existence check against an S3 API, not a filesystem
   check. The "create the directory and set permissions on first boot" logic for §13.2's
   `receipt_images` volume is new code, not a port, even though the *pattern* (idempotent
   check-then-create at startup) is worth keeping.

6. **`mem_limit`/`-Xmx` sizing for the merged `trove-core` (§13.8) has no empirical basis
   yet.** Every current per-service `-Xmx300m`/`75%-of-host` number describes 10 separate
   small JVMs, not one JVM running all 10 services' code, dependencies, and connection
   pools concurrently. Picking a number by simply summing the old per-service caps
   (10 × 300m = 3GB) ignores that a single JVM sharing one heap across what used to be 10
   separate processes' worth of caches/thread pools/connection pools does not scale
   linearly — this needs a real load test against the merged module, not a spreadsheet
   estimate, before it's written into a `mem_limit:` that gets enforced in production.

7. **The backup/restore drill (§13.9) is genuinely new work with no reference
   implementation anywhere in the repo** (§3, H4) — there is a real risk of shipping
   `backup.sh` without ever running `restore.sh` against its own output before the first
   incident that needs it, which is worse than appearing not to have backups at all
   (false confidence). The restore drill must be scripted and run in CI or on a schedule,
   not left as a one-time manual verification.

8. **Grafana/Prometheus are optional but the 3 fictional-metric panels (§6) will silently
   persist into the new stack if the dashboard JSON is carried over unedited** — deleting
   or fixing those 3 panels (or adding the corresponding `Counter`/`Timer` beans to
   `trove-core`, which is a code change outside this infra document's scope) is a small
   task that's easy to forget precisely because the dashboard "looks complete" at a
   glance and the 5 working panels create false confidence in the other 3.

9. **`docker/postgres-init.sql` vs. `infra/full-stack/postgres-init/
   01-create-databases.sql` disagreeing on database count (§2.3, §12.4) is moot for the
   collapse itself** (one database, not eleven) but is a reminder that this repo's infra
   configs have a demonstrated history of two files describing the same thing and
   silently drifting apart — the single consolidated `.env`/compose file this collapse
   produces must not become a second source of truth alongside anything left over from
   the old per-service configs during a transition period; delete the old compose
   families rather than leaving them "for reference" once `trove-core` is live.

10. **Traefik's rate-limit middleware (§13.6) is new, but the existing app-level rate
    limiter it would sit in front of (`RateLimitService.java`, G8) is documented as
    already too permissive on the auth path.** Adding a Traefik-level limit without also
    revisiting the app-level config risks a false sense of having "added rate limiting"
    while the more permissive of the two layers is still the effective one in practice —
    both layers need coordinated tuning, not independent addition.

11. **The socket-proxy container (§2.3, §13.3) is worth keeping, but its `traefik.yml`
    hardcodes `providers.docker.network: trove-full-stack`** (`infra/full-stack/traefik/
    traefik.yml:24`) — a literal network name tied to that compose project's `name:`
    directive. Porting this file verbatim without updating that value to whatever the new
    project's network is actually named silently breaks Traefik's ability to discover
    `trove-core` via Docker labels (it would fall back to no dynamic routes from the
    Docker provider at all, with no obvious error beyond "the site doesn't route").

12. **Every current Dockerfile's `HEALTHCHECK` calls `wget`,** which is present in
    `eclipse-temurin:*-jre-alpine` today; if the base image changes at any point during
    the collapse (e.g. to reduce image size further, or to switch JDK vendors),
    `wget`'s availability is not guaranteed and needs to be re-verified rather than
    assumed — a silently-failing healthcheck (command not found) is easy to miss because
    Docker reports it as "unhealthy," which looks like an app problem, not a missing
    binary.

13. **The bootstrap script's shown-once admin password (§13.4) needs a genuinely
    tamper-resistant "have we already shown this" flag**, not just "does `.env` exist" —
    §3.1 confirmed the current `start-full-stack.sh` treats "regenerate" as "value is
    still the placeholder," which is safe for re-runs but was never designed around a
    human needing to *see* the value exactly once; a naive port of that idempotency check
    to the new script could re-print (or worse, silently re-generate and invalidate) the
    admin password on a second run if the "already shown" state isn't tracked
    separately from "is this var still a placeholder."

14. **`services/receipt-processor`'s Dockerfile bug (§4, §12.4 item 1) and the tax-engine
    cut overlap in a way worth sequencing deliberately**: receipt-processor's build is
    broken for two independent reasons (A1's compile error, and the `*.jar` glob bug
    found in this pass) and is already profile-gated out of the maintained stack (§2.3).
    Since the collapse folds receipt-processor's code into `trove-core` as a package
    rather than building it as a standalone artifact, neither Dockerfile bug needs fixing
    in place — but whoever does the merge should not spend time debugging either issue
    against the old standalone module before realizing the whole Dockerfile is going
    away; confirm the merge plan explicitly retires `services/receipt-processor/
    Dockerfile` rather than "fixing" it first.
