# Contributing to Trove

Trove is one Spring Boot module (`core`) plus two clients (`apps/web`, `apps/mobile`),
behind one OpenAPI contract. See [docs-site/guide/architecture.md](docs-site/guide/architecture.md)
for the shape of the codebase and [docs-site/guide/local-development.md](docs-site/guide/local-development.md)
for getting it running.

## Quick start

```bash
git clone https://github.com/Harnoor6693/trove && cd trove
cp core/.env.example core/.env   # or export the same variables
./mvnw spring-boot:run -pl core -am
```

Full setup (Postgres, dev profile, seeding a first user) is in
[the local development guide](docs-site/guide/local-development.md).

## Spec before controller

`openapi/trove-core.yaml` is the contract. Add or change a route in the spec first, then
implement the controller. `OpenApiRouteDriftIT` and `OpenApiConformance` fail the build if
the running server's routes and response shapes do not match the spec — a controller
without a matching spec entry does not pass CI, and neither does a spec entry nothing
implements.

## The gate

Everything CI runs, run it locally before opening a PR:

```bash
./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage
```

This one command runs unit tests, integration tests (Testcontainers Postgres),
Checkstyle, and the JaCoCo coverage check together — `-Pintegration,coverage` merges IT
coverage into the same report the check reads, so running `verify` without both profiles
under-reports coverage and can pass locally while failing in CI.

- **Checkstyle** — config at `config/checkstyle/checkstyle.xml`; suppressions at
  `config/checkstyle/suppressions.xml`. Run alone: `./mvnw checkstyle:check -pl core`.
- **Coverage floor** — 80% line coverage (JaCoCo `BUNDLE`/`LINE`/`COVEREDRATIO`,
  `pom.xml`). The build fails below it; it does not fail above it, but do not add code
  that drags the bundle average down.
- **ArchUnit** — `SourceConventionTest` and friends ban hardcoded currency codes in Java
  source and unscoped repository finders. These run as part of the normal unit test
  phase, no separate command.

Client gates, run inside each app's own directory (never `npm` at the repo root — there
is no root `package.json`):

```bash
# apps/web
npm ci
npm run lint && npm run typecheck && npm run test:ci
npm run check:generated   # fails if generated API types drift from openapi/trove-core.yaml
npm run e2e               # Playwright, needs a running server

# apps/mobile
npm ci
npm run lint && npm run typecheck && npm run test:ci
npm run check:generated
npm run test:e2e:ios      # or :android — Detox, needs a built app + simulator/emulator
```

## Contribution types

| Type | Process |
|------|---------|
| Bug fix | Issue → fork → fix with a regression test → PR |
| New bank ingestion source | Issue tagged `bank-plugin` → implement under `core/src/main/java/finance/trove/ingestion/` → 10+ redacted fixtures → PR |
| Feature (small) | Issue → discussion → PR |
| Feature (spec-changing) | Update `openapi/trove-core.yaml` first, in its own commit → implementation |
| Documentation | Direct PR |

There is no runtime plugin registry to implement against (see
[ADR-0003](docs/adr/0003-plugin-architecture.md), superseded): a new bank source is a
package under `ingestion/`, reviewed and compiled in like any other code, not a
dynamically loaded module.

## Code standards

- **Java:** enforced by Checkstyle (`config/checkstyle/checkstyle.xml`), not a separate
  style guide.
- **TypeScript/React:** ESLint + Prettier, enforced by each app's own `npm run lint`.
- **Tests:** every new public method gets a test; the 80% line-coverage floor applies to
  the whole `core` bundle, not per file.
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/) —
  `feat:`, `fix:`, `docs:`, `test:`, `chore:`.
- **No secrets:** no API keys, tokens, or passwords in code, fixtures, or `.env.example`
  files — every `*.env.example` value is a real default or empty, never a
  plausible-looking fake secret (gitleaks runs in CI).
- **AI call sites:** `core/src/main/java/finance/trove/ai/` and the two packages that
  call into it (`receipts/pipeline`, `receipts/upload`, `categorisation/`) are
  CODEOWNERS-scoped for extra review — see `.github/CODEOWNERS`.

## Logging

One process, one `logback-spring.xml` (`core/src/main/resources/logback-spring.xml`).
Every request is tagged with a `requestId` MDC value (and `userId` once authenticated);
the same UUID appears in the `X-Request-Id` response header and the `audit_log` table.

| Level | Meaning | Examples |
|---|---|---|
| **ERROR** | Operator must look at this. | 5xx response, unrecoverable failure, a write that lost data |
| **WARN** | Degraded but recoverable. | 4xx response, a retried transient timeout |
| **INFO** | Expected event a human cares about. | Request completion, a user action that changed state |
| **DEBUG** | Useful while debugging; off in production. | Branch decisions, payload sizes, cache hit/miss |
| **TRACE** | Rarely useful. | Emergency debugging only |

Do not log secrets or PII directly — use `finance.trove.common.observability.Redact`:

```java
log.info("Webhook to {} delivered with token {}", url, Redact.token(secret));
log.info("Notification sent to {}", Redact.email(user.email()));
```

The encoder also masks `Bearer ...` headers, JWT-shaped strings, and 13–19-digit
card-number sequences as a last-resort defence, but the call-site `Redact.*` helper is
the primary one — do not rely on the encoder alone.

## Pull request checklist

- [ ] `./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage` passes
- [ ] Spec changed first, in its own commit, if this PR adds or changes a route
- [ ] No secrets in code, fixtures, or `.env.example` files
- [ ] Conventional commit message format
- [ ] Client gates pass for any `apps/web` or `apps/mobile` change

## Reporting security issues

Do **not** open a public GitHub issue for a vulnerability. Use the repository's private
vulnerability reporting instead: GitHub → **Security** → "Report a vulnerability". This
project follows a 90-day coordinated disclosure window.
