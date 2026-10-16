# Trove core API — Postman collection

`trove-core.postman_collection.json` is generated from [`openapi/trove-core.yaml`](../../openapi/trove-core.yaml)
by [`scripts/postman-collection.sh`](../../scripts/postman-collection.sh). **It is never hand-edited.**
After any change to the spec, regenerate it and commit the diff:

```bash
scripts/postman-collection.sh
git status --porcelain docs/postman   # should show the collection's diff, nothing else
```

The script runs `npx -y openapi-to-postmanv2@6.3.3` (pinned; never added to a `package.json`)
and post-processes the output with `jq` so that regenerating twice from the same spec
produces a byte-identical file — no random ids, no faked example values, sorted keys.

## Import

1. In Postman: **Import** → `docs/postman/trove-core.postman_collection.json`.
2. **Import** → `docs/postman/trove-local.postman_environment.json`, then select
   **Trove Core (local)** as the active environment (top-right environment picker).
3. Start the server locally — see
   [docs-site/guide/local-development.md](../../docs-site/guide/local-development.md) —
   so it is listening on `http://localhost:8080`.

## Sign in

The collection carries a **bearer-mode** flow: every request sends
`X-Trove-Auth-Mode: bearer` and `X-Requested-With: XMLHttpRequest` (added by a
collection-level pre-request script, so every folder gets them even if a request's own
headers omit one), and the collection's own auth is `Bearer {{accessToken}}`.
Cookie mode is not used here — it demands the CSRF double-submit token
(`X-XSRF-TOKEN`) dance, which a JSON-only Postman flow gains nothing from over bearer.

1. Set the environment's `email` and `password` (left blank on purpose — no real
   credential ships in this file). The account must have the `ADMIN` role: the
   Smoke folder reads `GET /v1/admin/users`, which a `MEMBER` receives as 403, so
   the run fails.
2. Open **Smoke → Sign in with an email address and password** and **Send**. Its test
   script reads the response and, on success, stores `accessToken` and `refreshToken`
   into the environment automatically — every later request in the collection picks
   `accessToken` up through the collection auth.

### If the account has TOTP (a second factor) enabled

The login response is still a 200; the envelope's `data` carries `challengeToken`
instead of a session. The collection's test script detects this and fails the request
with a clear message rather than silently leaving stale tokens in the environment.
To finish signing in, call **Authentication → Complete a two-factor sign-in**
(`POST /v1/auth/login/totp`) by hand with:

```json
{ "challengeToken": "<from the failed login's response body>", "code": "<the live TOTP code>" }
```

and copy `data.accessToken` / `data.refreshToken` into the environment yourself — this
step is not scripted because a TOTP code cannot be produced by a saved request.

## Call an endpoint

Every operation from the spec is here, one folder per tag (Categories, Accounts,
Transactions, Imports, Dashboard, AI, …). Pick a request, fill in any path/query
variables the description calls out, and **Send** — the bearer token and both auth-mode
headers are already wired up.

## Run from the terminal

```bash
npx -y newman@6.2.2 run docs/postman/trove-core.postman_collection.json \
  -e docs/postman/trove-local.postman_environment.json \
  --env-var email=<test account email> --env-var password=<test account password> \
  --folder Smoke
```

`Smoke` is a folder of copies (login, the two unauthenticated/self reads, and one
representative list read per resource domain) meant to run unattended against a fresh
local server. Nothing in CI runs it; run it by hand. The account must have the
`ADMIN` role, because the folder reads `GET /v1/admin/users` and a `MEMBER` gets 403.
It excludes tags with no safe idempotent read (Account security: every route
mutates; Imports: the upload mutates and every other route needs an existing batch id).
