# ADR-0009: Three-Step Transaction Deduplication Strategy

**Date**: 2026-04-27  
**Status**: accepted  
**Deciders**: Product and backend team

## Context

When a user syncs transactions from their bank, the bank API may return transactions that were already fetched in a previous sync (retransmission). Additionally, when a transaction is first created, it often appears in the bank's system as "pending" and later transitions to "posted" (cleared) status. Both scenarios can result in duplicate or conflicting transaction records if not handled carefully. The deduplication strategy must handle: (1) exact retransmissions (same transaction sent twice), (2) pending-to-posted transitions (same transaction in different states), and (3) genuinely new transactions (insertions).

## Decision

Implement a three-step deduplication strategy applied to each incoming transaction:

1. **Exact Match on sourceId**: Check if a transaction with the same `sourceId` (bank's unique identifier) already exists. If found:
   - If incoming is posted and existing is pending, update existing to posted and return UPDATED
   - If both are posted or both are pending, return SKIPPED (duplicate)

2. **Fuzzy Match within Date and Amount Tolerance**: If no exact match, search for a pending transaction on the same account whose date falls within ±2 days and whose amount falls within ±$0.50 of the incoming transaction. If found and incoming is posted, promote existing to posted and return UPDATED.

3. **New Insertion**: If neither exact nor fuzzy match succeeded, insert as a new row and return NEW.

## Alternatives Considered

### Alternative 1: Exact Match Only (No Fuzzy Matching)
- **Pros**: Simpler logic; faster queries (single index lookup); deterministic behavior
- **Cons**: Misses pending-to-posted transitions; creates duplicate records when pending and posted versions have different sourceIds
- **Why not**: Banks often issue different sourceIds for pending vs. posted transactions; fuzzy matching is essential

### Alternative 2: Fuzzy Match Only (No Exact Match First)
- **Pros**: Catches all pending-to-posted transitions
- **Cons**: May match unrelated transactions (e.g., two coffee purchases on the same day); slower queries (table scan with date/amount predicates)
- **Why not**: Exact match should be checked first; fuzzy match can collide on unrelated transactions

### Alternative 3: Timestamp-Based Deduplication
- **Pros**: Uses bank-issued timestamp for matching
- **Cons**: Timestamp may be null for pending transactions; banks may adjust timestamps during clearing; timezone issues
- **Why not**: sourceId and date are more reliable; timestamp alone is insufficient

## Consequences

### Positive
- Handles exact retransmissions (same sourceId) without creating duplicates
- Handles pending-to-posted transitions (different sourceIds, same amount/date)
- Three-step approach is efficient (exact match is fast, fuzzy match is rare)
- Clear logic that developers can understand and maintain
- Deduplication errors (false negatives) result in extra transactions, not lost transactions (fail safe)

### Negative
- Fuzzy matching tolerance (±2 days, ±$0.50) is somewhat arbitrary; may need tuning per bank
- Fuzzy matching can collide on unrelated transactions (e.g., two purchases for same amount on same day)
- Fuzzy matching requires a more complex database query (multiple predicates)
- Pending transactions with no sourceId cannot be deduplicated (fall through to new insertion)

### Risks
- **False positive fuzzy matches**: User makes two identical purchases on the same day; fuzzy match incorrectly promotes the first as posted. Mitigated by: (1) setting stricter tolerance (±1 day, ±$0.10) if collisions occur, (2) requiring exact sourceId match before deduplicating, (3) displaying deduplication confidence score to user
- **Missing pending transactions**: If a pending transaction doesn't appear in the next sync, it stays pending forever. Mitigated by: (1) background job that transitions old pending transactions to posted after N days, (2) user-facing UI that shows pending transactions and allows manual reconciliation
- **Bank changes sourceId on posting**: Some banks change sourceId when transaction posts. Mitigated by relying on fuzzy match as fallback if exact match fails
