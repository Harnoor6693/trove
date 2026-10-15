# ADR-0003: Plugin Architecture for Bank Data Sources

**Date**: 2026-04-27  
Status: Superseded by ADR-0020 (monolith collapse, 2026-09-26 — `BankSourcePlugin`/`PluginRegistry` were deleted outright; bank sources are now packages under `core/src/main/java/finance/trove/ingestion/`, compiled in like any other code)  
**Deciders**: Architecture team

## Context

Trove needs to integrate with dozens of banks and financial institutions, each with different data formats, authentication mechanisms, and APIs (SimpleFin, OFX, IMAP email, custom REST endpoints, etc.). Building adapter code for each institution directly into the ingestion service would create a massive, unmaintainable codebase. The alternative is to define a stable interface that all data source adapters must implement, allowing plugins to be developed, tested, and deployed independently.

## Decision

Define a plugin interface `BankSourcePlugin` that all bank data source adapters must implement. The interface contains two methods: `testConnection()` and `fetchTransactions(date range)`. Plugins are discovered at application startup via Spring component scan and indexed by `PluginRegistry` keyed on their `getId()` method. When syncing an account, `SyncOrchestrator` looks up the correct plugin by the `sourcePlugin` identifier stored in `ConnectedAccount`. New plugins can be added by implementing `BankSourcePlugin`, marking the class with `@Component`, and adding it to the classpath—no changes to core sync logic required.

## Alternatives Considered

### Alternative 1: Hard-coded Adapter Switch Statement
- **Pros**: Simple to understand; no reflection or dependency injection complexity
- **Cons**: Core sync logic becomes brittle and huge; adding a new bank requires modifying sync code
- **Why not**: Violates Open/Closed Principle; does not scale to dozens of banks

### Alternative 2: Database-Driven Plugin Configuration
- **Pros**: New banks can be added by inserting rows into a plugins table
- **Cons**: Requires serializing plugin logic from database; makes versioning and testing harder
- **Why not**: Over-engineered; Spring component scan is sufficient for Trove's deployment model

### Alternative 3: Separate Plugin Microservice with HTTP/gRPC
- **Pros**: Plugins can be deployed independently; failure of one plugin doesn't crash sync service
- **Cons**: Network latency for every plugin call; requires orchestrating plugin microservice lifecycle
- **Why not**: Premature optimization; plugins are currently colocated; can refactor later if needed

## Consequences

### Positive
- New bank adapters can be implemented and tested independently without touching core sync logic
- Plugin interface is stable; changes to core sync don't break existing plugins
- Plugins can be developed in parallel by different teams
- Easy to mock plugins in tests; sync orchestration can be tested with fake plugins
- Plugins clearly own their own error handling and recovery

### Negative
- Every plugin must implement the same interface even if some banks don't support certain operations
- If a plugin is broken, it affects every sync operation for that bank (no graceful degradation)
- Plugin discovery via Spring component scan is implicit; easier to miss that a new plugin exists
- Shared classloader means a plugin's dependency conflicts with core's dependencies

### Risks
- **Plugin missing at runtime**: If a bank's plugin is not on the classpath, registry lookup returns null. Mitigated by validating at startup that all configured plugins are available
- **Plugin throwing unexpected exception**: Mitigated by `BankSourcePlugin` contract requiring only declared exception types (BankSourceConnectionException)
- **Credential leakage via plugin**: If a plugin logs plaintext credentials, they bypass encryption. Mitigated by code review and security guidelines in plugin API documentation
