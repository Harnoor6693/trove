# Tax

Canada and US tax-time export, implemented in `finance.trove.taxexport`. **This is an
export, not a tax engine**: it carries the amounts and category labels the user already
recorded, in CSV/PDF form, for the user's own filing software or accountant. Trove
computes no tax figures, applies no tax rules, and gives no tax advice — see the
README's "What is not here".

The export covers transactions only: transfers between your own accounts and pending
transactions are left out, and other types (income, fees, interest) are listed row by row.
The CSV also ends with a "Totals by tax line" section that sums your recorded amounts per
tax line and currency for spending only (purchases added, refunds subtracted). These are
plain sums of what you recorded, not computed tax figures. Runs and line overrides belong
to the member who made them.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/tax-export/lines` | Category-to-tax-line mapping for a jurisdiction (`CA` or `US`; defaults to the caller's country) |
| `PUT` | `/v1/tax-export/lines/{categoryId}` | Set one category's mapping |
| `GET`/`POST` | `/v1/tax-export/runs` | List / start an export run (POST answers 202) |
| `GET`/`DELETE` | `/v1/tax-export/runs/{id}` | Run status (`PENDING`, `GENERATING`, `COMPLETE`, `FAILED`) / remove a run and its file |
| `GET` | `/v1/tax-export/runs/{id}/download` | Download the CSV/PDF |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Tax export`.
