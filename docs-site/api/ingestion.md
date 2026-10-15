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

## Bank sync and the scheduled sync switch

A bank connected through SimpleFIN syncs when someone presses **Sync** on the account
(`POST /v1/accounts/{id}/sync`). An admin can also have the server do it for everyone:

- **Where:** web, **Settings → Administration → Bank sync**; mobile, the **Admin** screen
  under Settings. The switch is labelled **Sync connected banks every day** and is off on a
  new install. It is stored on the household (`scheduledSyncEnabled` on
  `GET`/`PUT /v1/admin/settings`), so it survives a restart.
- **What it does:** once a day, the server syncs every SimpleFIN-connected account in the
  install, exactly as if the person who connected it had pressed Sync. Manual, CSV and OFX
  accounts are left alone. If someone is already syncing an account by hand at that moment,
  the scheduled run skips that account.
- **Why once a day:** SimpleFIN Bridge refreshes its data from the bank once every 24 hours
  and asks apps to make at most 24 requests a day
  ([Bridge developer guide](https://beta-bridge.simplefin.org/info/developers)). Syncing
  more often would fetch nothing new and spend that allowance. The run starts at 09:17 UTC,
  off the busy top of the hour. If you run `core` from source you can change the time with
  `TROVE_SIMPLEFIN_SCHEDULED_SYNC_CRON` (see `core/.env.example`); please keep it daily.
- **When a connection stops working:** the person who connected the account gets one
  "sync failed" notification when it goes from working to failing — not one every day.
  They hear about it again only if it recovers and then fails again. If that person has
  left the household, the household admin is told instead. A sync you start by hand keeps
  its old behaviour and reports its own failure.
- **How fresh is it:** Accounts, the Today page and the mobile account cards show
  "Synced 3 hours ago" for each SimpleFIN account; hover (web) or use a screen reader
  (mobile) for the exact time.
