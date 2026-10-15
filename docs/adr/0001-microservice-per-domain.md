# ADR-0001: Microservice Architecture per Business Domain

**Date**: 2026-04-27  
Status: Superseded by ADR-0020 (monolith collapse, 2026-09-26 — the ten services this ADR split the domain into were merged back into one `core` module)  
**Deciders**: Architecture team

## Context

The Trove platform handles multiple distinct business domains: user authentication and authorization, financial account integration via plugins, transaction categorization, budget management, reporting, and AI-powered financial coaching. Each domain has independent data models, scaling requirements, and failure modes. Early designs considered a monolithic architecture but ran into deployment inflexibility and tight coupling between unrelated services.

## Decision

Organize the system as a collection of loosely-coupled microservices, each owning one business domain:
- **api-gateway**: Authentication, rate limiting, audit logging, request routing
- **ingestion-service**: Bank account linkage, transaction sync, deduplication
- **categorisation-service**: Merchant categorization (rule-based + AI fallback)
- **budget-service**: Budget periods, envelopes, real-time health metrics
- **model-manager**: AI financial coaching via LiteLLM proxy
- **report-service**: Report generation and retrieval from MinIO storage

Each service is independently deployable, scalable, and testable. The api-gateway provides unified authentication and a stable API contract for frontend clients.

## Alternatives Considered

### Alternative 1: Monolithic Spring Boot Application
- **Pros**: Simpler deployment, shared database transactions, easier initial development
- **Cons**: Cannot scale budget logic independently from ingestion; database lock contention; tight coupling makes testing difficult
- **Why not**: Violates separation of concerns; does not match Trove's independent domain structure

### Alternative 2: Event-Driven Architecture (CQRS + Event Sourcing)
- **Pros**: Decoupled services via async events; full audit trail of all state changes
- **Cons**: Eventual consistency introduces operational complexity; harder to debug issues; requires sophisticated orchestration
- **Why not**: Trove's domains are largely independent; synchronous consistency is required for most operations

### Alternative 3: Shared Database with Separate Services (Shared Schema Pattern)
- **Pros**: Simpler data consistency; single deployment pipeline
- **Cons**: Shared schema prevents independent schema evolution; database becomes bottleneck; tight coupling on data layer
- **Why not**: Defeats the purpose of microservices; harder to scale independently

## Consequences

### Positive
- Services can be deployed, scaled, and upgraded independently
- Team can own end-to-end a single domain (reduced cognitive load)
- API contracts enforced at service boundaries prevent tight coupling
- Clear data ownership boundaries reduce merge conflicts and race conditions
- Each service can choose appropriate technologies and frameworks

### Negative
- Operational complexity increases (distributed logging, tracing, deployment orchestration)
- Cross-domain queries require choreography rather than joins
- Eventual consistency on data replication across service caches
- Network latency between services adds overhead compared to in-process calls
- Each service must independently handle secrets rotation and database migrations

### Risks
- **Service discovery**: Mitigated by api-gateway hardcoding known upstream addresses
- **Cascading failures**: Mitigated by setting appropriate timeouts and circuit breakers
- **Operational cost**: Mitigated by containerizing all services and using orchestration (Docker, Kubernetes)
