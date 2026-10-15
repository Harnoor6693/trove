# Budgets

Envelope-style budgets, periods, and rollover, implemented in
`finance.trove.budgets` (packages `envelope`, `period`, `rollover`, `goal`).

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/budgets` | List / create budgets |
| `GET` | `/v1/budgets/current` | The active period's envelopes and totals |
| `GET`/`PATCH`/`DELETE` | `/v1/budgets/{id}` | Read, update, remove a budget |
| `GET`/`POST` | `/v1/budgets/{id}/envelopes` | List / add envelopes |
| `PATCH`/`DELETE` | `/v1/budgets/{id}/envelopes/{envelopeId}` | Update / remove an envelope |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Budgets`.
