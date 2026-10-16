# Notifications

Two delivery channels, implemented in `finance.trove.notifications.send`:

- **In-app** (`InAppChannel`) — always on. Read via the endpoints below.
- **Email** (`EmailChannel`) — off by default (`trove.notifications.smtp-enabled`).
  Turning it on requires SMTP configuration; see
  [environment variables](../guide/environment-variables). There is **no push
  notification channel** (no APNs, no FCM) — see the README's "What is not here".

Webhooks (tag `Webhooks`) let a member forward events to their own HTTP endpoint (up to 20
per member by default), HMAC-signed with a write-only secret of 16 to 128 characters.
Subscriptions belong to the member who created them, not the household. Event types:
`budget.threshold_crossed`, `subscription.price_changed`, `sync.completed`,
`receipt.processed`. Payloads carry ids, enum names, counts and amounts only, never merchant
or bank text; fetch detail from the API. A failed delivery is retried with exponential
backoff, up to 5 attempts.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/notifications` | List the caller's notifications |
| `GET` | `/v1/notifications/unread-count` | Unread count for a badge |
| `PATCH` | `/v1/notifications/{id}/read` | Mark one read |
| `POST` | `/v1/notifications/read-all` | Mark all read |
| `GET`/`PUT` | `/v1/notifications/preferences` | Per-channel, per-event preferences; read-only `emailAvailable` says whether this server can send email at all |
| `DELETE` | `/v1/notifications/{id}` | Remove a notification |
| `GET`/`POST` | `/v1/webhooks` | List / register a webhook |
| `DELETE` | `/v1/webhooks/{id}` | Remove a webhook |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tags `Notifications`, `Webhooks`.

## Verifying a webhook delivery

Deliveries are signed in the [Standard Webhooks](https://www.standardwebhooks.com)
scheme (symmetric `v1`). Each POST carries:

| Header | Value |
|---|---|
| `webhook-id` | The delivery id. Stable across retries: drop one you have already processed. |
| `webhook-timestamp` | Unix seconds of this attempt. |
| `webhook-signature` | `v1,<base64>`: HMAC-SHA256 over `<webhook-id>.<webhook-timestamp>.<raw body>`. |
| `X-Trove-Event` | The event type, for routing. |

The HMAC key is the UTF-8 bytes of the `secret` you registered, exactly as you typed it.
To verify:

1. Refuse the request if `webhook-timestamp` is more than **5 minutes** from your clock.
   This is what stops a captured delivery being replayed later.
2. Compute HMAC-SHA256 over `<webhook-id>.<webhook-timestamp>.` followed by the raw
   request body bytes. Do not parse and re-serialise the JSON first.
3. Base64-encode it and compare, in constant time, with the part after `v1,` in
   `webhook-signature` (the header may list several space-separated entries; accept
   if any matches).
4. Remember `webhook-id` and ignore a repeat. A retry, or a delivery resumed after a
   server restart, reuses the same id.

Standard Webhooks libraries expect a base64 `whsec_` secret by default. Give them
your secret's raw UTF-8 bytes instead, for example
`new Webhook(new TextEncoder().encode(secret), { format: "raw" })` in the JavaScript
library.

::: warning Changed
Earlier builds sent `X-Trove-Signature: sha256=<hex>` over the body only, plus
`X-Trove-Delivery`. Those headers are gone. Receivers must switch to the scheme above.
:::
