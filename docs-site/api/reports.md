# Reports (Net worth, dashboard, data export)

There is no standalone report service. "Reports" here covers the read-oriented
endpoints — net worth (`finance.trove.networth`) and the spending/income dashboard
(`finance.trove.transactions.dashboard`) — plus full personal-data export under `finance.trove.dataexport`,
tag `Me` (`/v1/me/export`). For a tax-specific CSV/PDF export of the user's own recorded
amounts, see [Tax](./tax) instead — the two are different features.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/dashboard/today` | Today's and this month's spending/income, per currency, in the caller's own timezone |
| `GET` | `/v1/networth/series` | Net worth over time |
| `POST` | `/v1/networth/snapshot` | Take a net-worth snapshot now (201; idempotent per local date; one is also taken daily) |
| `GET`/`POST` | `/v1/networth/assets`, `/v1/networth/liabilities` | List / add manually-tracked assets and liabilities |
| `PUT`/`DELETE` | `/v1/networth/assets/{id}`, `/v1/networth/liabilities/{id}` | Replace / remove one |
| `GET` | `/v1/me/export` | List data-export runs (paged; maximum `limit` 200) |
| `POST` | `/v1/me/export` | Start a full data export. Needs a step-up body; answers 202. A member may start one export per local day by default (409 after that). The route is rate limited to 30 requests a minute. The result is a ZIP with `manifest.json`, `transactions.csv` and JSON files |
| `GET` | `/v1/me/export/{id}` | Run status |
| `GET` | `/v1/me/export/{id}/download` | Download a completed export |

Full schemas: [`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml), tags `Net Worth`, `Dashboard`, `Me`.
