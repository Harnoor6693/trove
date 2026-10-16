# Traefik + TLS

TLS termination is Traefik, configured by `deploy/trove` from `TROVE_TLS_MODE` in
`deploy/.env`. There are three modes:

- **`acme-http`** (default) — Let's Encrypt via HTTP-01. Needs inbound TCP/80.
- **`acme-dns`** — Let's Encrypt via DNS-01 against Cloudflare, for installs behind
  CGNAT or without inbound port 80. Needs `CF_DNS_API_TOKEN`.
- **`off`** — no TLS in Traefik at all, for running behind Tailscale Serve or another
  terminating proxy.

The full walkthrough for each mode — including the rendered static Traefik config,
certificate storage (the `traefik_acme` volume, included in backups) and the per-mode
`.env` settings — is
["3. Domain and TLS"](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md#3-domain-and-tls)
in `deploy/README.md`. This page intentionally does not duplicate it. To switch modes,
edit `TROVE_TLS_MODE` in `deploy/.env` (plus `CF_DNS_API_TOKEN` for `acme-dns`, and
`TROVE_HTTP_PORT`/`TROVE_HTTPS_PORT` for `off`), then run `./trove down && ./trove up` so Traefik
restarts with the re-rendered static config. Data volumes are not touched.

In `off` mode Traefik binds `127.0.0.1` by default (`TROVE_BIND_ADDR`), so only a proxy on the same
host can reach it. This is a breaking change for an existing `off` install whose terminating proxy
runs on another host: set `TROVE_BIND_ADDR` in `deploy/.env` to an interface that proxy can reach,
then restart.

For what Traefik does and does not protect once traffic reaches it (rate limiting,
security headers, what a compromised container could still reach), see
["What is and is not secured"](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md#7-what-is-and-is-not-secured)
in the same document.
