# ADR-0004: Envelope Budgeting with Read-Time Health Calculation

**Date**: 2026-04-27  
**Status**: accepted  
**Deciders**: Product and architecture team

## Context

Trove provides envelope-based budgeting (zero-based budgeting model similar to YNAB). Users allocate monthly income into spending categories (envelopes), and the system tracks whether envelopes are on pace. A key challenge is calculating health metrics (pace marker percentage, expected spend, actual spend, over-pace status, remaining amount) that change constantly throughout the month as new transactions arrive and time passes. The initial design persisted health metrics as database columns, but this created two problems: (1) metrics become stale instantly, (2) multiple health calculations compete for database updates (contention).

## Decision

Calculate `EnvelopeHealth` metrics at read-time, never storing them in the database. The `EnvelopeHealthCalculator` is a stateless utility that computes health given a `BudgetPeriod`, `Envelope`, and current `Instant`. Health metrics are derived from: (1) the date range of the budget period (normalized to start of month), (2) allocated amount (from database), (3) sum of transactions within the envelope (from database), and (4) current time (system clock). Metrics are computed only when requested (lazy evaluation). The `BudgetPeriod` and `Envelope` aggregates store only persistent allocation data; health is a derived view.

## Alternatives Considered

### Alternative 1: Persist Health Metrics to Database
- **Pros**: Instant retrieval; no computation on every read
- **Cons**: Metrics become stale immediately; requires background refresh jobs; update contention between concurrent requests
- **Why not**: Violates temporal consistency requirement; adds operational burden of background scheduler

### Alternative 2: Cache Health Metrics in Redis with TTL
- **Pros**: Fast retrieval; automatic expiration; reduces database load
- **Cons**: Requires Redis deployment; cache invalidation on transaction inserts; stale data during TTL window
- **Why not**: Over-engineered for Trove's scale; calculation is already fast; introduces external dependency

### Alternative 3: Event-Driven Health Updates (CQRS)
- **Pros**: Health is pre-computed and always fresh; reads are instant
- **Cons**: Requires event log infrastructure; eventual consistency on health updates; harder to debug
- **Why not**: Premature optimization; read-time calculation is sufficient; adds operational complexity

## Consequences

### Positive
- Health metrics are always accurate to the current moment; no staleness
- No background jobs or scheduled tasks needed to refresh health
- No database update contention between concurrent requests
- Envelope entity focused on persistent state; health is a separate concern
- Easy to test health calculation in isolation without database

### Negative
- Every read request must compute health metrics (sum of transactions, date arithmetic)
- On budget pages with many envelopes, computing health for all envelopes adds latency
- Cannot pre-cache health metrics across multiple requests
- Health calculation logic must be duplicated in backend and frontend if frontend displays health

### Risks
- **Performance degradation on large transaction sets**: Summing thousands of transactions on every read is slow. Mitigated by database query optimization (indexed lookups), response caching at API gateway level, eventual migration to denormalized health column
- **Calculation accuracy**: Time-dependent calculations change every second. Mitigated by accepting that health is accurate as of request-time
- **N+1 queries for multiple envelopes**: Fetching budget with all envelopes then computing health requires N transaction sum queries. Mitigated by batch fetching within a single transaction
