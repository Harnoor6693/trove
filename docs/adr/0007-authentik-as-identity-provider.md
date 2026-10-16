# ADR-0007: Authentik as External Identity Provider (SSO/OIDC)

**Date**: 2026-04-27  
Status: Superseded by [ADR-0020](0020-monolith-collapse.md) (monolith collapse, 2026-09-26 — Authentik was cut outright; local Argon2id password accounts are the default, with optional OIDC to any provider the operator chooses, see `finance.trove.auth.oidc`)  
**Deciders**: Security and product team

## Context

Trove initially implemented in-app password authentication (users create username/password credentials, hashed with bcrypt). This approach works for small deployments but creates operational burden: password reset flows, account lockout logic, compliance with password strength policies, etc. As Trove scales and opens to enterprise customers, users want to authenticate via their existing corporate identity (Active Directory, Google Workspace, Okta). The solution is to delegate authentication to an external identity provider (IdP) via industry-standard OIDC (OpenID Connect) protocol.

## Decision

Use Authentik, an open-source identity provider that implements OIDC, SAML, and OAuth. Deploy Authentik as a separate service and configure the api-gateway to redirect unauthenticated requests to Authentik's login page. After authentication, Authentik issues a JWT token that the api-gateway validates. Authentik connects to corporate identity systems (LDAP, Active Directory) or social logins (Google, GitHub). Trove's application logic treats the Authentik-issued JWT as the source of truth for user identity (no need to change any downstream services).

## Alternatives Considered

### Alternative 1: Keep In-App Password Authentication
- **Pros**: Full control over authentication logic; no external service dependency
- **Cons**: Operational burden (password resets, lockouts, compliance); users cannot use existing corporate credentials
- **Why not**: Does not meet enterprise authentication requirements; scales poorly with user base

### Alternative 2: Okta or Auth0 (Hosted Service)
- **Pros**: No infrastructure to maintain; world-class security and compliance
- **Cons**: Vendor lock-in; monthly per-user costs; requires third-party trust
- **Why not**: Authentik provides equivalent OIDC support with self-hosted option; no licensing costs

### Alternative 3: Custom OIDC Gateway Written in-House
- **Pros**: Full control over behavior; no external dependency
- **Cons**: Requires deep OIDC expertise; security-critical code (easy to get wrong); high maintenance burden
- **Why not**: Authentik is battle-tested; writing OIDC from scratch is a security risk

## Consequences

### Positive
- Users can authenticate via corporate identity (Active Directory, Google, etc.)
- Enterprise SSO capabilities (single logout, session management)
- Offloads password management from Trove (Authentik handles resets, strength policies, MFA)
- OIDC is industry-standard; easy to integrate with other systems in the future
- Open-source; self-hosted; no vendor lock-in

### Negative
- Adds an operational dependency (Authentik cluster must be provisioned, patched, monitored)
- Requires Authentik expertise on the team
- Network latency to Authentik on every authentication (mitigated by caching JWT tokens)
- Trove's in-app authentication logic can be removed (code cleanup effort)

### Risks
- **Authentik unavailability**: If Authentik is down, users cannot log in. Mitigated by: (1) deploying Authentik with high availability (replication, load balancing), (2) setting long JWT expiration (users stay logged in longer), (3) failing open to previous authentication method temporarily
- **OIDC misconfiguration**: Incorrect JWT validation could allow token forgery. Mitigated by: (1) validating JWT signature against Authentik's public key, (2) verifying iss (issuer), aud (audience), exp (expiration) claims, (3) regular security audits
- **User synchronization**: Authentik's user database may drift from Trove's user records. Mitigated by: (1) treating Authentik as source of truth for authentication, (2) periodic reconciliation of user metadata
