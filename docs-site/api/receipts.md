# Receipts

Receipt upload, AI-assisted field extraction (via the member's own configured endpoint; manual entry when AI is off), and matching to transactions, implemented in
`finance.trove.receipts`. Files are stored on local disk under `TROVE_DATA_DIR`
(`deploy/.env.example`) — there is no object store. Storage is metered by
`TROVE_RECEIPT_QUOTA_MB`, a quota **per household**, not per user (one shared disk
area — see `ReceiptProperties.quotaMb`, property `trove.receipt.quota-mb`, singular
"receipt").

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/receipts/upload` | Upload a receipt photo (JPEG or PNG only; PDF and HEIC are refused). Multipart `file` plus optional `source` (`UPLOAD` or `SCAN`); answers 202 |
| `GET` | `/v1/receipts` | List receipts |
| `GET`/`PATCH`/`DELETE` | `/v1/receipts/{id}` | Read / fill in fields by hand and link a transaction / remove |
| `GET` | `/v1/receipts/{id}/status` | Processing status |
| `PATCH` | `/v1/receipts/{id}/review` | Accept or reject the extracted fields |
| `GET` | `/v1/receipts/{id}/download`, `/thumbnail` | Stored image (re-encoded to JPEG at upload; the original bytes are not kept) / generated thumbnail |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Receipts`.
