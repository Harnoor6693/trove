# Transactions

The household's transaction ledger, implemented in `finance.trove.transactions`.
Transactions arrive via CSV/OFX/QFX import, SimpleFIN bank sync (see [Imports](./ingestion)) or manual entry,
and are enriched with categories, receipts, and subscription detection.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/transactions` | List/filter/page the ledger |
| `POST` | `/v1/transactions` | Record a transaction by hand |
| `GET` | `/v1/transactions/{id}` | One transaction |
| `PATCH` | `/v1/transactions/{id}` | Update category, merchant, notes, tags or transfer link (`{"transferPartnerId": "<id>"}` links both legs at once; `{"transferGroupId": null}` unlinks a transfer) |
| `DELETE` | `/v1/transactions/{id}` | Remove a manually entered transaction (synced rows: 409) |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Transactions`.

`GET /v1/transactions` takes `page`, `limit` (default 50, maximum 500), `sort` (`date,asc`,
`date,desc`, `amount,asc`, `amount,desc`), `from`/`to` (ISO dates), `type`, `accountId`,
`categoryId` and `q` (up to 200 characters). Unpaired transfers between your own accounts
are matched into pairs by an hourly job.

To link two legs by hand, send `{"transferPartnerId": "<other transaction id>"}` to either one.
The server writes one new group id onto both rows in a single database transaction, so both
are linked or neither is. It answers 400 when the body also carries `transferGroupId` or names
the row itself, 404 when the partner is not in your household, and 409 when either row is
already part of a transfer (unlink it first). Unlinking one leg with
`{"transferGroupId": null}` frees its partner as well.

## Accounts (tag: Accounts)

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/accounts` | List / create accounts |
| `GET`/`PUT`/`DELETE` | `/v1/accounts/{id}` | Read, replace, remove an account |
| `POST` | `/v1/accounts/exchange-setup-token` | Exchange a SimpleFIN setup token |
| `POST` | `/v1/accounts/{id}/sync` | Sync now |
| `GET` | `/v1/accounts/{id}/sync-logs` | Recent sync runs |

See [Imports](./ingestion) for the sync routes.

A pre-built Postman collection, generated from the same spec, ships at
[`docs/postman/`](https://github.com/Harnoor6693/trove/tree/canary/docs/postman) —
see its [README](https://github.com/Harnoor6693/trove/blob/canary/docs/postman/README.md).
