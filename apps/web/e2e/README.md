# Trove web e2e specs

Playwright specs that exercise the web app against a real running `core` + web
stack. There is no mock backend here — bring up an actual deployment first.

## Bring up a stack

Any real Trove deployment works. For a disposable local one, see
[docs-site/self-hosting/docker-compose.md](../../../docs-site/self-hosting/docker-compose.md)
or `scripts/load-stack.sh up <workdir>` (a throwaway stack on `https://trove.test:18443`
by default, self-signed TLS).

## Run

```bash
cd apps/web
npm run test:e2e
```

Sign in as the seeded admin on a `load-stack.sh` stack: `TROVE_E2E_EMAIL=admin@trove.test`, and `TROVE_E2E_PASSWORD` from `TROVE_ADMIN_PASSWORD` in `<workdir>/.env`. Point `TROVE_E2E_BASE` and `E2E_BASE_URL` at `https://trove.test:18443`. Make `trove.test` resolve to 127.0.0.1 first, for example with an `/etc/hosts` entry. Run `npm run e2e:install` once to install Chromium.

Environment variables. `auth-flow`, `onboarding-to-today` and `consecutive-writes` read `TROVE_E2E_BASE` directly; `smoke` reads `E2E_BASE_URL` through `playwright.config.ts`.

| Variable | Default | Purpose |
|---|---|---|
| `TROVE_E2E_BASE` | `http://localhost:5173` | Base URL of the running stack. Point it at the stack you actually brought up, e.g. `https://trove.test:18443`. |
| `E2E_BASE_URL` | `http://localhost:5173` | Base URL used by `smoke.spec.ts` via `playwright.config.ts`; set it to the same value as `TROVE_E2E_BASE`. |
| `TROVE_E2E_EMAIL` | `test@yopmail.com` | Login used by `onboarding-to-today`, `smoke` and `consecutive-writes`. |
| `TROVE_E2E_PASSWORD` | _(none)_ | Password for the login above; those specs skip themselves if it is unset. |

## Notes

- `onboarding-to-today.spec.ts` signs in with the local account, completes the three ungated onboarding steps and checks that a reload mid-onboarding lands on Today (onboardingCompleted is written at the end of step 1). It needs a seeded user that has not finished onboarding and it changes that user's server-side state. No external service or token is involved.
- `consecutive-writes.spec.ts` and `smoke.spec.ts` also need `TROVE_E2E_PASSWORD` and skip without it.
- `auth-flow.spec.ts` only needs `TROVE_E2E_BASE`. It does not need a password.
