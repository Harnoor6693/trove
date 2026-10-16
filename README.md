# Trove

**Your money, fully understood — not just tracked.**

Trove is a self-hostable personal finance platform for one household: transactions,
budgets, net worth, receipts, subscriptions, and tax-time export. It runs on your own
hardware, behind your own domain, with no required third-party account.

[![License: AGPL-3.0-or-later](https://img.shields.io/badge/License-AGPL_3.0_or_later-blue.svg)](LICENSE)
[![CI](https://github.com/Harnoor6693/trove/actions/workflows/ci.yml/badge.svg)](https://github.com/Harnoor6693/trove/actions/workflows/ci.yml)

📚 **[Read the documentation →](https://harnoor6693.github.io/trove)**

---

## What this is

One Spring Boot module, `core` (17 packages under `finance.trove`), backed by one
PostgreSQL database with one Flyway migration history. A React web client and an
Expo (React Native) mobile client talk to it over one OpenAPI 3.0.3 contract,
[`openapi/trove-core.yaml`](openapi/trove-core.yaml) — every integration test in `core`
conforms the running server's routes against that spec, so the spec is never allowed to
drift from what the server actually serves. One deployment, under [`deploy/`](deploy/),
runs the whole thing behind Traefik with automatic TLS.

- **Transactions, budgets, net worth** — manual entry, CSV/OFX import, SimpleFIN
  (manual or scheduled sync), receipt upload; envelope-style budgets; rolling net worth.
- **Receipt upload** — a JPEG or PNG photo; fields are extracted by your own AI endpoint
  if you configured one, otherwise entered by hand; matched to a transaction, stored on
  local disk under a per-household quota (not per-user — see [Receipts](docs-site/api/receipts.md)).
- **Subscription detection** — finds recurring payments from transaction history.
- **Scheduled bank sync** — optional daily SimpleFIN sync (09:17 UTC by default). Off by
  default; an admin turns it on in settings, so an upgrade never starts calling a third
  party by itself.
- **Household administration** — the admin invites members, changes roles, unlocks
  accounts and resets 2FA.
- **Webhooks** — each member can register signed outbound webhooks for events.
- **Data export** — each member can export their own data as a downloadable archive.
- **Tax-time export** — CSV/PDF export of the user's own recorded amounts and
  categories for Canada or US filing. It is an export, not a tax engine: see
  "What is not here" below.
- **AI Coach and AI categorisation** — optional, off by default, per-user. A user
  points their own account at an OpenAI-compatible endpoint (a local Ollama, or a
  cloud provider) from Settings; nothing is called until they do.
- **Cross-platform** — React web app; Expo/React Native app for iOS and Android.

## What is not here

- **No push notifications.** The in-app inbox and (optional, off by default) email are
  the only notification channels; members can additionally register signed outbound
  webhooks.
- **No tax calculation engine.** Tax export carries the amounts and labels the user
  already entered; Trove computes no tax figures and gives no tax advice.
- **One household per install.** Multi-tenancy is out of scope; run a second install
  for a second household.
- **Canada and US reporting only.** No other jurisdictions are modelled.
- **No AI by default.** The AI Coach and AI categorisation are per-user opt-in
  integrations against an endpoint the user supplies; a fresh install calls no AI
  service at all.
- **No app store distribution.** See "Getting the mobile app" below.

## Quick start

```bash
git clone https://github.com/Harnoor6693/trove && cd trove/deploy
./trove bootstrap   # writes .env with generated secrets, prints the admin login
./trove up          # builds and starts the stack
```

`./trove bootstrap` asks for your domain, a Let's Encrypt email, the first admin address
and country (CA or US, fixed after first boot), and prints the admin password it
generated. The TLS mode is not prompted for: it comes from `TROVE_TLS_MODE` in `deploy/.env`
(default `acme-http`). Full install steps, sizing, backup/restore, and troubleshooting
are in [`deploy/README.md`](deploy/README.md) — that document, not this one, is the
source of truth for running Trove in production.

For building and running the server and clients from source on a workstation, see the
[local development guide](docs-site/guide/local-development.md). For exercising the API
directly, see the [Postman collection](docs/postman/README.md), generated from the
OpenAPI spec and never hand-edited.

## Getting the mobile app

There is no App Store or Play Store listing. Distribution is by direct install:

- **iOS** — no prebuilt binary is published. Build it yourself with EAS on your own Apple
  developer account (see [`docs-site/mobile/build.md`](docs-site/mobile/build.md)); the
  release workflow only publishes the Android APK.
- **Android** — a signed APK built with EAS and installed directly.

See [`docs-site/mobile/app-store-release.md`](docs-site/mobile/app-store-release.md) for
why: an app that reads bank credentials and balances is a poor fit for a store review
process built around consumer apps with a vendor's backend, and self-hosting means there
is no single backend a store listing could point at.

## Security

Trove holds bank credentials, balances, and receipts. Before pointing it at a real
account:

- **Get it penetration-tested before you trust it with real bank data.** No
  external penetration test of this codebase has been performed. It has ArchUnit rules,
  Checkstyle, and a JaCoCo coverage floor enforced in CI, but none of that substitutes
  for an adversarial review of a system that stores financial credentials. Treat a fresh
  self-host as unaudited until you or someone you trust has looked at it that way.
- **Report a vulnerability privately.** Do not open a public issue. Use the
  repository's private vulnerability reporting (GitHub → Security → "Report a
  vulnerability") so a fix can ship before the report is public. See
  [`SECURITY.md`](SECURITY.md).
- See [`deploy/README.md`](deploy/README.md) for what is and is not secured by the
  shipped deployment (TLS termination, secret generation, network isolation, what
  leaves the server).

## Project structure

```
core/           The Spring Boot monolith — 17 packages under finance.trove
apps/web/       React + Vite web client
apps/mobile/    Expo (React Native) mobile client, iOS + Android
deploy/         The one supported deployment: Traefik + trove-core + Postgres
openapi/        trove-core.yaml — the API contract, conformance-tested every IT
docs/           ADRs, architecture notes, Postman collection, recovery record
docs-site/      VitePress developer documentation site
tests/performance/  k6 load profile (tests/performance/trove-load.js)
```

## Documentation

- **Local development:** [docs-site/guide/local-development.md](docs-site/guide/local-development.md)
- **Architecture:** [docs-site/guide/architecture.md](docs-site/guide/architecture.md)
- **Self-hosting:** [docs-site/self-hosting/docker-compose.md](docs-site/self-hosting/docker-compose.md) and [`deploy/README.md`](deploy/README.md)
- **API reference:** [docs-site/api/](docs-site/api/)
- **Postman collection:** [docs/postman/README.md](docs/postman/README.md)
- **Mobile build & release:** [docs-site/mobile/](docs-site/mobile/)
- **Architecture Decision Records:** [docs/adr/](docs/adr/)

Build the docs site locally:

```bash
cd docs-site
npm ci
npm run docs:dev   # http://localhost:5173
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the Maven and npm gate commands, coverage
floor, and the spec-before-controller rule.

## License

[AGPL-3.0-or-later](LICENSE) — free forever, for everyone.
Third-party components and their licences are listed in [NOTICE](NOTICE).
