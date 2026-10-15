# Transactions

The household's transaction ledger, implemented in `finance.trove.transactions`.
Transactions arrive via CSV/OFX import (see [Imports](./ingestion)) or manual entry,
and are enriched with categories, receipts, and subscription detection.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/transactions` | List/filter/page the ledger |
| `POST` | `/v1/transactions` | Record a transaction by hand |
| `GET` | `/v1/transactions/{id}` | One transaction |
| `PATCH` | `/v1/transactions/{id}` | Enrich one transaction (category, notes, tags) |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Transactions`.

A pre-built Postman collection, generated from the same spec, ships at
[`docs/postman/`](https://github.com/Harnoor6693/trove/tree/canary/docs/postman) —
see its [README](https://github.com/Harnoor6693/trove/blob/canary/docs/postman/README.md).
