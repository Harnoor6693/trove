# Docker Compose (the only supported self-hosting path)

Trove ships one deployment: `core` + PostgreSQL behind Traefik, defined in
[`deploy/docker-compose.yml`](https://github.com/Harnoor6693/trove/blob/canary/deploy/docker-compose.yml),
driven by the `./trove` wrapper script. There is no Kubernetes manifest and no
supported bare-metal install — see [ADR-0020](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0020-monolith-collapse.md)
for why one deployment shape is enough for a single-household install.

**[`deploy/README.md`](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md)
is the canonical, step-by-step guide** — requirements and sizing, the 10-minute install,
TLS modes, day-to-day commands, backup/restore, troubleshooting by symptom, and exactly
what is and is not secured. This page exists only to point you there and stays short on
purpose: duplicating that document here is how documentation like this drifted from the
shipped system before the 2026-09 collapse (see [ADR-0020](https://github.com/Harnoor6693/trove/blob/canary/docs/adr/0020-monolith-collapse.md)).

## The short version

```bash
git clone https://github.com/Harnoor6693/trove && cd trove/deploy
cp .env.example .env
./trove bootstrap
```

`./trove bootstrap` generates every secret, asks for your domain and TLS mode, and
brings the stack up. `./trove status`, `./trove logs`, `./trove backup`, and
`./trove doctor` cover day-to-day operation. Full detail, including the three TLS
modes (`acme-http`, `acme-dns`, `off`/Tailscale) and the backup/restore mechanics, is in
`deploy/README.md`.

## Optional profiles

`COMPOSE_PROFILES` in `deploy/.env` turns on:

- `ai` — a local Ollama container on an internal network. Nothing calls it until a user
  points their own account at `http://ollama:11434/v1` from Settings → AI.
- `monitoring` — Prometheus and Grafana, bound to `127.0.0.1` only (reach them over an
  SSH tunnel; they are never routed through Traefik).

Neither is required. See `deploy/.env.example` for every variable both profiles read.

## For local development instead

If you're building Trove from source rather than deploying it, see the
[local development guide](../guide/local-development) instead — it does not use
`deploy/docker-compose.yml`.
