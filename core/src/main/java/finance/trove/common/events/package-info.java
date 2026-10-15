/**
 * The in-process event types that join Trove's domain packages
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6).
 *
 * <p>The collapse removes nine HTTP hops between services; these records are what replaces them.
 * A package never calls another package's internals — it publishes one of these with
 * {@code ApplicationEventPublisher} and the interested package consumes it with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. That phase is load-bearing: the
 * seams the collapse is fixing (D1's envelope spend, D4's category write-back) are exactly the
 * places where a listener that ran inside the publisher's transaction would either see
 * uncommitted rows or be rolled back with them.
 *
 * <p>A listener that writes opens its own {@code REQUIRES_NEW} transaction. After
 * {@code AFTER_COMMIT} the publishing transaction is finished, so a listener that simply called
 * a {@code @Transactional} service would run without one and its write would be silently
 * discarded — the specific bug §6's D4 note calls out.
 *
 * <p>Every event carries {@code householdId}: the ledger is household-scoped (owner decision 6),
 * so a listener that recomputes an aggregate needs the household, and {@code userId} alone would
 * make it recompute the wrong one.
 */
package finance.trove.common.events;
