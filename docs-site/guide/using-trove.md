# Using Trove

This page is a tour of the screens in the web app and the mobile app, for the people who
use a Trove install every day. Setting one up is covered in
[Quick Start](./getting-started) and [Docker Compose self-hosting](../self-hosting/docker-compose).

Trove supports Canada and the United States. It never calls a bank on its own unless you
connect one, and it does not compute tax: the tax export hands your categorised
transactions to your accountant.

## Signing in for the first time

You get in one of two ways:

- **An invite link.** An administrator creates it in Settings (see
  [Household invites](#household-invites)). Open `/invite/<token>` and choose a password.
- **The first administrator.** The install creates this account from `TROVE_ADMIN_EMAIL`
  and `TROVE_ADMIN_PASSWORD` in `deploy/.env`. `./trove bootstrap` generates that password
  and prints it once.

Self-registration at `/register` is closed unless an administrator opens it
(`TROVE_REGISTRATION_OPEN`, or the Registration switch in Settings).

After you sign in for the first time, the web app opens a three-step setup (`/onboarding`).
You can leave it and come back. The setup shows your country and currency, which were fixed
when the account was created. If you forget your password, an administrator can create a
reset link for you; it opens `/reset-password/<token>`.

## The web app

The sidebar lists these screens. Everyone in the household sees all of them.

| Screen | What it is for |
| --- | --- |
| Today | The overview you land on. |
| Transactions | The household ledger. Open a row for its detail. |
| Accounts | Your accounts: add a manual, CSV or OFX account, connect a bank, and import a file. |
| Budgets | Budget periods and envelopes. Sub-pages: Categories, Rules and Goals. |
| Net Worth | Assets, liabilities and snapshots over time. |
| Subscriptions | Recurring charges Trove has detected. |
| Receipts | Upload receipt photos and review what was read from them. |
| Coach | Ask questions about your own numbers, using the AI endpoint you configure. |
| Tax Export | Export a calendar year of categorised transactions. |
| Notifications | Your in-app inbox. |
| Settings | Your profile, security and household. |

### Importing transactions

Add an account of kind CSV or OFX, then use **Import a file** on its row. Trove accepts
`.csv`, `.ofx` and `.qfx` files. The import runs in three steps: upload, preview, commit.
Nothing is written until you have seen the preview, and a finished import can be undone from the Accounts page until you leave it.

### Receipts

Receipts take JPEG or PNG images. After you upload one, Trove reads it in the background,
so its fields appear a moment later. Reading uses the AI endpoint set in Settings.

### Tax export

Choose a tax year and a format (CSV or PDF). The file contains your household's categorised
transactions for that calendar year. Trove shows no totals, rates or estimates. Lines
carry labels only, and the Tax lines panel lets you set the line code and wording for each category before you export.

### Settings

Settings holds these sections:

- **Profile**: display name and time zone. Your email and reporting currency are shown but
  fixed.
- **Dates and numbers**: how dates and amounts are written, with an example.
- **Household invites**: see below.
- **AI endpoint**: the API key, chat model, receipt-reading model and timeouts for the
  service Trove calls for Coach and receipts.
- **Notifications**: which alerts you receive, including the budget alert threshold.
  Email is off unless the install has configured it.
- **Webhooks**: URLs that Trove calls when something happens. Each has a signing secret.
- **Account security**: change your password, set up two-factor authentication (TOTP) with
  recovery codes, and review recent security activity.
- **Your data**: export everything, and delete your account.

#### Household invites

Administrators create and revoke invites here. Other members see a short note instead.

#### Exporting your data

**Export everything** builds a ZIP file in the background. It holds the household's ledger
(transactions as CSV, and JSON files for accounts, categories, rules, budgets, goals,
subscriptions, net-worth records, receipts, coach conversations and notification
preferences) and a manifest. Receipts are included as details, not images; download the
images one at a time from the Receipts screen. The archive stays downloadable for 24 hours,
and you can start one export a day. Trove asks for your password, and a code if you use
two-factor authentication.

#### Deleting your account

**Delete my account** asks for the same password and code. The account is disabled at once
and every session ends. For seven days an administrator can restore it. After that it is
erased: your password, sessions, AI settings, webhooks, coach conversations, tax-line
overrides and exports go with it. The household keeps the shared ledger, with your name
removed from the rows you entered.

### Administrator screens

Only administrators get the Administration console in Settings. Other members see a short
note in its place. The server checks the role on every call as well. The console has:

- **Household members**: make a member an administrator or remove that role, create a
  password reset link, restore a removed member, clear a lock, remove two-factor
  authentication, or remove a member.
- **Registration**: open or close self-registration.
- **Bank sync**: switch bank sync on or off.
- **Audit log**: a record of what changed.

## The mobile app

The mobile app (Expo, for iOS and Android) first asks for your server's address on a
**Connect** screen, then signs you in with your email and password, plus a code if you use
two-factor authentication.

The tab bar has eight tabs:

| Tab | What is in it |
| --- | --- |
| Today | The overview. |
| Transactions | The ledger; add a transaction; accounts; import a CSV or OFX file. |
| Budgets | Periods and envelopes; Categories, Rules and Goals. |
| Net Worth | Assets and liabilities; tax export. |
| Subs | Subscriptions and their alerts. |
| Receipts | Receipt photos and what was read from them. |
| Coach | The AI coach. |
| Settings | Everything else. |

Settings on mobile holds the server you are connected to, your profile, country and
locale, the light, dark or system theme, security, the AI endpoint, categories and rules,
notifications, webhooks, your data, and an About section with the source-code link.
Administrators also get an **Administration** card with an **Open admin console** button.
The console holds members, invitations, the sign-up switch, bank sync and the audit log.
Notifications are opened from the Notifications card in Settings.
The data card leads to the export and account-deletion screens. They work as on the web,
and both ask for your password.

### App lock

Under Security, **Require unlock** makes Trove lock when it opens and after it has been in
the background for the time you pick (immediately, or a number of minutes). Unlocking uses
Face ID, Touch ID or fingerprint, and falls back to the device passcode. A phone with
neither stays locked and shows "Set a device passcode or biometrics to use the app lock."
If Trove cannot be reached while unlocking (no connection, a timeout or a server error), it
stays locked and shows "Could not reach Trove. Check your connection and try again." Try
again once you are back online. If the server no longer accepts your session, Trove takes you
to the sign-in screen. If the phone holds no saved session, the lock screen shows "Your
session expired. Sign in again."
While Trove is in the app switcher, its content is hidden.
