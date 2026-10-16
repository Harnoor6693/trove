# Architecture

Trove is one Spring Boot module, `core`, backed by one PostgreSQL database with one
Flyway migration history. There is no gateway, no separate identity service, no cache
tier, and no object store — see [ADR-0020](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0020-monolith-collapse.md) for
why an earlier ten-service design was collapsed into this one.

## Shape

```
              ┌──────────────────────────────────┐
              │  Clients: web (React), mobile    │
              │  (Expo, iOS + Android)           │
              └──────────────────────────────────┘
                               │ HTTPS
                               ▼
              ┌──────────────────────────────────┐
              │  Traefik (deploy/ only)          │
              │  TLS termination, routing        │
              └──────────────────────────────────┘
                               │
             ┌─────────────────┴─────────────────┐
             │ everything else                   │ /api
             ▼                                   ▼
   ┌──────────────────────┐ ┌──────────────────────────────────────┐
   │  web (nginx)         │ │  core (one JVM)                      │
   │  serves the React app│ │  auth · users · households           │
   └──────────────────────┘ │  transactions · accounts · budgets   │
                            │  networth · ingestion · receipts     │
                            │  categorisation · subscriptions      │
                            │  taxexport · dataexport · ai         │
                            │  notifications                       │
                            │  common · app (shared plumbing and   │
                            │  wiring, not domains)                │
                            └──────────────────────────────────────┘
                                                 │
                                                 ▼
                                ┌──────────────────────────────────┐
                                │  PostgreSQL — one schema,        │
                                │  one Flyway history              │
                                └──────────────────────────────────┘
```

In the Docker Compose deployment, Traefik sends `/api` to the `trove-core` container and
everything else to a `web` container (nginx serving the React app).

`core` is 17 packages under `finance.trove`; all but `common` and `app` (shared plumbing and
application wiring) own a domain. They are Java packages, not deployable units — there is one build,
one JAR, one running process.

## Request flow: importing a transaction

1. The client authenticates (see below) and calls a route under `/api/v1/...`, matched
   against [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml) —
   the same spec `OpenApiRouteDriftIT` and `OpenApiConformance` check every integration
   test run, so the served routes cannot silently diverge from the documented contract.
2. `finance.trove.ingestion` parses CSV, OFX/QFX and SimpleFIN sources and writes rows
   scoped to the caller's household. Manual entry goes through `finance.trove.transactions`
   and receipts through `finance.trove.receipts`.
3. `finance.trove.categorisation` assigns a category: the household's exact then prefix
   merchant-map rows (`MerchantCategoryMap`) first, then `CommunityMerchantRegistry`
   defaults, then — only if the transaction owner has turned AI on in their own
   settings — `finance.trove.ai` calls the endpoint that user configured, and finally the
   Uncategorised system category.
4. `finance.trove.budgets` updates the relevant envelope/period totals.
5. If the import crosses a budget threshold (or a sync fails), `finance.trove.notifications`
   writes an in-app notification (`InAppChannel`, always on). Email (`EmailChannel`)
   exists only when the operator sets `SMTP_ENABLED=true`, and then only for members who
   enabled it in their own notification preferences — see
   [environment variables](./environment-variables).

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

AI is per-user and off by default. `finance.trove.ai.AiGateway` calls whatever OpenAI-compatible
endpoint a user points their own account at from Settings — a local Ollama, a LAN model server,
or a cloud provider. A fresh install calls no AI endpoint at all: there is no bundled model
runtime and no default endpoint. Outbound calls go through
`finance.trove.common.net.OutboundUrlPolicy`, which blocks private and loopback addresses unless
the operator sets `TROVE_EGRESS_ALLOW_PRIVATE=true` (for a LAN model server) and the member also
opts their own endpoint in (`allowPrivateEndpoint`). Webhooks and bank sync go through the same
policy without the opt-in, so they never reach a private address whatever the flag says. With
both keys set, only the private ranges open up (RFC 1918, loopback, carrier-grade NAT, IPv6
unique-local and the deprecated IPv6 site-local `fec0::/10`), plain http is accepted for that
endpoint, and so is an IPv6 address in the deprecated IPv4-compatible (`::/96`) or the
IPv4-mapped (`::ffff:0:0/96`) form (its IPv4 address is still judged); nothing else is lifted.
Link-local, cloud-metadata
(including `100.100.100.200` and the IPv6 metadata addresses of AWS, Google Cloud, Oracle Cloud,
Akamai and Scaleway: `fd00:ec2::254`, `fd00:ec2::23`, `fd20:ce::254`, `fd00:c1::a9fe:a9fe`,
`fd00:a9fe:a9fe::1` and `fd00:42::42`), unspecified, multicast and reserved addresses are always
blocked, and so is every other IPv4 or IPv6 range the IANA special-purpose registries mark not
globally reachable (among them the IETF protocol assignments `192.0.0.0/24` apart from the PCP
and TURN anycast addresses `192.0.0.9` and `192.0.0.10`, documentation `192.0.2.0/24`,
`198.51.100.0/24` and `203.0.113.0/24`, benchmarking `198.18.0.0/15`, the deprecated 6to4 relay
anycast `192.88.99.0/24`, discard-only `100::/64`, benchmarking `2001:2::/48`, the retired ORCHID
`2001:10::/28` and documentation `2001:db8::/32` and `3fff::/20`) and the IPv6 space outside
`2000::/3` that the IETF reserves. An IPv6 address is first checked as IPv6 (link-local,
multicast, the metadata addresses and those ranges are refused before anything else), then by
every IPv4 address it could carry as an IPv4-mapped, IPv4-compatible, SIIT, 6to4, Teredo or
ISATAP address or under the well-known NAT64 prefix `64:ff9b::/96`, and it is refused if any
one of them is. A NAT64 prefix an operator chose for their own network cannot be detected, so
an address under it is judged as the IPv6 address it is. The local-use NAT64 range
`64:ff9b:1::/48` is refused whole: on an IPv6-only install whose DNS64 uses that prefix,
IPv4-only destinations are refused.

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
| Deployment | Docker Compose: `trove-core` + web (nginx, serves the SPA) + Postgres, behind Traefik |
