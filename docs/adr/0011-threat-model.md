# 11. Threat Model — OWASP Top 10 (A04 Insecure Design)

Date: 2026-05-05
Status: Accepted
Supersedes: —

> Updated in collapse step 25 (2026-09-26): the monolith collapse (ADR-0020) removed the
> service boundaries this table originally mapped mitigations onto. Threats and CI gates
> are unchanged; component references below now name the single `core` module's packages
> instead of the deleted per-domain services. See ADR-0020 for why there is one module,
> and the superseded ADR-0002/0006/0007/0008 for the specific mechanisms this table used
> to point at.

> Update 2026-10-16: (a) refresh tokens are signed JWTs hashed at rest in `finance.trove.auth.session`; rotation marks the row replaced and allows a short reuse grace window, and reuse beyond it revokes the whole token family. They are not deleted on rotation. (b) Both images run as a non-root user: `core/Dockerfile` as before, and the `apps/web` image is now `nginxinc/nginx-unprivileged` (`USER 101`, listening on 8080; `scripts/web-healthcheck-probe.sh` asserts uid is not 0). (c) CI gates npm dependencies with `scripts/npm-audit-gate.mjs` (high and critical, with an expiring allowlist), not a bare `npm audit --audit-level=high`. (d) Trove signs outbound webhooks (`webhook-signature`, HMAC-SHA256); it does not verify inbound webhooks, and signature rejection happens in the receiver.

> Update 2026-10-16: the SSRF row reads as if `TROVE_EGRESS_ALLOW_PRIVATE` lifts the link-local and metadata refusals. It does not. That flag together with a user's own `allowPrivateEndpoint` opt-in, on the per-user AI endpoint only, lifts the private ranges (RFC 1918, carrier-grade NAT, IPv6 unique-local and site-local, loopback), allows plain http, and accepts an IPv6 address in the deprecated IPv4-compatible (`::/96`) or the IPv4-mapped (`::ffff:0:0/96`) form (its IPv4 address is still judged), and nothing else. Link-local, the cloud metadata addresses, the unspecified, multicast and reserved ranges, and every IPv4 or IPv6 range IANA marks not globally reachable are always refused. SimpleFIN and webhooks always use the default policy (https, public addresses only) whatever the flag says. See `finance.trove.common.net.OutboundUrlPolicy`.

## Context

OWASP Top 10 A04 (Insecure Design) requires that an application has been
designed against an explicit threat model rather than retrofitted with controls.
Phase 29 of the v1.0 rebuild requires this ADR to exist as the reviewable
artefact for that criterion.

> Naming note: Stage 3 plan section 29.6 originally referenced
> `docs/adr/0010-threat-model.md`. ADR 0010 was already taken by
> `0010-aes-gcm-field-encryption.md`. This ADR is therefore filed as 0011 and
> the Phase 29 acceptance criterion is interpreted accordingly.

This document is non-exhaustive — it captures the threats Trove explicitly
designs against. Findings from active scanners (gitleaks, OWASP DC, CodeQL, Trivy)
extend this list dynamically through CI.

## Decision

We adopt the threat model below. Each threat is mapped to (a) the asset it
targets, (b) the attacker capability assumed, and (c) the Trove component(s)
that mitigate it. CI gates referenced are wired up in `.github/workflows/security.yml`.

### Authentication threats

| Threat | Mitigation | Trove component |
|---|---|---|
| Online password brute force | Per-IP and per-user rate limiting; account lockout on repeated failures | `finance.trove.app.ratelimit` (`RateLimitFilter`, `RateLimitService`), `finance.trove.auth.local` |
| JWT theft via XSS / log scraping / cache | Short-lived access tokens; signed refresh tokens hashed at rest server-side, rotated on use with reuse detection | `finance.trove.auth.session` |
| Credential stuffing | Rate limiter operates per `/api/v1/auth/*` route; Argon2id password hashing makes offline cracking of a leaked hash expensive | `finance.trove.auth.local`, `finance.trove.app.ratelimit` |
| JWT alg=none / alg confusion | Allowlisted symmetric HS256 with a shared secret of at least 32 bytes; secret presence and length enforced at startup, refuses the literal `change-me-...` placeholder | `finance.trove.auth.jwt` (`JwtProperties` constructor checks) |
| Refresh token replay | Refresh tokens rotate on use; reuse outside a short grace window revokes the whole token family | `finance.trove.auth.session` |
| Optional SSO misconfiguration | OIDC is all-or-nothing: issuer, client id, and client secret must all be set or the feature stays off; local accounts keep working either way | `finance.trove.auth.oidc` |

**Historical note (superseded 2026-09-26):** an earlier design (ADR-0002) shared one
HS256 secret across ten separate services, so a compromise of any one of them could
mint valid JWTs for any user. The monolith collapse (ADR-0020) removed that specific
risk by removing the separate services — there is one process holding the secret now.
RS256/JWKS migration is not pursued: with a single signer there is no downstream
service to distribute a public key to.

### Data threats

| Threat | Mitigation | Trove component |
|---|---|---|
| PII at rest leakage (database dump) | Column-level AES-GCM encryption keyed by `TROVE_ENCRYPTION_KEY` (ADR 0010) | `finance.trove.common.crypto` |
| PII in transit | TLS termination at Traefik (`deploy/traefik/`); the browser-to-server hop is the only network hop in the shipped deployment | `deploy/docker-compose.yml` |
| PII in logs | Structured logs with field-level redaction; `finance.trove.common.observability.Redact` masks emails, tokens, and card-shaped digit runs at the call site, with encoder-level masking as a backstop | `logback-spring.xml`, `Redact` |
| Receipt object exposure | Receipts are stored on local disk under `TROVE_DATA_DIR`, inside the deployment's own volume, not a public object store; access is gated by the authenticated household's own ownership check, not a pre-signed URL | `finance.trove.receipts` |
| Cross-tenant data leak | Every query on household-owned data filters on the household id derived from the authenticated session; integration test `AccessControlIT` | `finance.trove.households.AccessControlIT`, repository layer across domain packages |
| SQL injection | JPA / Spring Data parameterised queries only; no native SQL string concatenation; static-checked by CodeQL | All `*Repository` interfaces |

### Infrastructure threats

| Threat | Mitigation | Trove component |
|---|---|---|
| SSRF via outbound calls (webhooks, per-user AI endpoint, SimpleFIN) | URL allowlist rejects RFC 1918 / link-local / metadata IPs before HTTP dispatch, unless the operator opts a specific install into `TROVE_EGRESS_ALLOW_PRIVATE` for a LAN AI endpoint | `finance.trove.common.net.OutboundUrlPolicy` |
| Webhook event forgery | HMAC-SHA-256 signature on every outbound webhook; the receiver verifies the signature | `finance.trove.notifications` |
| Container escape | Non-root user (core and web images), minimal base image, no `--privileged` flags | `core/Dockerfile`, `apps/web/Dockerfile`, `deploy/docker-compose.yml` |
| Vulnerable third-party dependency | OWASP Dependency-Check (Java) and the npm audit gate (`scripts/npm-audit-gate.mjs`, high/critical, web + mobile) gate CI; Trivy scans the published core image | `.github/workflows/security.yml` (`owasp-dependency-check`, `dependency-audit`, `trivy-scan`) |
| Secrets in source / git history | gitleaks gate on every PR with an explicit allowlist of placeholder values | `.gitleaks.toml`, `.github/workflows/security.yml` (`secret-scan`) |
| Rate-limit bypass | In-process counter keyed by client IP and auth subject; a single instance is the only deployment shape this ships, so there is no cross-instance counter to keep consistent | `finance.trove.app.ratelimit` |

## Consequences

### Positive

* Auditors and security reviewers have a single artefact mapping every
  OWASP Top 10 category to a concrete Trove component or test.
* The monolith collapse removed an entire class of risk (shared-secret blast radius
  across services, inter-service network trust) without adding a replacement risk of
  equal size, since there is nothing left to be "between".

### Negative

* Threat models drift from reality if not revisited. We commit to reviewing
  this ADR every minor release, and whenever a new class of integration is added
  (for example a new external webhook source, or a second deployment shape).
* This ADR was allowed to drift for five collapse steps before being corrected here —
  the same drift risk applies to any doc describing the deleted architecture. Treat every
  doc in this repository except `docs/recovery/` as suspect if its last edit predates
  the collapse.
* Some mitigations (log redaction, the SSRF allowlist) are runtime controls; a coding
  mistake in a single call site can defeat the design. There is no external penetration
  test of this codebase — see the README's Security section.

## References

* OWASP Top 10 (2021): https://owasp.org/Top10/
* ADR 0002 — JWT shared secret (superseded)
* ADR 0006 — ValKey for rate limiting (superseded)
* ADR 0007 — Authentik as identity provider (superseded)
* ADR 0008 — MinIO for report storage (superseded)
* ADR 0009 — Deduplication strategy
* ADR 0010 — AES-GCM field encryption
* ADR 0020 — Monolith collapse
* `core/src/test/java/finance/trove/households/AccessControlIT.java`
* `.github/workflows/security.yml`
