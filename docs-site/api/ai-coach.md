# AI Coach

Optional, off by default. A member turns it on by saving their own AI provider
settings (per user, not per household) — Trove ships no built-in model access and calls no AI vendor unless
configured to. Outbound calls are checked by
`finance.trove.common.net.OutboundUrlPolicy` (the same SSRF guard used for webhooks
and bank sync), gated by `TROVE_EGRESS_ALLOW_PRIVATE` and, per member, the `allowPrivateEndpoint` setting; both must be on to reach a private address.

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
