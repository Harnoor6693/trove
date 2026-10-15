# Ingestion (Imports)

File and connector-based transaction import, implemented in `finance.trove.ingestion`.
Manual single-transaction entry goes through the [Transactions](./transactions) endpoints
instead — this domain is for bulk/external sources: CSV, OFX/QFX, SimpleFIN, and the
bank-source connectors under `ingestion/` (email/IMAP alert parsing, SMS, OFX Direct
Connect — see `.github/ISSUE_TEMPLATE/bank_plugin.yml` for adding one).

| Method | Path | Purpose |
|---|---|---|
| `GET`/`POST` | `/v1/imports` | List import runs / upload a file to import |
| `GET` | `/v1/imports/{id}/preview` | Parsed rows before committing, with duplicate flags |
| `POST` | `/v1/imports/{id}/commit` | Commit the previewed rows as transactions |
| `GET` | `/v1/imports/{id}` | Import run status |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Imports`.
