# Trove rebuild record

> **Historical record.** These files were written between 2026-09-03 and 2026-09-28 during the rebuild. They describe the pre-rebuild ten-service code and the plan and progress of replacing it; they are not kept up to date. The current architecture is in `docs-site/guide/architecture.md`.

Design and verification documents from the rebuild of Trove into the `trove-core`
monolith. Source comments and ADRs cite these files by name, so they are kept here. The
rebuild's session logs and tooling are kept in a private archive and are not published.

| File | What it is |
|---|---|
| [`01-FINDINGS.md`](01-FINDINGS.md) | Verified findings dossier on the pre-rebuild codebase, by area, with file:line. Written 2026-09-03. |
| [`04-REVIEWS-AND-SCOPE.md`](04-REVIEWS-AND-SCOPE.md) | Senior reviews and the recommended scope cut. Written 2026-09-03. |
| [`06-COLLAPSE-PLAN.md`](06-COLLAPSE-PLAN.md) | Monolith collapse plan (`trove-core`). Written 2026-09-03. |
| [`06-PROGRESS.md`](06-PROGRESS.md) | Progress log of the rebuild steps. Written 2026-09-03 to 2026-09-28. |
| [`07-READINESS-GAPS.md`](07-READINESS-GAPS.md) | Production-readiness gaps and the design decisions taken for them. Written 2026-09-03, with dated notes to 2026-09-27. |
| [`23-LOAD-AND-SECURITY.md`](23-LOAD-AND-SECURITY.md) | Load and security verification of the whole stack. Written 2026-09-25. |
| [`inventory/`](inventory/) | Per-component inventories of the pre-rebuild code (2026-09-03), verified against source at that time. |
