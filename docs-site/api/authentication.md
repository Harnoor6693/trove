# Authentication

`core` issues its own HS256 JWTs against local, Argon2id-hashed password accounts by
default. OIDC to an external provider is optional and all-or-nothing — see
[Architecture: Authentication](../guide/architecture#authentication). See the
[API overview](./) for the two auth carriers (bearer vs. cookie) and CSRF rules.

## Endpoints (tags: Authentication, Account security, Me)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/auth/register` | Create the first household or accept an invite. |
| `POST` | `/v1/auth/login` | Email + password sign-in. |
| `POST` | `/v1/auth/login/totp` | Complete sign-in when TOTP is enabled. |
| `POST` | `/v1/auth/refresh` | Rotate a refresh token (single-use). |
| `POST` | `/v1/auth/logout` | Revoke the current session. |
| `GET` | `/v1/auth/providers` | List configured sign-in methods (local, OIDC if enabled). |
| `GET` | `/v1/auth/invites/{token}` | Look up a pending household invite. |
| `POST` | `/v1/auth/password`, `/v1/auth/password/reset-request`, `/v1/auth/password/reset` | Change or reset a password. |
| `POST` | `/v1/auth/totp/setup`, `/confirm`, `/disable` | Enroll or remove TOTP two-factor auth. |
| `GET` | `/v1/auth/me` | The caller's own account. |

Full request/response schemas are in
[`openapi/trove-core.yaml`](https://github.com/Harnoor6693/trove/blob/canary/openapi/trove-core.yaml)
or a running instance's `/swagger-ui/index.html`.

## First login on a fresh install

Registration is closed by default (`TROVE_REGISTRATION_OPEN=false`). The first household
and its admin are created once, automatically, on a boot where no users exist yet, from
`TROVE_ADMIN_EMAIL` / `TROVE_ADMIN_PASSWORD` / `TROVE_HOUSEHOLD_NAME` / `TROVE_ADMIN_COUNTRY`
(`CA` or `US`, default `CA`; it also sets the admin's currency, and neither can change later) —
see `finance.trove.app.bootstrap.FirstRunBootstrap`. After that, the admin invites other
members from the app; there is no open sign-up.

## Household administration (tag: Administration)

Every route here needs the `ADMIN` role and acts only on the caller's own household; a member
of another household is 404.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/admin/users` | List the household's members, inactive ones included. |
| `DELETE` | `/v1/admin/users/{id}` | Remove a member (soft delete, seven-day restore window). |
| `POST` | `/v1/admin/users/{id}/restore` | Bring a removed member back inside the window. |
| `PUT` | `/v1/admin/users/{id}/role` | Make a member an `ADMIN`, or a `MEMBER` again. |
| `POST` | `/v1/admin/users/{id}/unlock` | Clear a member's sign-in lock. |
| `DELETE` | `/v1/admin/users/{id}/totp` | Clear a member's second factor. |
| `GET`/`POST` | `/v1/admin/invites` | List or create invite links. |
| `GET`/`PUT` | `/v1/admin/settings` | Open registration, scheduled bank sync. |

**The household always keeps at least one active administrator.** Removing or demoting the
last one is 409, so the last administrator who wants to leave first makes another member an
administrator with `PUT /v1/admin/users/{id}/role`. A demotion signs the member out everywhere,
so the old role cannot outlive the decision in a live token; a promotion reaches the member's
token at its next refresh.
