# Environment Variables

There are two variable sets, for two different things, and the split matters:

- **[`deploy/.env.example`](https://github.com/Harnoor6693/trove/blob/canary/deploy/.env.example)**
  — the deployment. Every variable `deploy/docker-compose.yml` reads, with generation
  commands for every secret and no plausible-looking fake secret checked in. This is
  what `./trove bootstrap` writes for you and what you edit for a real self-host.
- **[`core/.env.example`](https://github.com/Harnoor6693/trove/blob/canary/core/.env.example)**
  — running `core` directly from source (no container), for local development. See the
  [local development guide](./local-development).

This page is deliberately not a third copy of either list. A hand-maintained variable
table drifted out of sync with the shipped `docker-compose.yml` before the 2026-09
collapse (see [ADR-0020](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0020-monolith-collapse.md)) — every variable it
described belonged to a service that no longer exists. The two `.env.example` files are
read by the code that consumes them and cannot drift from it the same way.

## A few things worth knowing before you open either file

- **No secret has a default in `deploy/.env.example`.** `docker-compose.yml` fails fast,
  naming the missing variable, rather than starting with a guessable one.
- **`TROVE_JWT_SECRET` and `TROVE_ENCRYPTION_KEY`** are the two that matter most:
  rotating the JWT secret only logs everyone out, but losing the encryption key loses
  every stored bank credential permanently (it is backed up alongside the database for
  exactly this reason — see backup/restore in `deploy/README.md`).
- **`SMTP_ENABLED` is off by default.** Leaving it off is a normal, fully-supported
  configuration: notifications stay in-app only. See
  [ADR-0011](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0011-threat-model.md) and `finance.trove.notifications`.
- **`TROVE_RECEIPT_QUOTA_MB` is per household, not per user** — one shared quota for
  everyone the admin invites. See [Receipts](../api/receipts).
- **AI variables do not exist in either file.** AI is per-user and configured from
  Settings inside the app, not from an environment variable — see
  [Architecture: AI](./architecture#ai).
