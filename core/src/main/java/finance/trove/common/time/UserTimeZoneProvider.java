package finance.trove.common.time;

import java.time.ZoneId;
import java.util.UUID;

/**
 * Supplies the IANA timezone a user's date boundaries are computed in.
 *
 * <p>{@link UserClock} depends on this interface rather than on the {@code users} table so that
 * collapse step 2 can land the clock before step 3 creates that table. Step 3 adds the real
 * implementation reading {@code users.timezone}; until then
 * {@link DefaultUserTimeZoneProvider} answers the install default for everyone
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, gap P2-15).
 */
public interface UserTimeZoneProvider {

    /**
     * The zone for {@code userId}, or the install default when the user is unknown or has not
     * chosen one. Never returns {@code null}: a missing preference degrades to a default, it does
     * not make "today" undefined.
     *
     * @param userId the user whose boundaries are being computed; may be {@code null} for
     *               instance-wide work such as a maintenance sweep
     */
    ZoneId zoneOf(UUID userId);

    /**
     * Discards any cached answer for one user.
     *
     * <p>A no-op by default, because a provider that computes its answer has nothing to discard.
     * The table-backed implementation caches — a zone is read on every per-user date boundary and
     * changes perhaps twice in an account's life — and without this the settings screen would
     * appear to save a new timezone while "today" kept its old boundaries until the cache entry
     * aged out. That is the kind of bug that gets reported as "the dashboard is a day behind".
     *
     * <p>On the interface rather than only on the implementation so that the caller
     * ({@code UserProfileService}) depends on the contract instead of on which implementation is
     * wired, which is what keeps {@code common} free of a dependency on {@code users}.
     */
    default void evict(UUID userId) {
        // Nothing cached.
    }
}
