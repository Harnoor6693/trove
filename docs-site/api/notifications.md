# Notifications

Two delivery channels, implemented in `finance.trove.notifications.send`:

- **In-app** (`InAppChannel`) — always on. Read via the endpoints below.
- **Email** (`EmailChannel`) — off by default (`trove.notifications.smtp-enabled`).
  Turning it on requires SMTP configuration; see
  [environment variables](../guide/environment-variables). There is **no push
  notification channel** (no APNs, no FCM) — see the README's "What is not here".

Webhooks (tag `Webhooks`) let a household forward events to its own HTTP endpoint,
HMAC-signed.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/notifications` | List the caller's notifications |
| `GET` | `/v1/notifications/unread-count` | Unread count for a badge |
| `POST` | `/v1/notifications/{id}/read`, `/read-all` | Mark read |
| `GET`/`PUT` | `/v1/notifications/preferences` | Per-channel, per-event preferences |
| `DELETE` | `/v1/notifications/{id}` | Remove a notification |
| `GET`/`POST` | `/v1/webhooks` | List / register a webhook |
| `DELETE` | `/v1/webhooks/{id}` | Remove a webhook |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tags `Notifications`, `Webhooks`.
