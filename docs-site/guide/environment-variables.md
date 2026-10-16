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
- **Not every core setting is reachable from `deploy/.env`.** The compose file passes
  through only the variables listed in `deploy/.env.example`. Tunables that exist in
  `core/.env.example` but are not wired in compose (trusted proxies, upload and import
  limits, outbound timeouts, metrics access, the scheduled-sync cron
  `TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON`, household name) take their built-in defaults in a
  Docker install unless you add them to the `trove-core` `environment:` block. The scheduled
  bank sync itself is off until an admin switches it on in Settings; the cron (daily 09:17
  UTC) is the only thing the variable controls.
- **`SMTP_ENABLED` is off by default.** Leaving it off is a normal, fully-supported
  configuration: notifications stay in-app only. See
  [ADR-0011](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0011-threat-model.md) and `finance.trove.notifications`.
- **`TROVE_RECEIPT_QUOTA_MB` is per household, not per user** — one shared quota for
  everyone the admin invites. See [Receipts](../api/receipts).
- **No AI provider, model or API-key variables exist.** AI is per-user and configured from
  Settings inside the app — see [Architecture: AI](./architecture#ai). The only
  deployment-level AI switches are the optional `ai` Compose profile (a local Ollama;
  `OLLAMA_*`) and `TROVE_EGRESS_ALLOW_PRIVATE`, which must be true before a user can point at
  a LAN or Ollama endpoint.

## Properties-only settings

These are read by the code but have no entry in either `.env.example` file. Compose passes
them to `trove-core` only if you add them to its `environment:` block.

| Variable | Default | What it does |
|---|---|---|
| `TROVE_ACCOUNT_DELETION_GRACE` | `P7D` | how long a removed member is kept before the hard delete. An ISO-8601 duration, for example `P7D` or `PT72H`. The in-app dialogs say "seven days" verbatim, so if you change this the text no longer matches |
| `TROVE_DATA_EXPORT_DOWNLOAD_TTL` | `PT24H` | how long a data export link stays valid |
| `TROVE_DATA_EXPORT_RUNS_PER_DAY` | `1` | data exports one user may start per day |
| `TROVE_DATA_EXPORT_SWEEP_BATCH_SIZE` | `200` | export archives the hourly sweep handles at once |
