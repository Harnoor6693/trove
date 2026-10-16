# API Reference Overview

Trove serves one HTTP API from one process (`core`), under `/api`. There is no gateway
in front of it and no per-domain services behind it — see
[Architecture](../guide/architecture). The authoritative contract is
[`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml);
every integration test in `core` validates real request/response pairs against it, so
this page summarises the spec rather than restating it in full — read the spec's own
`info.description` for the complete, current version of everything below.

## The envelope

Every JSON route answers with `ApiResponse{success, data, error, meta}`:

```json
{ "success": true, "data": { "...": "..." } }
```

`data` is omitted (not `null`) when an operation returns nothing. `meta` carries
pagination on list routes and is otherwise absent. Three kinds of route are not JSON
envelopes: `GET /v1/status` answers bare so an uptime probe has something trivial to
check; streaming chat (`POST /v1/chat/completions?stream=true`) answers
`text/event-stream`; and file downloads stream bytes. `GET /v1/auth/start` is a bare
302 redirect into the OIDC flow (a 404 envelope when SSO is off).

Paged lists take zero-based `page` (default 0) and `limit` (default 50; maximum 500 for
transactions and 200 for every other paged list). `meta` is
`{ "total": n, "page": p, "limit": l }`. Out-of-range values are refused with 400
`validation-error`, not clamped.

Money amounts are JSON strings (`"12.34"`). An amount that does not fit NUMERIC(19,4)
(more than 15 integer digits or more than 4 decimals) is refused with 400
`validation-error` and a field error naming the property. A manually entered
transaction amount may have at most 2 decimals.

Errors follow RFC 7807 inside the same envelope:

```json
{
  "success": false,
  "error": {
    "type": "https://trove.finance/errors/validation-error",
    "title": "Bad Request",
    "status": 400,
    "detail": "One or more fields failed validation.",
    "instance": "/api/v1/transactions",
    "requestId": "...",
    "fieldErrors": [{ "field": "amount", "message": "must be greater than or equal to 0.01" }]
  }
}
```

Clients switch on the `type` slug. `title` is the HTTP reason phrase and `detail` is prose; only the slug is stable.
`requestId` matches the `X-Request-Id` response header — quote it when reporting a bug.

## Authentication

Two carriers, chosen per request with `X-Trove-Auth-Mode`:

- **bearer** (default; what the mobile client uses) — tokens come back in the response
  body; send `Authorization: Bearer <accessToken>`.
- **cookie** (`X-Trove-Auth-Mode: cookie`; what the web client uses) — the body carries
  no tokens; the server sets `__Host-trove_access` / `__Host-trove_refresh`
  (HttpOnly, Secure, SameSite=Lax).

Cookie-mode mutations need the CSRF double-submit header, `X-XSRF-TOKEN`, echoing the
non-HttpOnly `XSRF-TOKEN` cookie — including on login and register, since login CSRF is a
real attack. A missing or wrong token is 403 `forbidden`. Once a request is
authenticated by the session cookie it must also send `X-Requested-With: XMLHttpRequest`
on every POST/PUT/PATCH/DELETE; without it the answer is 403 `csrf-header-missing`.
Bearer callers send neither. The `XSRF-TOKEN` cookie is replaced on every cookie-mode
password login, registration and TOTP login, so re-read it afterwards. A refresh keeps it.
See the "Auth modes" and "CSRF" sections of `openapi/trove-core.yaml`'s
`info.description` for the exact rules and status codes.

Sign in against `POST /v1/auth/login`, refresh against `POST /v1/auth/refresh`. Local
email+password accounts are the default; OIDC is optional (see
[Authentication](./authentication)).

## Rate limits

Requests are rate limited per caller (by address for unauthenticated requests and the public sign-in routes).
Over the limit you get 429 with error type `rate-limited` and a `Retry-After` header in
seconds. The sign-in gate answers 429 `account-locked`, also with `Retry-After`. Back off
for that long before retrying. The limits are rows in the `rate_limit_config` table,
which an operator can edit; they reload within five minutes. Defaults per minute (burst
in brackets):

| Path | Limit |
|---|---|
| `/api/v1/auth/login` | 60 (120) |
| `/api/v1/auth/register` | 20 (40) |
| `/api/v1/auth/**` | 60 (120) |
| `/api/v1/chat/**` | 60 (120) |
| `/api/v1/ai/**` | 30 (60) |
| `/api/v1/receipts/upload` | 30 (60) |
| `/api/v1/me/export` | 30 (60) |
| `/api/v1/accounts/*/sync` | 10 (20) |
| `/api/**` | 600 (1200) |

## Domains

| Area | OpenAPI tag(s) | Page |
|---|---|---|
| Auth, sessions, MFA, your own profile, household administration, audit log | Authentication, Account security, Me, Administration | [Authentication](./authentication) |
| Transactions, accounts | Transactions, Accounts | [Transactions](./transactions) |
| Imports (CSV/OFX/QFX statement files) | Imports | [Ingestion](./ingestion) |
| Category rules and AI tiering | Categories | [Categorisation](./categorisation) |
| Budgets, envelopes, savings goals | Budgets | [Budgets](./budgets) |
| Net worth, today/this-month dashboard | Net Worth, Dashboard | [Reports](./reports) |
| Subscription detection | Subscriptions | [Subscriptions](./subscriptions) |
| Receipt upload and field extraction | Receipts | [Receipts](./receipts) |
| Tax-time CSV/PDF export | Tax export | [Tax](./tax) |
| In-app/email notifications, webhooks | Notifications, Webhooks | [Notifications](./notifications) |
| AI Coach, per-user AI settings | AI | [AI Coach](./ai-coach) |

## Exercising the API

- **OpenAPI file:** read `openapi/trove-core.yaml` in the repository, or load it into any OpenAPI viewer. A running `core` does not serve a Swagger UI.
- **Postman:** a generated collection and environment at
  [`docs/postman/`](https://github.com/Harnoor6693/trove/tree/canary/docs/postman) —
  never hand-edited, regenerated from the spec by `scripts/postman-collection.sh`.
