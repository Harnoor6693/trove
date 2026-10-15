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

Environment variables (read directly by the spec files, not by
`playwright.config.ts`'s own `E2E_BASE_URL` default — override explicitly):

| Variable | Default | Purpose |
|---|---|---|
| `TROVE_E2E_BASE` | `http://localhost:5173` | Base URL of the running stack. Point this at whatever you actually brought up, e.g. `https://trove.test:18443`. |
| `TROVE_E2E_EMAIL` | `test@yopmail.com` | Login used by `onboarding-to-today.spec.ts`. |
| `TROVE_E2E_PASSWORD` | _(none)_ | Required for `onboarding-to-today.spec.ts` — it skips itself if unset. |

## Notes

- `onboarding-to-today.spec.ts` uses a real public SimpleFIN demo token from
  `https://beta-bridge.simplefin.org/info/developers` (a fresh token per page
  visit, scraped inline by the spec) to exercise a real account-linking flow.
  SimpleFIN's free tier caps this at ~24 requests/day per access URL — don't
  loop the spec more than a handful of times against the same one.
- `auth-flow.spec.ts` only needs `TROVE_E2E_BASE`.
