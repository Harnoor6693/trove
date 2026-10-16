# Running Trove

Everything needed to run Trove on one machine lives in this directory. There is
no root `docker-compose.yml`: this file, `deploy/docker-compose.yml`, is the
stack, and `deploy/trove` is the only command you need.

```
deploy/
├── trove                       the CLI: bootstrap, up, down, status, logs, backup,
│                               restore, update, reset, doctor, support-bundle
├── docker-compose.yml          the whole stack
├── .env.example                every setting, with the generation command
├── traefik/traefik.yml         static Traefik config (template — see below)
├── traefik/dynamic/            security headers, CSP, rate limits, TLS policy
└── prometheus/prometheus.yml   only used by the optional monitoring profile
```

Trove's images are built from this repository on your machine (`docker-compose.yml`
uses the local tag `trove-core:local`): `./trove up` and `./trove update` run
`docker compose build`.

---

## 1. Requirements and sizing

| | Minimum | Recommended |
|---|---|---|
| CPU | 2 cores (x86-64 or arm64) | 4 cores |
| RAM | 2 GB | **4 GB** |
| Disk | 20 GB free | 60 GB+ (receipts grow) |
| Software | Docker Engine 24+ with the Compose v2 plugin, `openssl`, `git` | same |
| JDK | not required — see below | JDK 21 makes the first build faster |
| Network | a hostname pointing at the box, and inbound TCP 80+443 | same |

`core/Dockerfile` copies a jar rather than building one, so `./trove up`
packages `trove-core` before it builds the image: with the host's JDK if there
is one, otherwise inside a throwaway `maven:3.9-eclipse-temurin-21` container
(the `~/.m2` cache is kept in a `trove_maven_cache` volume, so only the first
build is slow). A NAS with nothing but Docker on it can install Trove.

Memory is the constraint, and 4 GB is the recommended host. Out of the box
trove-core runs with `JAVA_OPTS=-Xmx1280m -XX:+UseZGC` inside a 2 GB container
limit (`TROVE_CORE_MEM_LIMIT=2g`), and Postgres is tuned for a 512 MB limit.
Measured under a 50-user load test (step 23): with `-Xmx1536m` the container
peaked at 93.8 % of its 2 GB limit — too little headroom for a burst of receipt
uploads or a data export — while `-Xmx1280m` peaked at 81 %. Neither run had an
out-of-memory event. A 2 GB host runs the default with nothing else on it, and
is tight.

Option A, for a 4 GB host that wants the extra heap: set
`TROVE_CORE_MEM_LIMIT=2560m` and `JAVA_OPTS=-Xmx1536m -XX:+UseZGC` together in
`.env` (1536m was the only heap measured with zero GC allocation stalls). Raise
`POSTGRES_MEM_LIMIT` and `POSTGRES_SHARED_BUFFERS` alongside if the box has room.

The optional `ai` profile (a local Ollama) needs several GB more, and a model
worth using needs more still. Most installs point the per-user AI endpoint at a
machine that is not this one, or leave AI off.

---

## 2. Install (about 10 minutes, most of it DNS)

1. Point an `A`/`AAAA` record at this machine and forward TCP 80 and 443 to it.
   Wait for the record to resolve before step 4 — Let's Encrypt checks it.
2. Clone the repository and enter it.
3. Generate the configuration:

   ```bash
   cd deploy
   ./trove bootstrap
   ```

   It asks for the hostname, an email for certificate notices, the first admin
   address and the first admin's country (CA or US; this sets the currency to CAD
   or USD and cannot be changed later), then generates every secret with
   `openssl rand` and writes `.env` with mode 0600. Without a terminal it reads
   `TROVE_DOMAIN`, `ACME_EMAIL`, `TROVE_ADMIN_EMAIL` and `TROVE_ADMIN_COUNTRY`
   from the environment instead. **Copy the printed admin password and backup
   passphrase somewhere safe — they are printed once. Copy `TROVE_ENCRYPTION_KEY`
   into a password manager too.**

4. Start:

   ```bash
   ./trove up
   ```

   `up` runs its preflight checks first (Docker present and running, ports free,
   secrets generated, domain set, ≥ 5 GB disk) and prints the fix under anything
   that fails; nothing is started until they all pass. Then it packages the jar,
   builds the images, starts the stack, and waits for `trove-core` to report
   *readiness* — which includes Flyway having finished, so a green `up` means the
   database is migrated. Expect 5-15 minutes the first time, most of it Maven.

5. Open `https://your-domain/`, sign in as the admin, and change the password in
   Settings. `TROVE_ADMIN_PASSWORD` stays in `.env` (compose requires it) but is
   ignored once any user exists, so it is harmless; do not rely on it to sign in
   afterwards.

6. Invite the rest of the household from **Settings → Household**. Signup is
   closed by default (`TROVE_REGISTRATION_OPEN=false`); invites are single-use
   links valid for 72 hours, and they work without email configured — copy the
   link and send it however you like.

### Optional: single sign-on (OIDC)

Set `TROVE_OIDC_ENABLED=true`, `TROVE_OIDC_ISSUER_URI`, `TROVE_OIDC_CLIENT_ID`,
`TROVE_OIDC_CLIENT_SECRET` and optionally `TROVE_OIDC_DISPLAY_NAME` in `.env`.
Register `<TROVE_PUBLIC_URL>/api/login/oauth2/code/oidc` as the redirect URI at
your provider. Scopes are `openid,profile,email`. The issuer is contacted at boot,
so a wrong or unreachable issuer makes the container fail to start.

An existing link between the provider identity and a Trove account wins.
Otherwise a local account with the same email is linked automatically only when
the provider sends `email_verified=true`; if not, the user must prove ownership
with their local password. With no local account, sign-in follows the
registration policy (closed by default), so OIDC never opens the install to
strangers. Password accounts keep working. The sign-in buttons come from
`GET /v1/auth/providers`.

---

## 3. Domain and TLS

`TROVE_TLS_MODE` picks one of three paths.

### `acme-http` (default)

HTTP-01: Traefik answers Let's Encrypt's challenge on port 80. Requires inbound
TCP 80 from the public internet. Certificates land in the `traefik_acme` volume
and are included in every backup, so a restore does not re-issue them (Let's
Encrypt rate-limits issuance; that matters).

Test against the staging CA first if you are unsure of your DNS or forwarding:

```bash
# in .env
TROVE_ACME_CA_SERVER=https://acme-staging-v02.api.letsencrypt.org/directory
```

Your browser will warn about the staging certificate — that is the point. Switch
back to the production URL and run `./trove up` again when the flow works.

### `acme-dns` (Cloudflare)

Use this when port 80 cannot reach you: CGNAT, an ISP that blocks it, or you
want a wildcard. Cloudflare is the only provider wired up.

1. Create an API token at Cloudflare → My Profile → API Tokens with
   **Zone → DNS → Edit** on the zone of `TROVE_DOMAIN` (nothing more).
2. In `.env`:

   ```bash
   TROVE_TLS_MODE=acme-dns
   CF_DNS_API_TOKEN=<the token>
   ```

3. `./trove up`. Issuance takes a minute or two while the TXT record propagates.

If you use Cloudflare's proxy (orange cloud), set the SSL mode to **Full
(strict)** — Flexible would put plaintext HTTP on the last hop.

### `off` — no domain yet, via Tailscale

You do not need a public domain to use Trove. Put it on your tailnet and let
Tailscale terminate TLS with a `*.ts.net` certificate:

```bash
# .env
TROVE_TLS_MODE=off
TROVE_DOMAIN=<machine>.<tailnet>.ts.net
TROVE_HTTP_PORT=8080          # Traefik binds this instead of 80
TROVE_HTTPS_PORT=8443         # unused in this mode, but must not collide
```

```bash
./trove up
sudo tailscale serve --bg --https=443 http://127.0.0.1:8080
```

Then browse to `https://<machine>.<tailnet>.ts.net/`. In this mode Traefik serves
plain HTTP on `TROVE_HTTP_PORT`, which Docker publishes on every host interface, and
does no ACME at all; Tailscale owns the
certificate. The HTTP→HTTPS redirect is removed from the rendered Traefik config
automatically. Everything else — headers, CSP, rate limits, routing — is
unchanged. **Firewall `TROVE_HTTP_PORT` from anything but this machine (or allow
only the tailnet);** otherwise the install is reachable over unencrypted HTTP. The mobile app connects over the tailnet only.

**HSTS is one year without `preload`, deliberately.** Preload is a one-way door
that would strand this install if it ever moves domain or drops TLS.

### The rendered Traefik config

Traefik cannot expand environment variables in its *static* configuration (file,
CLI and env are mutually exclusive sources). `./trove up` therefore renders
`traefik/traefik.yml` into `traefik/traefik.runtime.yml`, substituting
`ACME_EMAIL` and `TROVE_ACME_CA_SERVER` and dropping the redirect block when TLS
is off. **Edit `traefik.yml`, never `traefik.runtime.yml`** — the latter is
overwritten on every `up`, `update` and `restore`. If you drive `docker compose`
by hand, run `./trove up` once first: it renders `traefik.runtime.yml`
(`./trove doctor` only reports whether it exists).

---

## 4. Day-to-day

```bash
./trove status            # what is running, version, disk, DB size, backup age, cert expiry
./trove logs trove-core   # follow one service (omit the name for all of them)
./trove down              # stop everything; data volumes are never touched
./trove up                # start again
./trove doctor            # diagnose without changing anything
```

`./trove down` cannot delete data — it never passes `-v`. The only command that
removes volumes is `./trove reset`, which requires typing `DELETE` and then the
hostname.

### Upgrading

```bash
./trove update
```

In order: take a backup, `git pull --ff-only`, repackage the jar, rebuild the
images, restart, and wait. `trove-core` runs its Flyway migrations during startup and only reports
readiness once they have applied, so `--wait` is what proves the migration
worked — if it fails, the container never becomes ready and `update` stops,
prints the last 80 log lines, and tells you the exact rollback and restore
commands. The version is printed before and after.

Update on a schedule you choose, not automatically. Read the release notes
first; a major version means a manual step.

---

## 5. Backup and restore

```bash
./trove backup
```

One encrypted archive, `backups/trove-<timestamp>.tar.gz.enc`, containing:

| Inside the archive | Why it is there |
|---|---|
| `database.dump` | `pg_dump -Fc` of the whole catalog |
| `trove-data.tar.gz` | receipts, exports, uploads from the `trove_data` volume |
| `acme.tar.gz` | TLS certificates, so a restore does not re-issue |
| `env.secrets` | your `.env` — **including `TROVE_ENCRYPTION_KEY`** |
| `manifest.txt` | timestamp, host, source version, contents |

The dump alone is not a backup. Bank credentials and TOTP secrets in it are
AES-GCM ciphertext; without `TROVE_ENCRYPTION_KEY` they are unreadable and every
connected account has to be re-linked. That is why the key is in the archive —
and why the archive is encrypted with AES-256 (PBKDF2, 600 000 iterations) using
`TROVE_BACKUP_PASSPHRASE`, or to an `age` recipient if you set
`TROVE_BACKUP_AGE_RECIPIENT` and have `age` installed.
With an age recipient the archive is named `.tar.gz.age`; to restore it run
`TROVE_BACKUP_AGE_IDENTITY=/path/to/age-key.txt ./trove restore <archive>`.

**Keep `TROVE_BACKUP_PASSPHRASE` and `TROVE_ENCRYPTION_KEY` somewhere that is not
this machine.** An archive whose passphrase died with the disk is not a backup.

Schedule it and copy it off the box:

```cron
0 3 * * *  /path/to/repo/deploy/trove backup >> /var/log/trove-backup.log 2>&1
30 3 * * * rclone copy /path/to/repo/deploy/backups remote:trove-backups
```

`TROVE_BACKUP_KEEP` (default 7) archives are kept; older ones are pruned after a
successful run. `backups/LAST_SUCCESS` carries the timestamp `status` and
`doctor` report.

### Restoring

```bash
./trove restore backups/trove-20260903T030000Z.tar.gz.enc
```

Confirms by hostname, stops `trove-core` and `web`, drops and recreates the
database, `pg_restore`s it, replaces the data volume, restores the certificates,
starts back up and waits for readiness before printing a row count. Add
`--include-secrets` to also replace `.env` — required when restoring onto a new
machine, because the new `.env` would otherwise have a different encryption key.

### Restore drill

Do this once now and once a quarter. It takes ten minutes and it is the only
thing that turns "we have backups" into a fact.

1. `./trove backup`
2. Note the numbers from `./trove status` (database size) and the transaction
   count on the dashboard.
3. On a *scratch* machine or a copy of the repo with a different
   `TROVE_PROJECT_NAME`, run `./trove restore <archive> --include-secrets`.
4. Sign in. Check the transaction count matches, open one receipt image, and
   confirm a connected account still syncs (that last one proves the encryption
   key survived).
5. Write down how long it took. That is your real RTO.

**RPO — 24 hours** with the nightly cron above: at worst you lose the
transactions imported since last night's run. Shorten it by running `backup`
more often; it is safe to run while the stack is up.

**RTO — about 10 minutes** on a 2 GB box for a database of a few hundred
thousand transactions, plus however long it takes you to get the archive back
onto the machine. Most of the wall clock is `pg_restore` and the JVM start.

### Background jobs and retention

The server runs these on its own. Clock-time triggers are UTC, and each job holds a
lock so two instances never run one twice.

| Job | When (UTC) | What |
|---|---|---|
| Envelope rollover | daily 00:40 | rolls budget envelopes over |
| Subscription scan | daily 02:00 | detects subscriptions |
| Refresh-token purge | daily 03:10 | removes expired tokens |
| Invite expiry sweep | daily 03:20 | removes expired invites |
| Password-reset purge | daily 03:30 | removes expired reset links |
| Login-attempt purge | daily 03:40 | deletes rows older than 30 days |
| Audit-log purge | daily 03:50 | deletes rows older than 365 days |
| Import retention sweep | daily 04:05 | deletes abandoned import previews (default 7 days, `TROVE_IMPORT_PREVIEW_RETENTION`) |
| Account purge | daily 04:05 | hard-deletes a removed member after the 7-day grace |
| SimpleFIN sync | daily 09:17 | only when an admin enabled it (`TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON`) |
| Net-worth snapshot | daily 23:30 | records the day's net worth |
| Transfer pairing | hourly at :10 | pairs matching transfers |
| Data-export sweep | hourly at :12 | deletes export archives after their 24-hour link lapses |
| Receipt orphan sweep | hourly at :20 | re-queues receipts stuck for over an hour |
| Weekly digest | Mondays 08:00 | sends the digest |
| Weekly insight | Mondays 08:30 | AI, only for users who configured an endpoint |
| Webhook retry | every 30 s | up to 5 attempts, backoff of 2^n seconds |
| Rate-limit rule reload | every 5 minutes | picks up rule changes |

Retention periods are fixed in code, except the import preview retention and the
SimpleFIN cron, which the shipped compose file does not forward to `trove-core`.

---

## 6. Troubleshooting by symptom

**Browser shows a certificate warning / `ERR_SSL_PROTOCOL_ERROR`.**
The certificate has not been issued. `./trove logs traefik` and look for `acme`.
Usual causes: DNS not resolving to this box yet, port 80 not forwarded (switch
to `acme-dns`), or you are still on the staging CA.

**502 from the web page.** `trove-core` is not healthy. `./trove status`, then
`./trove logs trove-core`. If it is restarting, the usual cause is the database:
check `./trove logs postgres`.

**`trove up` says a port is in use.** Something else owns 80/443
(`lsof -nP -iTCP:443 -sTCP:LISTEN`). Either stop it, or set `TROVE_HTTP_PORT`/
`TROVE_HTTPS_PORT` and put your own proxy in front.

**Login loops or every save returns 403.** A CSRF cookie problem, almost always
from browsing on a hostname that is not `TROVE_DOMAIN` (an IP, or `localhost`).
Use the configured hostname.

**"too many requests" on the login page.** Traefik limits `/api/v1/auth/*` to
5 requests/second per IP. The app also throttles sign-in, and answers only
"Email or password is incorrect." while it does: 5 failures for one account
from one address throttle that address for 30 seconds, doubling up to 15
minutes, and 20 failures from one address across all accounts in 10 minutes
answer "too many requests". After 100 failures in an hour for one account, from
anywhere, the account accepts sign-ins only from addresses it has signed in
from before. Wait, or have an admin unlock the member (Settings, Admin), or,
for the admin, run
`docker compose run --rm trove-core --trove.cli.command="unlock <email>"` (see the
next entry). For the Traefik limit, raise `ratelimit-auth` in
`traefik/dynamic/middlewares.yml` (hot-reloaded, no restart).

**Locked out of the only admin account.** A forgotten password or a lost
authenticator on the only admin cannot be fixed from the app. Run one of these
on the server:

```bash
docker compose run --rm trove-core --trove.cli.command="reset-password <email>"
docker compose run --rm trove-core --trove.cli.command="disable-totp <email>"
docker compose run --rm trove-core --trove.cli.command="unlock <email>"
```

`reset-password` prints a one-hour, single-use reset link to open in the browser;
`disable-totp` removes the second factor; `unlock` clears a sign-in lock without
touching the password. The command prints its result and exits instead of starting
the server (exit code 0 means it worked). Shell access to the server is the only
authorisation needed.

**Disk full.** `./trove status` shows database and data-directory sizes. Receipts
are the usual growth; each household has a `TROVE_RECEIPT_QUOTA_MB` cap (one
shared disk area per household, not per user). Container
logs cannot be the cause: every service is capped at 5 × 20 MB by the
`json-file` driver.

**Container keeps restarting after an update.** A migration failed. `./trove logs
trove-core` shows the failing SQL. Roll back to the previous checkout and
restore the backup `update` took before it started.

**Nothing works and you want to send a report.** `./trove support-bundle` writes
a tarball of logs and configuration with every value from `.env` scrubbed out.
Read it before sending — it still contains hostnames, email addresses and client
IPs from the access log.

---

## 7. What is and is not secured

Done, and verifiable in this directory:

- **One published port pair.** Only Traefik binds 80/443. `trove-core`,
  `postgres`, `ollama` publish nothing; Postgres sits on an `internal: true`
  network with no route off the box, and Ollama on another one so it cannot
  reach the database.
- **The management port is never routed.** Actuator (`/actuator/health`,
  `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`) lives on port 9090, reachable only from inside the stack. The
  public health endpoint is the detail-free `/api/v1/status`.
- **Traefik does not touch the Docker socket.** It reads a filtered, read-only
  API through `socket-proxy`.
- **Security headers on every response** — HSTS (1 year, `includeSubDomains`, no
  preload), `X-Content-Type-Options`, `Referrer-Policy`, a minimal
  `Permissions-Policy`, `frame-ancestors 'none'`, and a CSP: `script-src 'self';
  connect-src 'self'` for the app, `default-src 'none'` for `/api`.
- **TLS 1.2 minimum**, forward-secret AEAD ciphers only, `sniStrict`.
- **Two rate-limit layers**: Traefik per IP (100/s general, 5/s on
  `/api/v1/auth/*`) and the application's own per-user and per-account limits.
- **No secret has a default.** Compose refuses to start naming any variable you
  missed; `bootstrap` generates all of them with `openssl rand`; `.env` is 0600.
- **Grafana has no default password:** `GRAFANA_ADMIN_PASSWORD` must be non-empty in
  `.env` (compose refuses to start any profile without it, even when monitoring is
  off; bootstrap generates one). Prometheus and Grafana bind to loopback only — reach them over an
  SSH tunnel, never through Traefik.
- **Backups are encrypted** and include the encryption key, so a restore is a
  real restore.
- **Log rotation** on every service (20 MB × 5).
- `no-new-privileges` on every container.

Not done, and you should know it:

- **No external penetration test has been performed** (owner decision). The
  security work here is an internal adversarial review against a staging stack,
  documented in the repository. Treat this as a self-hosted application that has
  been reviewed carefully by its authors and by nobody else.
- **No WAF, no fail2ban, no IPS.** Rate limiting is all there is in front of the
  login form.
- **Containers are not `read_only` and capabilities are not dropped** beyond
  `no-new-privileges`.
- **One database role.** Flyway and the application share it; there is no
  separate migration/runtime split yet.
- **Key rotation is partial.** Rotating `TROVE_JWT_SECRET` logs everyone out and
  is otherwise safe. Rotating `TROVE_ENCRYPTION_KEY` is **not** yet a supported
  operation — stored bank credentials are encrypted with the current key and
  there is no re-encryption command. Until there is: back the key up, and if you
  lose it, re-link every account after restoring.
- **Alerting is mostly manual.** Failing bank syncs raise an in-app notification (and
  optional email/webhook). Disk space, backup age and certificate expiry are not
  alerted on: check them with `./trove status` and `./trove doctor`, or poll
  `GET /api/v1/status` (DEGRADED when disk space is low). There is no Alertmanager.

Report a vulnerability through the repository's private vulnerability reporting,
not a public issue.

---

## 8. What leaves your server

Nothing leaves this machine unless you switch it on. Exhaustively, the outbound
connections this stack can make:

| Destination | When | What is sent |
|---|---|---|
| Let's Encrypt (`acme-v02.api.letsencrypt.org`) | `TROVE_TLS_MODE=acme-http` or `acme-dns` | your domain name and `ACME_EMAIL` |
| Cloudflare API (`api.cloudflare.com`) | `TROVE_TLS_MODE=acme-dns` only | the DNS challenge record, using your token |
| Your bank aggregator (SimpleFIN) | only after a user connects an account | the setup token, then account and transaction fetches |
| An AI endpoint | only after a *user* configures one, per user | the prompt content for that feature: transaction descriptions for categorisation, the coach conversation, receipt images for extraction. Each user chooses their own endpoint and key; point it at the `ai` profile's Ollama and it never leaves the box (this needs `TROVE_EGRESS_ALLOW_PRIVATE=true`) |
| Your webhook URLs | only if a user adds one | the notification payload |
| Your SMTP server | only if `SMTP_ENABLED=true` | notification emails (for example the weekly digest); password resets never use email |
| Docker Hub / GHCR | `up` and `update` | base image pulls, nothing about you |

There is no telemetry, no analytics, no crash reporting, and no phone-home
version check. Trove has no server of its own to talk to.

Every outbound target above except the CA and the image registries goes through
one policy: HTTPS required (plain http only for a private target that both
`TROVE_EGRESS_ALLOW_PRIVATE=true` and the individual user have opted in), DNS pinned
at connect time, and private/loopback/link-local addresses refused otherwise.
Refused targets show an `outbound-url-rejected` error next to the setting; there is
no outbound-call log screen.

---

## 9. Configuration reference

`.env.example` is the reference: every variable, grouped, with a comment and the
`openssl` command for anything secret. Copy it or let `./trove bootstrap` do it.

Two things worth repeating:

- `TROVE_ENCRYPTION_KEY` is the one value whose loss costs you data.
- `POSTGRES_PASSWORD` is generated, never `trove`. If you are migrating from an
  older Trove checkout, do not carry the old value across.
