# ADR-0006: ValKey (Redis Clone) for Distributed Rate Limiting

**Date**: 2026-04-27  
Status: Superseded by [ADR-0020](0020-monolith-collapse.md) (monolith collapse, 2026-09-26 — a single `core` instance has no cross-instance counter to keep consistent, so rate limiting moved to an in-process counter; see `finance.trove.app.ratelimit`)  
**Deciders**: Infrastructure and backend team

## Context

Trove requires rate limiting on the api-gateway to prevent abuse and ensure fair resource allocation. Rate limits must be applied per-user (authenticated requests) and per-IP (anonymous requests), and must work correctly in a distributed deployment where multiple gateway instances run concurrently. In-memory rate limiting (e.g., ConcurrentHashMap with counters) doesn't work because each gateway instance would have its own counter, and a user could bypass limits by distributing requests across instances. A distributed data store (Redis or ValKey) is required to maintain a single authoritative counter across all gateway instances.

## Decision

Use ValKey (an open-source Redis clone maintained by the Linux Foundation) as the rate-limit storage backend. The `RateLimitFilter` implements a Token Bucket algorithm: for each request, check if a key exists in ValKey (`rate_limit:{user_id or ip}:{endpoint}`), decrement the token count, and check if it's below zero. On startup, initialize the token bucket with a per-endpoint limit (e.g., 100 requests per minute). If ValKey is unavailable, fail open (allow request to proceed) to maintain system availability over strict rate limit enforcement.

## Alternatives Considered

### Alternative 1: In-Memory Rate Limiting (Per-Instance)
- **Pros**: No external dependency; instant lookups; simple to implement
- **Cons**: Does not work in distributed deployments; users can bypass limits across instances
- **Why not**: Trove runs multiple gateway instances behind a load balancer; per-instance limiting is insufficient

### Alternative 2: Redis (Commercial/Proprietary)
- **Pros**: More mature than ValKey; wider ecosystem support; commercial support available
- **Cons**: Commercial licensing; not part of Linux Foundation ecosystem
- **Why not**: ValKey provides equivalent functionality with open-source community support; no lock-in

### Alternative 3: Database-Backed Rate Limiting
- **Pros**: No additional service dependency; persistent across restarts
- **Cons**: Slow (database write on every request); contention on rate-limit rows; not designed for high-frequency updates
- **Why not**: Database is not optimized for rate limiting; ValKey is purpose-built for this use case

## Consequences

### Positive
- Distributed rate limiting works correctly across multiple gateway instances
- Token Bucket algorithm is fair and predictable (burst capacity built in)
- ValKey is fast (in-memory operations, typically < 1ms latency per request)
- Open-source and community-supported
- Can easily export rate-limit metrics for monitoring
- Fail-open behavior ensures system availability if ValKey is down

### Negative
- Adds an operational dependency (ValKey cluster must be provisioned and monitored)
- Requires ValKey expertise on the team (data structure knowledge, cluster management)
- Rate-limit state is lost if ValKey cluster is restarted (tokens reset)
- Network latency to ValKey cluster adds to request latency (though negligible in practice)

### Risks
- **ValKey unavailability**: If ValKey is down, rate limiting fails open (all requests allowed). Mitigated by: (1) deploying ValKey cluster with high availability (replication, failover), (2) monitoring ValKey health and alerting on outages, (3) running local circuit breaker to fail open gracefully
- **Rate-limit bypass on cluster split**: In a split-brain scenario (network partition between gateway and ValKey), each side maintains independent counters. Mitigated by using quorum-based consensus (Redis cluster mode) and only accepting writes from the majority partition
- **Token bucket refill**: Tokens may refill slowly on low-traffic endpoints. Mitigated by using refresh-on-read logic (refill tokens based on elapsed time, not request count)
