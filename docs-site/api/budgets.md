# Budgets

Envelope-style budgets, periods, and rollover, implemented in
`finance.trove.budgets` (packages `envelope`, `period`, `rollover`, `goal`).

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/budgets` | List / create budgets |
| `GET` | `/v1/budgets/current` | This month's budget period (month in the caller's timezone; 404 if none). Fetch its envelopes with `GET /v1/budgets/{id}/envelopes` |
| `GET`/`PUT`/`DELETE` | `/v1/budgets/{id}` | Read, replace, remove a budget |
| `GET`/`POST` | `/v1/budgets/{id}/envelopes` | List / add envelopes |
| `GET`/`PUT`/`DELETE` | `/v1/budgets/{id}/envelopes/{envelopeId}` | Read, replace, remove an envelope |
| `GET`/`POST` | `/v1/goals` | List / create savings goals |
| `PUT`/`DELETE` | `/v1/goals/{id}` | Replace / remove a goal |

Rollover: a daily job at 00:40 UTC carries unspent envelope balances into the next month
for `ROLLOVER` budgets. After downtime it catches up one month per run.

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Budgets`.
