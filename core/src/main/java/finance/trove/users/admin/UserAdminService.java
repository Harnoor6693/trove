package finance.trove.users.admin;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.households.HouseholdRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write half of {@code /v1/admin/users} — the part that has to be atomic.
 *
 * <p>It exists for one reason: <strong>"is this the last administrator?" is a check-then-act, and
 * a check-then-act with no lock is not a guard.</strong> The controller counted the household's
 * admins and then deleted in two separate database interactions. Two DELETEs arriving at the same
 * instant against a household's last two administrators — an admin removing a colleague while the
 * colleague removes them, or one impatient person with two tabs — both read {@code count == 2},
 * both walked past {@code <= 1}, and both committed. The household is then left with no
 * administrator at all, and there is no way back in through the API: registration is closed,
 * opening it is an admin route, and so is minting an invite. An install in that state needs a
 * database edit or the console commands of {@code finance.trove.app.cli}.
 *
 * <p>The serialisation point is the <strong>household row</strong>, taken
 * {@code PESSIMISTIC_WRITE} before anything is counted. The tenancy root is the natural choice:
 * it is one row, it always exists for a request that got this far, and every membership change in
 * the household passes through it, so two of them cannot interleave. Locking the admin rows
 * instead would leave the gap where a concurrent <em>promotion</em> or deletion adds a row the
 * first transaction never locked. This is the same reasoning that gave invite redemption
 * {@code findByTokenHashForUpdate} and refresh rotation {@code findByTokenHashForUpdate}; it was
 * simply never extended to this route.
 */
@Service
public class UserAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;
    private final HouseholdRepository households;
    private final SecurityEventRecorder securityEvents;
    private final RefreshTokenService sessions;
    private final AccountDeletionProperties properties;
    private final UserClock clock;

    public UserAdminService(UserRepository users, HouseholdRepository households,
                            SecurityEventRecorder securityEvents, RefreshTokenService sessions,
                            AccountDeletionProperties properties, UserClock clock) {
        this.users = users;
        this.households = households;
        this.securityEvents = securityEvents;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Removes a member of {@code householdId}, or refuses.
     *
     * <p><strong>Since collapse step 18 this is a SOFT delete</strong> and it is the one path both
     * {@code DELETE /v1/admin/users/{id}} and {@code DELETE /v1/me} take — one guard, one lock, one
     * event, one behaviour, so the two routes cannot drift. Self-deletion simply passes
     * {@code targetId == actorId}, which the last-ADMIN guard already covers because it counts
     * rather than comparing ids.
     *
     * <p>The member is deactivated, stamped and their sessions revoked; the row survives for the
     * restore window ({@link #grace()}) and is then erased by {@code AccountPurgeJob}.
     * {@code GET /v1/admin/users} already returns {@code active}, so a soft-deleted member is
     * visible to the administrator who has to decide whether to restore them.
     *
     * @param householdId the acting admin's own household, from the signed token — never a path
     *                    variable, so "not yours" and "does not exist" are the same answer
     * @param actorId     the acting admin, or the member themselves on {@code DELETE /v1/me}
     * @param targetId    the member to remove
     * @throws ResourceNotFoundException 404 — another household's id, or an unknown one
     * @throws ConflictException         409 — the target is the household's last ADMIN, or is
     *                                   already inside the restore window
     */
    @Transactional
    public void deleteMember(UUID householdId, UUID actorId, UUID targetId) {
        // Everything below happens behind this lock. Without it the count and the delete are two
        // decisions about a household that can change in between them — proven, not assumed:
        // removing these two lines makes AccessControlIT#theLastAdminGuardHoldsUnderConcurrency
        // answer [200, 200] and leave the household with no administrator at all.
        households.findByIdForUpdate(householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Household", householdId));

        User target = users.findByIdAndHouseholdId(targetId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("User", targetId));

        // A member who is already inside the grace is refused, and the symmetry with
        // restoreMember below is the point: that method refuses to "restore" somebody who was
        // never deleted because answering 200 would tell an administrator they had undone
        // something they had not. Deleting a deletion is the same lie in the other direction, and
        // it is worse than cosmetic — softDelete overwrites the stamp, so a second call restarts
        // the seven-day clock, pushes the erasure back another week, loses who actually removed
        // them, and writes a second ACCOUNT_DELETE for a deletion that already happened. Repeated
        // often enough it defers an erasure for ever while the API reports success every time.
        if (target.getDeletedAt() != null) {
            throw new ConflictException(
                "That member has already been deleted and is inside their restore window. There "
                    + "is nothing left to delete; asking again would only restart the window and "
                    + "delay their erasure. Wait for it to pass, or restore them first.");
        }

        // ACTIVE admins, not all admins. Since this delete became soft (collapse step 18) a
        // departed administrator's row survives the grace period, and counting it would let the
        // household's last LIVE administrator delete themselves behind a guard that saw two.
        if (target.getRole() == UserRole.ADMIN
            && users.countByHouseholdIdAndRoleAndActiveTrue(householdId, UserRole.ADMIN) <= 1) {
            throw new ConflictException(
                "This is the household's last administrator. Give another member the admin role "
                    + "first, or the household would be left with no way to invite anyone or "
                    + "change its settings.");
        }

        // Recorded BEFORE the delete, and the order is not cosmetic. SecurityEventRecorder writes
        // in REQUIRES_NEW, so an insert issued after the row is gone (an admin deleting their own
        // account, which is allowed while somebody else can still administer the household) fails
        // its foreign key to users — and this recorder swallows database errors by design, so the
        // event would be silently lost. Worse, issued after the delete but inside this
        // transaction it would block: the new transaction's foreign-key check waits for a row
        // this transaction has deleted and not yet committed, while this transaction waits for
        // the recorder to return. Recording first means the references exist, and V9's
        // ON DELETE SET NULL then redacts them exactly as it was designed to — the ACCOUNT_DELETE
        // row survives with its metadata naming who was removed.
        securityEvents.record(SecurityEventType.ACCOUNT_DELETE, actorId, actorId,
            Map.of("deletedUserId", targetId.toString(), "role", target.getRole().name(),
                "self", targetId.equals(actorId)));

        // COLLAPSE STEP 18 changes what happens here, and nothing above it. The guard, the lock,
        // the 409 and the event are exactly as they were; what used to be
        // `users.deleteByIdAndHouseholdId` is now a SOFT delete, because gap row 16 asks for a
        // seven-day grace an administrator can restore from and an erased row cannot be restored
        // from anything. The row is deactivated and stamped inside this same locked transaction,
        // every session of the member is revoked, and `AccountPurgeJob` erases the row for good
        // once the grace has passed.
        //
        // `is_active = false` is what makes the member GONE immediately, and it is not a new
        // mechanism: AuthService filters it twice, OidcAccountLinker twice, LocalAccountService,
        // WeeklyDigestJob, NotificationEventListener, WebhookEventListener and InsightRecipients'
        // SQL all check it already. So from this commit the member cannot sign in, cannot refresh,
        // receives no digest, is not enumerated as an insight recipient and is not a webhook
        // audience — during the grace, exactly as after it.
        Instant now = clock.now();
        target.softDelete(actorId, now);
        users.save(target);

        // The session revocation is what closes the window the soft delete would otherwise open.
        // A hard delete took the refresh_token rows with it through ON DELETE CASCADE; a soft one
        // does not, and a live access token outlives its own row for as long as it has left to
        // run. CookieOrBearerJwtAuthFilter asks SessionValidity.isLive(fid) on EVERY authenticated
        // request — one indexed refresh_token read, deliberately uncached — so revoking the
        // families here means the member's very next call is refused, whatever token they hold.
        int revoked = sessions.revokeAllForUser(targetId, "account-deleted", now);

        LOG.info("A {} entered the deletion grace in household {} ({} sessions revoked)",
            target.getRole(), householdId, revoked);
    }

    /**
     * Brings a member back inside the deletion grace, or refuses.
     *
     * <p>The other half of the soft delete, and the reason it is a soft delete at all (gap row
     * 16). Same household lock as {@link #deleteMember}: a restore and a delete racing on the same
     * household are two decisions about who administers it, and the last-ADMIN guard reads a count
     * that a concurrent restore changes.
     *
     * <p>Three refusals:
     * <ul>
     *   <li>another household's id, or an unknown one — <strong>404</strong>, because the lookup
     *       is household-scoped and "not yours" and "does not exist" are the same answer;</li>
     *   <li>a member who was never deleted — <strong>409</strong>. There is nothing to restore, and
     *       answering 200 would tell an administrator they had undone something they had not;</li>
     *   <li>a member whose grace has passed — <strong>409</strong>. The row is gone, or the next
     *       purge pass is about to take it, and reactivating an account that is queued for erasure
     *       would be a promise this service cannot keep.</li>
     * </ul>
     *
     * <p>The event is {@code ACCOUNT_DELETE} with {@code action: restore} in its metadata rather
     * than a new {@code SecurityEventType} constant, and that is forced rather than preferred:
     * {@code SecurityEventType}'s names must match {@code V9__security_event.sql}'s CHECK exactly,
     * so a new constant needs a migration, and this step's V-numbers are spoken for. Keying the
     * deletion and the restoration of one account to one event type is also how they stay
     * adjacent in an incident review, which is where somebody asks "was this account ever
     * deleted, and who brought it back?".
     */
    @Transactional
    public void restoreMember(UUID householdId, UUID actorId, UUID targetId) {
        households.findByIdForUpdate(householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Household", householdId));

        User target = users.findByIdAndHouseholdId(targetId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("User", targetId));

        Instant deletedAt = target.getDeletedAt();
        if (deletedAt == null) {
            throw new ConflictException(
                "That member has not been deleted, so there is nothing to restore. An account "
                    + "that is merely deactivated is a different state and is not restored from "
                    + "here.");
        }
        Instant graceEnds = deletedAt.plus(properties.grace());
        Instant now = clock.now();
        if (!now.isBefore(graceEnds)) {
            throw new ConflictException(
                "That member's " + properties.grace().toDays() + "-day restore window closed at "
                    + graceEnds + ". Their account is being erased and cannot be brought back; "
                    + "invite them again to start a new one.");
        }

        target.restore();
        users.save(target);

        // recordAfterCommit, not record: this row is written BECAUSE the transaction SUCCEEDED,
        // and a REQUIRES_NEW insert here would survive a rollback and claim a restoration that
        // never happened. That is the exact opposite of the deletion event above, which is written
        // because a request may fail and must be recorded either way.
        securityEvents.recordAfterCommit(SecurityEventType.ACCOUNT_DELETE, targetId, actorId,
            Map.of("action", "restore", "restoredUserId", targetId.toString(),
                "deletedAt", deletedAt.toString()));
        LOG.info("A {} was restored in household {}", target.getRole(), householdId);
    }

    /** How long a deleted member may still be restored — the property's value, for callers. */
    public Duration grace() {
        return properties.grace();
    }
}
