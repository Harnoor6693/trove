# Reports (Net worth, dashboard, data export)

There is no standalone report service. "Reports" here covers the read-oriented
endpoints — net worth and the spending/income dashboard, both under
`finance.trove.networth` — plus a full personal-data export under `finance.trove.dataexport`,
tag `Me` (`/v1/me/export`). For a tax-specific CSV/PDF export of the user's own recorded
amounts, see [Tax](./tax) instead — the two are different features.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/dashboard/today` | Today's and this month's spending/income, per currency, in the caller's own timezone |
| `GET` | `/v1/networth/series` | Net worth over time |
| `GET`/`POST` | `/v1/networth/snapshot` | Point-in-time net worth |
| `GET`/`POST` | `/v1/networth/assets`, `/liabilities` | Manage manually-tracked assets/liabilities |
| `GET`/`POST` | `/v1/me/export` | List / start a full data-export run (JSON/CSV archive of the caller's own data) |
| `GET` | `/v1/me/export/{id}/download` | Download a completed export |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tags `Net Worth`, `Dashboard`, `Me`.
