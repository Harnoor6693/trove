# Getting Started

There are two different starting points, depending on what you're doing:

- **Self-hosting Trove for your own household** → [Docker Compose self-hosting](../self-hosting/docker-compose).
  Three commands, no source build required.
- **Building or contributing to Trove** → [Local development](./local-development). Runs
  the Spring Boot server and the web/mobile clients straight from source, against a
  Postgres container, with no Docker image build in the loop.

## The three-command version (self-hosting)

```bash
git clone https://github.com/Harnoor6693/trove && cd trove/deploy
cp .env.example .env
./trove bootstrap
```

`./trove bootstrap` generates every secret, asks for your domain and TLS mode, and
brings the whole stack up: `core` (the one Spring Boot module) and PostgreSQL, behind
Traefik. It prints the admin email and password it generated for the first login. Full
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
