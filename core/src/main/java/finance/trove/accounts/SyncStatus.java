package finance.trove.accounts;

/**
 * The state of a {@link ConnectedAccount}'s most recent sync
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V18, §6 M0).
 *
 * <p>{@link #SYNCING} is not a hint that a sync is probably running. It is a <strong>claim</strong>
 * written by a conditional {@code UPDATE … WHERE sync_status <> 'SYNCING'}: exactly one caller can
 * win it, and the loser is answered 409 rather than being allowed to fetch the same window and
 * collide on the unique dedup index halfway through the batch. The pre-collapse enum carried the
 * value with a comment calling it "reserved for future async use" while the CHECK on the column
 * rejected it outright, so the guard could not have been written even if somebody had tried.
 */
public enum SyncStatus {

    /** Created, never synced. */
    PENDING,

    /** A sync run holds the claim on this account right now. */
    SYNCING,

    /** The last run reached the source. Individual rows may still have been refused. */
    OK,

    /** The last run could not reach the source; {@code error_message} says why. */
    ERROR
}
