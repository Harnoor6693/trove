# Contributing

Thanks for your interest in contributing to Trove. The full contribution policy lives in
[`CONTRIBUTING.md`](https://github.com/Harnoor6693/trove/blob/canary/CONTRIBUTING.md) at
the repo root.

## Quick links

- **Code of Conduct:** [CODE_OF_CONDUCT.md](https://github.com/Harnoor6693/trove/blob/canary/CODE_OF_CONDUCT.md) (if present in your fork)
- **License:** [AGPL-3.0](https://github.com/Harnoor6693/trove/blob/canary/LICENSE)
- **Issue tracker:** [github.com/Harnoor6693/trove/issues](https://github.com/Harnoor6693/trove/issues)

## Development workflow

1. Fork and clone the repo.
2. Follow the [local development guide](/guide/local-development) to run `core` and the
   clients from source.
3. Create a topic branch (`feat/...`, `fix/...`, `docs/...`).
4. Add tests for what you change; CI enforces an 80% line-coverage floor on `core`.
5. Run the gate locally before opening a PR:
   ```bash
   ./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage
   ```
6. Open a pull request; CI runs the same gate plus Checkstyle, dependency and secret
   scanning, and (for client changes) the web/mobile lint, typecheck, and test suites.

## Reporting security issues

Please do **not** file public GitHub issues for security problems. Email the maintainers
or use GitHub's private vulnerability reporting flow.
