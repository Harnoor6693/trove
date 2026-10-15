# ADR-0002: JWT Signing with Shared Symmetric Secret (HMAC-SHA256) over Asymmetric Keys

**Date**: 2026-04-27  
Status: Superseded by ADR-0020 (monolith collapse, 2026-09-26 — the shared-secret-across-services risk this ADR accepted no longer applies once there is one process holding the secret; see ADR-0011 threat model)  
**Deciders**: Security team

## Context

Trove uses JWT tokens for stateless authentication across microservices. The system requires that only the api-gateway can mint new tokens, while all downstream services validate tokens independently using a shared secret. A shared secret means all services must have access to the same cryptographic material, creating key distribution challenges. Asymmetric key signing (RS256) requires downstream services to have only the public key, eliminating the key distribution problem. However, asymmetric signing adds computational overhead and complexity.

## Decision

Use HMAC-SHA256 (symmetric shared secret) for JWT signing. The secret is a 32+ byte random string provided via environment variable or Spring property. Every service receives this secret during deployment and uses it to validate JWTs. Only the api-gateway (JwtIssuerService) calls `Jwts.builder()` to mint tokens. A compromised downstream service cannot forge tokens for other users because it cannot generate valid signatures with the shared secret unless the secret is also compromised.

## Alternatives Considered

### Alternative 1: RSA (RS256) or ECDSA (ES256) Asymmetric Signing
- **Pros**: Only gateway holds private key; downstream services need only public key; public key is non-sensitive
- **Cons**: 2-4x slower signing/validation; requires key rotation infrastructure; adds operational complexity
- **Why not**: Trove's deployment is containerized and controlled; key distribution via environment variables is acceptable; performance matters for high-frequency validations

### Alternative 2: Asymmetric Signing + Public Key Distribution Service
- **Pros**: Solves key rotation; enables safe public key distribution
- **Cons**: Adds a new service dependency; requires service-to-service authentication to fetch keys; introduces race conditions during rotation
- **Why not**: Over-engineered for Trove's threat model; not justified by current scale

### Alternative 3: mTLS (Mutual TLS) instead of JWT
- **Pros**: Authenticates both client and server; works at transport layer
- **Cons**: Requires certificate management infrastructure; all services must hold certificates; harder to debug than JWT claims
- **Why not**: JWT claims carry user identity and roles, making authorization decisions transparent; mTLS is service-to-service only

## Consequences

### Positive
- Simple to implement; no additional dependencies beyond JJWT library
- Symmetric signing/validation is computationally cheaper than asymmetric algorithms
- Secret distribution via environment variables fits Kubernetes/Docker deployment model
- Can rotate secret by redeploying all services with new environment variable
- Compact token size

### Negative
- Every service must hold the secret, increasing the blast radius if any service is compromised
- Secret rotation requires coordinated redeployment of all services (no graceful rolling rotation)
- Cannot revoke a compromised secret from one service while keeping others running
- Secret must be treated as highly confidential during transit and storage

### Risks
- **Secret exposure**: If any service's environment is compromised, the entire JWT system is compromised. Mitigated by: strict access controls on deployment pipelines, audit logs of who accessed secrets, regular secret rotation and access reviews
- **No key versioning**: Cannot sign with one key and validate with another during rotation. Mitigated by deploying all services with new secret simultaneously and setting short token expiration (5-15 minutes)
- **Symmetric secret in memory**: JVM may not reliably erase the secret bytes from heap. Mitigated by regular key rotation and considering hardware security modules for future scaling
