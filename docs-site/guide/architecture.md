# Architecture

Trove is one Spring Boot module, `core`, backed by one PostgreSQL database with one
Flyway migration history. There is no gateway, no separate identity service, no cache
tier, and no object store — see [ADR-0020](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0020-monolith-collapse.md) for
why an earlier ten-service design was collapsed into this one.

## Shape

```
                    ┌─────────────────────────────────┐
                    │  Clients: web (React), mobile    │
                    │  (Expo, iOS + Android)           │
                    └────────────────┬──────────────────┘
                                     │ HTTPS
                                     ▼
                    ┌─────────────────────────────────┐
                    │      Traefik (deploy/ only)      │
                    │  TLS termination, routing        │
                    └────────────────┬──────────────────┘
                                     │
                                     ▼
        ┌───────────────────────────────────────────────────────┐
        │                      core (one JVM)                    │
        │  auth · users · households                             │
        │  transactions · accounts · budgets · networth          │
        │  ingestion · categorisation · receipts · subscriptions │
        │  taxexport · dataexport · notifications · ai · app     │
        └────────────────────────┬────────────────────────────────┘
                                 │
                                 ▼
                    ┌─────────────────────────────────┐
                    │   PostgreSQL — one schema,       │
                    │   one Flyway history             │
                    └─────────────────────────────────┘
```

`core` is 17 packages under `finance.trove`, each owning its own domain. They are Java
packages, not deployable units — there is one build, one JAR, one running process.

## Request flow: importing a transaction

1. The client authenticates (see below) and calls a route under `/api/v1/...`, matched
   against [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml) —
   the same spec `OpenApiRouteDriftIT` and `OpenApiConformance` check every integration
   test run, so the served routes cannot silently diverge from the documented contract.
2. `finance.trove.ingestion` parses the source (CSV, OFX/QFX, SimpleFIN, manual entry,
   receipt OCR) and writes rows scoped to the caller's household.
3. `finance.trove.categorisation` assigns a category: exact/fuzzy merchant match first
   (`MerchantCategoryMap`, `CommunityMerchantRegistry`), then a rules tier, then —
   only if the household has opted a category tier into AI — `finance.trove.ai` calls
   the endpoint the user configured.
4. `finance.trove.budgets` updates the relevant envelope/period totals.
5. `finance.trove.notifications` dispatches an in-app notification (`InAppChannel`,
   always on) and, if the household enabled it, an email (`EmailChannel`, off by
   default — see [environment variables](./environment-variables)).

## Authentication

Local accounts (email + Argon2id-hashed password) are the default —
`finance.trove.auth.local`. OIDC to an external provider is optional and all-or-nothing:
if `TROVE_OIDC_ENABLED` is set, issuer, client id, and client secret must all be
present or the app refuses to start; local accounts keep working either way
(`finance.trove.auth.oidc`).

Sessions are HS256 JWTs signed with `TROVE_JWT_SECRET` — one process, one secret, no
downstream service to distribute a key to. Short-lived access tokens; single-use opaque
refresh tokens rotated on use. See [ADR-0011](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0011-threat-model.md) for
the full authentication threat table.

## AI

AI is per-user and off by default. `finance.trove.ai.AiGateway` calls whatever
OpenAI-compatible endpoint a user points their own account at from Settings — a local
Ollama, a LAN model server, or a cloud provider. A fresh install calls no AI endpoint at
all: there is no bundled model runtime and no default endpoint. Outbound calls go
through `finance.trove.common.net.OutboundUrlPolicy`, which blocks private/link-local/
metadata addresses unless the operator opts the whole install into
`TROVE_EGRESS_ALLOW_PRIVATE` (for a LAN model server).

## Storage

Receipts, exports, and uploads are files on local disk under `TROVE_DATA_DIR`, inside
the deployment's own Docker volume (`finance.trove.receipts`, `finance.trove.dataexport`).
There is no S3-compatible object store in this architecture.

## Technology choices

| Concern | Choice |
|---|---|
| Runtime | Spring Boot 4.1, Java 21 |
| Database | PostgreSQL, one schema, Flyway-managed |
| Auth | Local Argon2id by default; optional OIDC; HS256 JWT |
| AI | Per-user, opt-in, OpenAI-compatible endpoint the user supplies |
| Storage | Local disk under a Docker volume, per-household quota |
| Web client | React + Vite |
| Mobile client | Expo (React Native), iOS + Android |
| API contract | OpenAPI 3.0.3, conformance-tested every integration test run |
| Deployment | Docker Compose, one `core` container + Postgres, behind Traefik |
