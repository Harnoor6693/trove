package finance.trove.dataexport;

import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.households.HouseholdRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-per-day cap and the INSERT it guards, taken together under a lock.
 *
 * <h2>Why this is its own bean rather than two lines of {@link DataExportService}</h2>
 * <strong>"Has this member already exported today?" is a check-then-act, and a check-then-act
 * with no lock is not a cap.</strong> Counting the caller's runs and then inserting a new one in
 * two separate, uncommitted-in-between interactions lets N simultaneous
 * {@code POST /v1/me/export} calls from one member all read {@code already == 0}, all walk past
 * the guard and all start a run — and the cap is the only ceiling on the most expensive
 * authenticated operation in the product. Each run reads the household's whole ledger plus
 * fourteen datasets into memory on the shared {@code exportExecutor} (2 core / 4 max / 50 queued)
 * and writes a ZIP to the data volume. Nothing in the schema catches it either: V41's indexes are
 * ordinary btrees, and a unique index cannot express "one per the member's own local day" because
 * that boundary comes from {@code users.timezone} rather than from the stored instant.
 *
 * <p>This is the identical defect, and the identical remedy, that
 * {@code UserAdminService.deleteMember} already carries for the last-ADMIN guard: the count and
 * the write happen behind a {@code PESSIMISTIC_WRITE} lock on the <strong>household row</strong>,
 * which is the serialisation point this tree already uses for decisions about a household's
 * members. Contention costs nothing here — a member is entitled to one export a day, so two
 * requests meeting on this lock is by definition a duplicate.
 *
 * <p>It is a separate bean because {@link DataExportService#request} is <strong>deliberately not
 * transactional</strong> (the INSERT has to be COMMITTED before the background thread looks for
 * the row) and Spring cannot proxy a self-invocation: a {@code @Transactional} method called on
 * {@code this} runs with no transaction at all, which would be the same bug wearing an
 * annotation. {@code REQUIRES_NEW} rather than {@code REQUIRED} for the same reason: this write
 * must commit on its own, whatever the caller is or is not inside.
 */
@Service
public class DataExportRunStarter {

    private final DataExportRunRepository runs;
    private final HouseholdRepository households;
    private final DataExportProperties properties;
    private final UserClock clock;

    public DataExportRunStarter(DataExportRunRepository runs, HouseholdRepository households,
                                DataExportProperties properties, UserClock clock) {
        this.runs = runs;
        this.households = households;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Counts the caller's runs over their own local day and records a new {@code PENDING} one, or
     * refuses.
     *
     * <p>Gap row 16's "1/day", counted over the member's OWN local day.
     * {@code UserClock.startOfDay} resolves against {@code users.timezone}, so two members in
     * {@code America/Vancouver} and {@code America/Toronto} get different boundaries from the same
     * instant — which is the point: a cap measured on the container's UTC day would refuse a
     * Vancouver member their second calendar day's export at 4 pm and allow a Toronto member's at
     * 8 pm, for no reason either of them could see.
     *
     * <p>This is deliberately NOT expressed in {@code RateLimitFilter}. That limiter buckets by
     * path and client IP, so it cannot say "per member" at all: it would refuse a household
     * sharing one connection and let one member with two networks through twice. V41 does seed a
     * flood ceiling for the route, which is a different control for a different attack.
     *
     * @throws ConflictException 409 — the member has already used today's allowance
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DataExportRun start(UUID userId, UUID householdId, Instant now) {
        // Everything below happens behind this lock. Without it the count and the INSERT are two
        // decisions about one member that can change in between them.
        households.findByIdForUpdate(householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Household", householdId));

        LocalDate today = clock.dateOf(userId, now);
        Instant dayStart = clock.startOfDay(userId, today);
        long already = runs.countByUserIdAndCreatedAtGreaterThanEqual(userId, dayStart);
        if (already >= properties.runsPerDay()) {
            Instant nextDay = clock.endOfDayExclusive(userId, today);
            throw new ConflictException(
                "You have already asked for " + already + " export"
                    + (already == 1 ? "" : "s") + " today. Building one is a copy of your whole "
                    + "account, so it is limited to " + properties.runsPerDay()
                    + " a day in your own timezone. You can ask again after " + nextDay
                    + ", and your existing export is still on this page until it expires.");
        }
        return runs.save(new DataExportRun(userId, householdId, now));
    }
}
