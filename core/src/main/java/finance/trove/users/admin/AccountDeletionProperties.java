package finance.trove.users.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a deleted account can still be restored (collapse step 18; gap row 16's "7-day
 * soft-delete grace with admin restore").
 *
 * <p><strong>The default lives here and nothing is added to {@code application.yml}</strong>,
 * which this step may not edit: steps of a wave run in parallel worktrees, so a property added to
 * that file is a merge conflict at best and a silently lost edit at worst. A record bound by
 * {@code @ConfigurationPropertiesScan} with its default in the canonical constructor needs no yml
 * entry to work and picks one up the moment an operator adds one — the shape
 * {@code ReceiptProperties} already uses. Spring's relaxed binding maps the key to
 * {@code TROVE_ACCOUNT_DELETION_GRACE}.
 *
 * @param deletionGrace how long a stamped account survives before {@code AccountPurgeJob} erases
 *                      it. Seven days is gap row 16's figure and it is the right shape: long
 *                      enough that a member who deleted in anger, or an administrator who removed
 *                      the wrong person, has a working week to notice; short enough that "deleted"
 *                      means something. It is a floor as well as a ceiling — a value of zero would
 *                      make the very next purge pass erase an account the member could still be
 *                      arguing about, so it is refused
 */
@ConfigurationProperties("trove.account")
public record AccountDeletionProperties(Duration deletionGrace) {

    /** Gap row 16's "7-day soft-delete grace". */
    public static final Duration DEFAULT_DELETION_GRACE = Duration.ofDays(7);

    public AccountDeletionProperties {
        deletionGrace = deletionGrace == null ? DEFAULT_DELETION_GRACE : deletionGrace;

        if (deletionGrace.isNegative() || deletionGrace.isZero()) {
            throw new IllegalArgumentException(
                "trove.account.deletion-grace must be positive; zero or less would let the next "
                    + "purge pass erase an account before anybody could ask for it back, which "
                    + "is the whole thing the grace exists to prevent. Got " + deletionGrace);
        }
    }

    /** The grace, under the name the rest of this package reads it by. */
    public Duration grace() {
        return deletionGrace;
    }
}
