# ADR-0020: Collapse the Microservice Split into One Spring Boot Module

**Date**: 2026-09-26
Status: Accepted
**Deciders**: Maintainer (@Harnoor6693)
**Supersedes**: ADR-0001, ADR-0002 (in part: the shared-across-services secret risk), ADR-0003, ADR-0006, ADR-0007, ADR-0008

## Context

ADR-0001 (2026-04-27) split Trove into ten Spring Boot services plus supporting
infrastructure (Authentik for identity, ValKey for rate limiting, MinIO for object
storage) on the theory that independent domains needed independent deployment and
scaling. In practice the project was abandoned mid-integration: services were built to
varying degrees of completeness, the seams between them (shared auth, cross-service
transactions, a consistent local dev story) were never finished, and the resulting
system could not be stood up as a whole. See `docs/recovery/` for the verified account
of what was and was not working when rebuild began.

Trove runs for one household, self-hosted, on hardware an individual owns — not a
fleet of tenants needing independent scaling per domain. The operational cost of ten
services (ten sets of health checks, ten log streams, a shared-secret JWT design whose
blast radius was every one of them, a rate limiter and an identity provider that only
existed to coordinate across them) bought nothing a single household's install needed,
and was the direct cause of the system never having worked end to end.

## Decision

Collapse every service into one Spring Boot module, `core`, organised as 17 packages
under `finance.trove` (one per domain: accounts, ai, app, auth, budgets, categorisation,
common, dataexport, households, ingestion, networth, notifications, receipts,
subscriptions, taxexport, transactions, users). One PostgreSQL database, one Flyway
migration history. One deployment, under `deploy/`, running `core` behind Traefik.

Direct consequences, each recorded in its own superseded ADR:

- **No API gateway.** `core` serves `/api/v1/*` itself. There is nothing between the
  edge (Traefik) and the application to route to.
- **No shared-secret blast radius across services** (ADR-0002 superseded). One process
  holds the JWT signing secret.
- **No external identity provider** (ADR-0007 superseded). Local Argon2id password
  accounts by default; OIDC to any provider the operator configures is optional and
  all-or-nothing.
- **No distributed rate-limit store** (ADR-0006 superseded). A single instance has no
  second instance to keep a counter consistent with, so the limiter is in-process.
- **No object store** (ADR-0008 superseded). Receipts and exports are files under
  `TROVE_DATA_DIR`, inside the deployment's own Docker volume.
- **No runtime plugin registry** (ADR-0003 superseded). Bank ingestion sources are
  packages under `ingestion/`, added by a PR like any other code.

## Alternatives Considered

- **Finish the microservice build instead of collapsing it.** Rejected: the missing
  seams (auth propagation, cross-service transactions, a working local/dev compose)
  were exactly the work that had been attempted and abandoned twice already, per the
  recovery record. Nothing about attempting it a third time changes the reason it
  failed the first two.
- **Collapse to two or three modules along a different split (e.g. "money" vs.
  "identity" vs. "AI").** Rejected as the "rename-everything" restructure — considered
  and passed over in favour of the simpler cleanup ("option A") that keeps one
  compilation unit and one deployable artifact; (agreed 2026-09-25).
- **Keep Kubernetes/Kustomize as a self-hosting option** (`docs-site/self-hosting/kubernetes.md`,
  now deleted). Rejected: a single-instance monolith has no scaling dimension Kubernetes
  addresses that Docker Compose does not, and maintaining two deployment paths for one
  supported topology is exactly the kind of duplicate surface this collapse removes.

## Consequences

### Positive

- One thing to build, test, and deploy. The full gate is one Maven command
  (`./mvnw --batch-mode -pl core -am clean verify checkstyle:check -Pintegration,coverage`).
- One OpenAPI contract, conformance-tested against the one running server — no
  cross-service contract to keep in sync.
- The threat model (ADR-0011) shrank: an entire class of inter-service trust and
  shared-secret risk no longer exists, because there is no "inter-service" left.

### Negative

- No independent scaling per domain. If AI categorisation load ever needed to scale
  separately from transaction ingestion, that is a future re-split, not a configuration
  change today. Not a concern at the scale this ships for (one household).
- Every doc written against the ten-service architecture became wrong the moment this
  landed. Collapse step 25 is the housekeeping pass that finds and corrects them; ADRs
  0001, 0002, 0003, 0006, 0007, 0008 are marked superseded rather than deleted, so the
  historical reasoning stays available to whoever asks "why not microservices" next.

## References

- `docs/recovery/` — the verified account of the pre-collapse system and the collapse
  plan (`06-COLLAPSE-PLAN.md`) that this ADR closes out.
- ADR-0001, 0002, 0003, 0006, 0007, 0008 — superseded by this decision.
- ADR-0011 — threat model, updated for the single-module architecture.
