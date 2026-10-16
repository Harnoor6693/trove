package finance.trove.users.admin;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.Instants;
import finance.trove.dataexport.DataExportRun;
import finance.trove.dataexport.DataExportRunRepository;
import finance.trove.taxexport.TaxExportRun;
import finance.trove.taxexport.TaxExportRunRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Erases accounts whose deletion grace has run out (collapse step 18; gap row 16's "7-day
 * soft-delete grace with admin restore").
 *
 * <h2>What the database does, and the one thing it cannot</h2>
 * Almost the whole erasure is one DELETE, because §3's foreign keys already say what belongs to a
 * member and what belongs to the household. Cascading from {@code users(id)} and going with the
 * row: {@code local_credential}, {@code totp_credential}, {@code oidc_link},
 * {@code refresh_token}, {@code password_reset}, {@code ai_settings},
 * {@code notification_preference}, {@code notification_log}, {@code in_app_notification},
 * {@code webhook_subscription}, {@code coach_conversation} with its messages,
 * {@code coach_insight}, {@code tax_export_run}, the member's own {@code tax_line_mapping}
 * overrides and their {@code data_export_run} rows. Surviving with the author nulled, because
 * every household-scoped table's {@code user_id} is {@code ON DELETE SET NULL}: transactions,
 * accounts, categories, rules, budget periods, envelopes, goals, snapshots, assets, liabilities,
 * subscriptions — and, from V43, receipts, which is the FK this step had to fix. Surviving with a
 * null user id by design: {@code security_event}, because an incident review must not be erasable
 * by deleting an account.
 *
 * <p><strong>{@code login_attempt} is deliberately NOT on either list, and there is no cascade to
 * put it on one.</strong> V8 keys that table by the SHA-256 of the lowercased address and the
 * client address; it has no {@code user_id} column and no foreign key to {@code users} at all,
 * because the lockout gate has to count attempts against addresses that were never registered.
 * Its retention is {@code PurgeJobs}' own {@code login-attempt-purge} sweep, on a window, and
 * nothing here reaches it.
 *
 * <p><strong>No cascade reaches a filesystem</strong>, and that is the part an implementation
 * forgets. Deleting a {@code tax_export_run} or a {@code data_export_run} row removes nothing from
 * the exports area: it leaves a complete copy of the departed member's financial life on the
 * volume with no row pointing at it, which no sweep can then find because every sweep works from
 * rows. So this job reads each run's {@code file_key} BEFORE the row goes, and deletes the
 * file only AFTER the erase has gone through, ignoring one that is already missing. Receipt images
 * are deliberately NOT deleted: the receipts
 * stay with the household (V43, owner decision 17), so their images have to stay too.
 *
 * <h2>Its own component, its own lock</h2>
 * Not a method on {@code PurgeJobs}: that class sweeps expired rows off append-only tables, and
 * this one erases people. {@code ScheduledJobsTest} scans every compiled class and fails the build
 * on a duplicate ShedLock name, an unlocked scheduled method or a cron with no UTC zone. The
 * trigger is UTC; what the job computes is an absolute instant — a grace is a duration, not a
 * calendar boundary — so no member's timezone enters into it.
 */
@Component
public class AccountPurgeJob {

    /** This job's ShedLock name; distinct from every other in the application. */
    public static final String LOCK_NAME = "account-purge";

    /**
     * How many accounts one pass erases.
     *
     * <p>Bounded so that an install with a backlog is drained over several runs rather than in one
     * transaction that erases hundreds of accounts and cascades across a dozen tables at once. A
     * fixed number rather than a property: it is a batching detail with no operator meaning, and
     * every knob in a record is a knob somebody can set wrong.
     */
    static final int BATCH_SIZE = 50;

    private static final Logger LOG = LoggerFactory.getLogger(AccountPurgeJob.class);

    private final UserRepository users;
    private final DataExportRunRepository dataExports;
    private final TaxExportRunRepository taxExports;
    private final LocalDiskStorage storage;
    private final AccountDeletionProperties properties;

    public AccountPurgeJob(UserRepository users, DataExportRunRepository dataExports,
                           TaxExportRunRepository taxExports, LocalDiskStorage storage,
                           AccountDeletionProperties properties) {
        this.users = users;
        this.dataExports = dataExports;
        this.taxExports = taxExports;
        this.storage = storage;
        this.properties = properties;
    }

    /** Daily at 04:05 UTC, clear of the five sweeps {@code PurgeJobs} runs at 03:10-03:50. */
    @Scheduled(cron = "0 5 4 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    public void purgeDeletedAccounts() {
        LOG.info("{} erased {} accounts past their restore window", LOCK_NAME,
            purge(Instants.now()));
    }

    /**
     * One bounded pass, taking the instant so a test can drive it without waiting seven days.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> Each account is erased in its
     * own short transaction — {@code eraseAccountPastItsGrace} carries one — so one member whose
     * archive cannot be deleted from the volume does not roll back the erasure of everybody else
     * in the batch, and the cascade across a dozen tables is not held open across filesystem work.
     * (An annotation here would also be inert: the scheduled method above calls this one on
     * {@code this}, which no proxy can intercept.)
     *
     * <h2>Nothing is destroyed until the row really goes</h2>
     * The SELECT that chooses a batch and the DELETE that erases one of its accounts are seconds
     * apart — the job walks up to fifty accounts and touches the volume for each — and an
     * administrator restoring somebody at the edge of the grace lands squarely in that gap. So
     * two things are ordered deliberately here. The DELETE re-states the condition
     * ({@code eraseAccountPastItsGrace}), which means a row a concurrent restore has just
     * un-stamped is no longer matched and the account survives. And the archives are read from
     * the rows BEFORE the delete but only removed from the volume AFTER it reports a row erased —
     * deleting the files first would leave a restored member with a live account and no exports.
     *
     * @return how many accounts were erased
     */
    public int purge(Instant now) {
        Instant cutoff = now.minus(properties.grace());
        List<User> expired = users.findPurgeCandidates(cutoff, PageRequest.of(0, BATCH_SIZE));
        int erased = 0;
        for (User account : expired) {
            // Resolved BEFORE the delete, because the rows that name these files cascade away
            // with the account and no sweep can find a file no row points at.
            List<Archive> archives = archivesOf(account.getId());
            // Scoped by the member's OWN household, taken from the row itself rather than from any
            // request: this job has no caller and no token, and the scoped repository has no
            // unscoped delete to reach for.
            int removed = users.eraseAccountPastItsGrace(account.getId(),
                account.getHouseholdId(), cutoff);
            if (removed > 0) {
                archives.forEach(Archive::delete);
                erased++;
                LOG.info("Erased an account whose restore window closed (household {})",
                    account.getHouseholdId());
            } else {
                LOG.info("Skipped an account that is no longer past its restore window "
                    + "(household {}); a restore won the race and its archives were kept",
                    account.getHouseholdId());
            }
        }
        return erased;
    }

    /**
     * The member's export archives on the volume, resolved from their rows.
     *
     * <p>Only theirs. {@code data_export_run} is user-scoped, so {@code findAllByUserId} is
     * exactly the set, and a household mate's archives are not reachable from here at all.
     *
     * <p>Tax-export archives go the same way and for the same reason: {@code tax_export_run} is
     * user-scoped too, and its files sit in the same exports area with no cascade that can reach
     * them. This job deletes what a row resolves to and never guesses at a path.
     */
    private List<Archive> archivesOf(UUID userId) {
        List<Archive> archives = new ArrayList<>();
        for (DataExportRun run : dataExports.findAllByUserId(userId)) {
            if (run.getFileKey() != null) {
                archives.add(new Archive(run.getFileKey(), "data export run", run.getId()));
            }
        }
        for (TaxExportRun run : taxExports.findAllByUserId(userId)) {
            if (run.getFileKey() != null) {
                archives.add(new Archive(run.getFileKey(), "tax export run", run.getId()));
            }
        }
        return archives;
    }

    /** One file on the volume, with enough context to log a failure without naming the path. */
    private final class Archive {

        private final String fileKey;
        private final String what;
        private final UUID runId;

        private Archive(String fileKey, String what, UUID runId) {
            this.fileKey = fileKey;
            this.what = what;
            this.runId = runId;
        }

        private void delete() {
            try {
                storage.delete(fileKey);
            } catch (RuntimeException e) {
                // swallow-ok: a file that cannot be deleted must not stop the erasure of the
                // account that asked to be erased — the row would then survive for ever and the
                // member would still be in the system. The key is never logged; it is a path.
                LOG.error("Could not delete the archive of {} {} during the purge", what, runId,
                    e);
            }
        }
    }
}
