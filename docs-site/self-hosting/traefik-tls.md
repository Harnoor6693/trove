# Traefik + TLS

TLS termination is Traefik, configured by `deploy/trove` from `TROVE_TLS_MODE` in
`deploy/.env`. There are three modes:

- **`acme-http`** (default) — Let's Encrypt via HTTP-01. Needs inbound TCP/80.
- **`acme-dns`** — Let's Encrypt via DNS-01 against Cloudflare, for installs behind
  CGNAT or without inbound port 80. Needs `CF_DNS_API_TOKEN`.
- **`off`** — no TLS in Traefik at all, for running behind Tailscale Serve or another
  terminating proxy.

The full walkthrough for each mode — including the rendered Traefik dynamic config,
certificate storage, and how to switch modes on an existing install — is
["3. Domain and TLS"](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md#3-domain-and-tls)
in `deploy/README.md`. This page intentionally does not duplicate it.

For what Traefik does and does not protect once traffic reaches it (rate limiting,
security headers, what a compromised container could still reach), see
["What is and is not secured"](https://github.com/Harnoor6693/trove/blob/canary/deploy/README.md#7-what-is-and-is-not-secured)
in the same document.
