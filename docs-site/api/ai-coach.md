# AI Coach

Optional, off by default. A household turns it on by saving its own AI provider
settings — Trove ships no built-in model access and calls no AI vendor unless
configured to. Outbound calls are checked by
`finance.trove.common.net.OutboundUrlPolicy` (the same SSRF guard used for webhooks
and bank sync), gated by `TROVE_EGRESS_ALLOW_PRIVATE`.

| Method | Path | Purpose |
|---|---|---|
| `GET`/`PUT` | `/v1/ai/settings` | Read / set the household's AI provider config |
| `PUT` | `/v1/ai/settings/api-key` | Set the provider API key |
| `POST` | `/v1/ai/settings/test` | Verify the configured provider is reachable |
| `GET` | `/v1/ai/models` | List models the configured provider offers |
| `POST` | `/v1/chat/completions` | Send a message, streamed (SSE) |
| `GET` | `/v1/chat/suggestions` | Suggested prompts |
| `GET` | `/v1/chat/history` | Past conversation turns |
| `GET` | `/v1/insights` | Generated spending insights |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `AI`.
