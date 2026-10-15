# Subscriptions

Recurring-payment detection from transaction history, implemented in
`finance.trove.subscriptions`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/subscriptions` | List detected subscriptions |
| `GET` | `/v1/subscriptions/summary` | Total recurring spend |
| `GET` | `/v1/subscriptions/alerts` | Price-change / new-subscription alerts |
| `POST` | `/v1/subscriptions/alerts/{id}/read` | Mark an alert read |
| `POST` | `/v1/subscriptions/scan` | Re-run detection on demand |
| `GET`/`PATCH`/`DELETE` | `/v1/subscriptions/{id}` | Read, confirm/dismiss, or remove a detected subscription |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Subscriptions`.
