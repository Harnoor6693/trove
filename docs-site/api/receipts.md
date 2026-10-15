# Receipts

Receipt upload, OCR extraction, and matching to transactions, implemented in
`finance.trove.receipts`. Files are stored on local disk under `TROVE_DATA_DIR`
(`deploy/.env.example`) — there is no object store. Storage is metered by
`TROVE_RECEIPT_QUOTA_MB`, a quota **per household**, not per user (one shared disk
area — see `ReceiptProperties.quotaMb`, property `trove.receipt.quota-mb`, singular
"receipt").

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/receipts/upload` | Upload a receipt image/PDF |
| `GET` | `/v1/receipts` | List receipts |
| `GET`/`DELETE` | `/v1/receipts/{id}` | Read / remove a receipt |
| `GET` | `/v1/receipts/{id}/status` | OCR/processing status |
| `POST` | `/v1/receipts/{id}/review` | Confirm or correct OCR-extracted fields |
| `GET` | `/v1/receipts/{id}/download`, `/thumbnail` | Original file / generated thumbnail |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Receipts`.
