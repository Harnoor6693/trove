# AI Coach

Optional, off by default. A member turns it on by saving their own AI provider
settings (per user, not per household) — Trove ships no built-in model access and calls no AI vendor unless
configured to. Outbound calls are checked by
`finance.trove.common.net.OutboundUrlPolicy` (the same SSRF guard used for webhooks
and bank sync, which always refuse private addresses), gated by `TROVE_EGRESS_ALLOW_PRIVATE` and, per member, the `allowPrivateEndpoint` setting; both must be on for the AI endpoint to reach a private address (RFC 1918, loopback, carrier-grade NAT, IPv6 unique-local or the deprecated IPv6 site-local `fec0::/10`) or to use plain http; the two keys lift nothing else. Link-local, cloud-metadata (including the IPv6 metadata addresses of AWS, Google Cloud, Oracle Cloud, Akamai and Scaleway), unspecified, multicast and reserved addresses, every other IPv4 or IPv6 range the IANA special-purpose registries mark not globally reachable (such as `192.0.2.0/24`, `198.18.0.0/15` and `2001:db8::/32`) and the IPv6 space outside `2000::/3` that the IETF reserves are refused regardless. An IPv6 address is first checked as IPv6 (link-local, multicast, the metadata addresses and those ranges are refused before anything else), then by every IPv4 address it could carry as an IPv4-mapped, IPv4-compatible, SIIT, 6to4, Teredo or ISATAP address or under the well-known NAT64 prefix `64:ff9b::/96`, and it is refused if any one of them is. A NAT64 prefix an operator chose for their own network cannot be detected, so an address under it is judged as the IPv6 address it is. The local-use NAT64 range `64:ff9b:1::/48` is refused whole: on an IPv6-only install whose DNS64 uses that prefix, IPv4-only destinations are refused.

| Method | Path | Purpose |
|---|---|---|
| `GET`/`PUT` | `/v1/ai/settings` | Read / set your own AI provider config (`apiKey` is write-only and never returned) |
| `DELETE` | `/v1/ai/settings/api-key` | Forget the stored API key (set a key through `apiKey` in `PUT /v1/ai/settings`) |
| `POST` | `/v1/ai/settings/test` | Verify the configured provider is reachable |
| `GET` | `/v1/ai/models` | List models the configured provider offers |
| `POST` | `/v1/chat/completions` | Send a message. Add `?stream=true` for an SSE stream; otherwise the full answer comes back as JSON. The conversation id travels in the `X-Session-Id` header |
| `GET` | `/v1/chat/suggestions` | Suggested prompts |
| `GET` | `/v1/chat/history` | Past conversation turns (paged: `sessionId`, `page`, `limit`) |
| `DELETE` | `/v1/chat/history` | Clear one conversation (`?sessionId=`) or all of yours |
| `GET` | `/v1/insights` | Generated spending insights (paged) |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `AI`.
