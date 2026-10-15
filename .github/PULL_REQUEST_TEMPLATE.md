## Summary

<!-- What does this PR do? Why? 1-3 bullet points. -->

-
-

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Bank source
- [ ] AI / model routing
- [ ] Tax export
- [ ] Documentation
- [ ] Dependency update
- [ ] Refactor / chore

## Test plan

- [ ] Unit tests added / updated
- [ ] Integration tests pass (`./mvnw -pl core -am verify -Pintegration`)
- [ ] Bank parser fixtures pass (if bank source change)
- [ ] Receipt bench accuracy ≥ 85% (if AI/vision change)
- [ ] No secrets or credentials in code or test fixtures

## Checklist

- [ ] Follows [Conventional Commits](https://www.conventionalcommits.org/) format
- [ ] No hardcoded API keys, tokens, or passwords
- [ ] New AI call sites reviewed for outbound-URL policy (`finance.trove.common.net.OutboundUrlPolicy`)
- [ ] 80%+ line coverage on modified files (JaCoCo gate, `-Pcoverage`)
