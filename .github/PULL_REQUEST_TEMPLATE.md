## Summary

<!-- What does this PR do? Why? 1-3 bullet points. -->

-
-

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Bank source
- [ ] AI (coach / categorisation / vision)
- [ ] Tax export
- [ ] Documentation
- [ ] Dependency update
- [ ] Refactor / chore

## Test plan

- [ ] Unit tests added / updated
- [ ] The full gate passes (`./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage`)
- [ ] Bank parser fixtures pass (if bank source change)
- [ ] Receipt pipeline tests pass (if AI/vision change)
- [ ] No secrets or credentials in code or test fixtures

## Checklist

- [ ] Follows [Conventional Commits](https://www.conventionalcommits.org/) format
- [ ] No hardcoded API keys, tokens, or passwords
- [ ] New AI call sites reviewed for outbound-URL policy (`finance.trove.common.net.OutboundUrlPolicy`)
- [ ] Coverage gate passes (80% line coverage across the core bundle, JaCoCo, `-Pcoverage`)
- [ ] Docs updated for any behaviour, endpoint, env var, command or default change (README, `docs-site/`, `deploy/README.md`, `openapi/trove-core.yaml`), or this PR changes none
