# Tax

Canada and US tax-time export, implemented in `finance.trove.taxexport`. **This is an
export, not a tax engine**: it carries the amounts and category labels the user already
recorded, in CSV/PDF form, for the user's own filing software or accountant. Trove
computes no tax figures, applies no tax rules, and gives no tax advice — see the
README's "What is not here".

| Method | Path | Purpose |
|---|---|---|
| `GET`/`PUT` | `/v1/tax-export/lines` | Category-to-tax-line mapping |
| `PUT` | `/v1/tax-export/lines/{categoryId}` | Set one category's mapping |
| `GET`/`POST` | `/v1/tax-export/runs` | List / start an export run for a tax year |
| `GET` | `/v1/tax-export/runs/{id}` | Export run status |
| `GET` | `/v1/tax-export/runs/{id}/download` | Download the CSV/PDF |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tag `Tax export`.
