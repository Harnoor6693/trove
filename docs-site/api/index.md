# API Reference Overview

Trove serves one HTTP API from one process (`core`), under `/api`. There is no gateway
in front of it and no per-domain services behind it — see
[Architecture](../guide/architecture). The authoritative contract is
[`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml);
every integration test in `core` validates real request/response pairs against it, so
this page summarises the spec rather than restating it in full — read the spec's own
`info.description` for the complete, current version of everything below.

## The envelope

Every route answers with `ApiResponse{success, data, error, meta}`:

```json
{ "success": true, "data": { "...": "..." } }
```

`data` is omitted (not `null`) when an operation returns nothing. `meta` carries
pagination and is otherwise absent. The one exception is `GET /v1/status`, which answers
bare so an uptime probe has something trivial to check.

Errors follow RFC 7807 inside the same envelope:

```json
{
  "success": false,
  "error": {
    "type": "https://trove.finance/errors/validation-failed",
    "title": "Validation failed",
    "status": 400,
    "detail": "...",
    "instance": "/api/v1/transactions",
    "requestId": "...",
    "fieldErrors": [{ "field": "amount", "message": "must not be negative" }]
  }
}
```

Clients switch on the `type` slug, not the HTTP status or the prose in `title`/`detail`.
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
real attack. See the "Auth modes" and "CSRF" sections of `openapi/trove-core.yaml`'s
`info.description` for the exact rules and status codes.

Sign in against `POST /v1/auth/login`, refresh against `POST /v1/auth/refresh`. Local
email+password accounts are the default; OIDC is optional (see
[Authentication](./authentication)).

## Domains

| Area | OpenAPI tag(s) | Page |
|---|---|---|
| Auth, sessions, MFA | Authentication, Account security | [Authentication](./authentication) |
| Transactions, accounts | Transactions, Accounts | [Transactions](./transactions) |
| Imports (CSV/OFX/SimpleFIN/manual) | Imports | [Ingestion](./ingestion) |
| Category rules and AI tiering | Categories | [Categorisation](./categorisation) |
| Budgets and envelopes | Budgets | [Budgets](./budgets) |
| Net worth, today/this-month dashboard | Net Worth, Dashboard | [Reports](./reports) |
| Subscription detection | Subscriptions | [Subscriptions](./subscriptions) |
| Receipt upload and OCR | Receipts | [Receipts](./receipts) |
| Tax-time CSV/PDF export | Tax export | [Tax](./tax) |
| In-app/email notifications, webhooks | Notifications, Webhooks | [Notifications](./notifications) |
| AI Coach, per-user AI settings | AI | [AI Coach](./ai-coach) |

## Exercising the API

- **Swagger UI:** `/swagger-ui/index.html` on a running `core` instance.
- **Postman:** a generated collection and environment at
  [`docs/postman/`](https://github.com/Harnoor6693/trove/tree/canary/docs/postman) —
  never hand-edited, regenerated from the spec by `scripts/postman-collection.sh`.
