# Subscriptions

Recurring-payment detection from transaction history, implemented in
`finance.trove.subscriptions`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/subscriptions` | List detected subscriptions |
| `GET` | `/v1/subscriptions/summary` | Total recurring spend |
| `GET` | `/v1/subscriptions/alerts` | Unread price-increase alerts (the type enum also reserves `UPCOMING` and `PAYMENT_FAILED`; detection currently writes only `PRICE_INCREASE`) |
| `PATCH` | `/v1/subscriptions/alerts/{id}/read` | Mark an alert read |
| `POST` | `/v1/subscriptions/scan` | Re-run detection on demand |
| `GET`/`DELETE` | `/v1/subscriptions/{id}` | Read one (cancelled ones included, `cancelled: true`) / dismiss it (soft delete; the row is kept so a scan does not re-detect it) |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Subscriptions`.

Subscriptions are created only by detection (nightly job at 02:00 UTC, `POST /v1/subscriptions/scan`, after
imports). There is no create or update.
