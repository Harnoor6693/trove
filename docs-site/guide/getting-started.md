# Getting Started

There are two different starting points, depending on what you're doing:

- **Self-hosting Trove for your own household** → [Docker Compose self-hosting](../self-hosting/docker-compose).
  Three commands; Docker builds the images from source on first run (allow 5-15
  minutes), so you need Docker but no JDK or Node.
- **Building or contributing to Trove** → [Local development](./local-development). Runs
  the Spring Boot server and the web/mobile clients straight from source, against a
  Postgres container, with no Docker image build in the loop.

## The three-command version (self-hosting)

```bash
git clone https://github.com/Harnoor6693/trove && cd trove/deploy
./trove bootstrap
./trove up
```

`./trove bootstrap` writes `deploy/.env` with generated secrets, asks for your domain,
Let's Encrypt email, first admin email and country (CA or US), and prints the admin
password once. Do not copy `.env.example` first: bootstrap does nothing if `deploy/.env`
already exists. The TLS mode is set by `TROVE_TLS_MODE` in `deploy/.env` (default
`acme-http`), not prompted for. `./trove up` then builds and starts the stack: the web UI
container, `core` (the one Spring Boot module) and PostgreSQL, behind Traefik. Full
detail — sizing, TLS modes, backup/restore, troubleshooting — is in
[`deploy/README.md`](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md).

## What you get

One web UI, served over your own domain. One optional mobile app (iOS/Android),
connected by pointing it at that same domain from its connect screen — see
[mobile build](../mobile/build). No account with any third-party service is created;
the only outbound calls the server makes are the ones you opt into (an AI endpoint, a
webhook target, SimpleFIN if you connect a bank that way) — see
["What leaves your server"](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md#8-what-leaves-your-server).

## Where to go next

- [Architecture](./architecture) — the shape of the one `core` module and how a request
  flows through it.
- [Local development](./local-development) — running everything from source.
- [API reference](../api/) and the [Postman collection](https://github.com/Harnoor6693/trove/blob/canary/docs/postman/README.md)
  for exercising the API directly.
- [Contributing](./contributing).
