# Authentication

`core` issues its own HS256 JWTs against local, Argon2id-hashed password accounts by
default. OIDC to an external provider is optional and all-or-nothing — see
[Architecture: Authentication](../guide/architecture#authentication). See the
[API overview](./) for the two auth carriers (bearer vs. cookie) and CSRF rules.

## Endpoints (tags: Authentication, Account security, Me)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/auth/register` | Redeem an invite (joins the inviter's household with the invited role) or, when an admin has opened registration, join the install's household as a member. Never creates a household. 201 with the same body as login; 429 `account-locked` is possible. |
| `POST` | `/v1/auth/login` | Email + password sign-in. Failed attempts are throttled, see below. |
| `POST` | `/v1/auth/login/totp` | Complete sign-in when TOTP is enabled. |
| `POST` | `/v1/auth/refresh` | Rotate a refresh token (single-use). |
| `POST` | `/v1/auth/logout` | Revoke the current session. |
| `GET` | `/v1/auth/providers` | List configured sign-in methods (local, OIDC if enabled), the OIDC display name and `registrationOpen`. |
| `GET` | `/v1/auth/invites/{token}` | Look up a pending household invite. |
| `POST` | `/v1/auth/password` | Change your password (current password required; your other sessions are signed out). |
| `POST` | `/v1/auth/password/reset-request` | `ADMIN` only: mint a one-hour single-use reset link for a member, to hand over out of band. No email is sent. |
| `POST` | `/v1/auth/password/reset` | Public: redeem the link with a new password. The token is the whole authorisation. |
| `POST` | `/v1/auth/totp/setup` | Start TOTP enrolment (current password required); returns the secret without enabling it. |
| `POST` | `/v1/auth/totp/confirm` | Enable TOTP with one live code; returns ten recovery codes. |
| `POST` | `/v1/auth/totp/disable` | Remove TOTP (password and a current code required). |
| `DELETE` | `/v1/auth/totp` | Same as `/totp/disable`, for clients that keep DELETE bodies. |
| `GET` | `/v1/auth/me` | The caller's own account. |
| `GET` | `/v1/auth/start` | Public OIDC sign-in entry: 302 to the authorisation endpoint, 404 when SSO is off. |
| `PUT` | `/v1/me` | Update your profile. |
| `GET`/`PUT` | `/v1/me/preferences` | Read / patch timezone, locale, onboarding flag and region. |
| `GET` | `/v1/me/security-events` | Your own recent sign-in and security activity. |
| `DELETE` | `/v1/me`, `POST /v1/me/delete` | Delete your own account behind a step-up body (password, plus `code` when TOTP is on). 403 `step-up-required` on a wrong factor; 409 for the household's last administrator. Soft delete with a seven-day restore window. |

Full request/response schemas are in
[`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml).


## First login on a fresh install

Registration is closed by default (`TROVE_REGISTRATION_OPEN=false`). The first household
and its admin are created once, automatically, on a boot where no users exist yet, from
`TROVE_ADMIN_EMAIL` / `TROVE_ADMIN_PASSWORD` / `TROVE_HOUSEHOLD_NAME` / `TROVE_ADMIN_COUNTRY`
(`CA` or `US`, default `CA`; it also sets the admin's currency, and neither can change later) —
see `finance.trove.app.bootstrap.FirstRunBootstrap`. After that, the admin invites other
members from the app; there is no open sign-up.
`TROVE_REGISTRATION_OPEN` only seeds the first boot. After that an administrator opens or
closes registration in Settings (`PUT /v1/admin/settings`), and the choice is stored on
the household.

## Household administration (tag: Administration)

Every route here needs the `ADMIN` role and acts only on the caller's own household; a member
of another household is 404.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/admin/users` | List the household's members, inactive ones included. |
| `DELETE` | `/v1/admin/users/{id}` | Remove a member (soft delete, seven-day restore window). |
| `POST` | `/v1/admin/users/{id}/restore` | Bring a removed member back inside the window. |
| `PUT` | `/v1/admin/users/{id}/role` | Make a member an `ADMIN`, or a `MEMBER` again. |
| `POST` | `/v1/admin/users/{id}/unlock` | End every sign-in throttle on a member's account (not your own). |
| `DELETE` | `/v1/admin/users/{id}/totp` | Remove a member's second factor, e.g. a lost phone (not your own; use `DELETE /v1/auth/totp` for that). |
| `GET`/`POST` | `/v1/admin/invites` | List or create invite links. The create response (201) carries the only readable copy of the token. |
| `DELETE` | `/v1/admin/invites/{id}` | Withdraw an unredeemed invite. |
| `GET`/`PUT` | `/v1/admin/settings` | Open registration, scheduled bank sync. |
| `GET` | `/v1/audit` | Paged audit log of mutating requests (`ADMIN` only; kept 365 days). |

**The household always keeps at least one active administrator.** Removing or demoting the
last one is 409, so the last administrator who wants to leave first makes another member an
administrator with `PUT /v1/admin/users/{id}/role`. A demotion signs the member out everywhere,
so the old role cannot outlive the decision in a live token; a promotion reaches the member's
token at its next refresh.

## Sessions and step-up

- The access token lives 15 minutes. A refresh token lasts 24 hours, or 12 hours idle, and is
  single-use with a 30 second reuse grace, so two tabs racing a refresh are not treated as
  theft. Real reuse is detected and logged.
- After a password login for a TOTP account, the challenge is valid for 60 seconds. TOTP is
  six digits, 30 second period, with ten recovery codes.
- Data export and account deletion need a step-up body: `password`, plus `code` where TOTP
  is on. Each request must carry its own step-up factor; nothing is remembered between
  requests. A missing or wrong factor is 403 `step-up-required`. TOTP setup needs the
  password; TOTP disable needs the password and a current code.
- Passwords are 12 to 128 characters.

## Sign-in throttling

Five failed sign-ins for an account from one address start a backoff of 30 seconds that
doubles per further failure, up to 15 minutes. An account that collects 100 failures in an
hour also refuses addresses it has never signed in from. These refusals look like a wrong
password (401 `credentials-invalid`) on purpose. Separately, one address with 20 failures in
10 minutes (or no resolvable address) gets 429 `account-locked` with `Retry-After`; this
applies to login, login/totp and register. A wrong TOTP code counts as a failure. An admin
can end the account-level throttles with `POST /v1/admin/users/{id}/unlock`; the
per-address 429 clears itself after the `Retry-After` wait.
