package finance.trove.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.households.invites.InviteService;
import finance.trove.subscriptions.scheduler.SubscriptionScanJob;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Risk 14 and plan §2.7: the bootstrap deadlock that made the pre-collapse feature incapable of
 * ever detecting its first subscription on any install.
 *
 * <p>The old nightly job enumerated its work with
 * {@code SubscriptionRepository.findDistinctActiveUserIds()} —
 * {@code SELECT DISTINCT userId FROM DetectedSubscription WHERE cancelled = false}. On a fresh
 * install that table is empty, so nobody was scanned, so nothing was written, so the table was
 * still empty the next night. The job genuinely ran every night and could never produce a first
 * row. Moving that query across unchanged would have relocated a dead feature into the monolith.
 *
 * <p>This test is the proof that it did not move across. A household is created with three
 * monthly debits and, at the moment the job runs, <strong>zero</strong> rows in
 * {@code detected_subscription} — the exact state the old query returned nobody for.
 */
class SubscriptionScanFreshInstallIT extends SubscriptionSupport {

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private SubscriptionScanJob job;

    @Test
    @DisplayName("A brand-new household with three monthly debits and no rows gets a detection")
    void theNightlyJobDetectsTheFirstSubscriptionOfAFreshHousehold() {
        Session fresh = createFreshHousehold();
        String accountId = createManualAccount(fresh, "Fresh ledger " + UUID.randomUUID());
        String merchant = uniqueMerchant("FRESH INSTALL");
        seedMonthlyCharges(fresh, accountId, merchant, "8.75", 3, 1);

        assertThat(rowsIn(fresh.householdId()))
            .as("the household has transactions and NOT ONE detected subscription — the exact "
                + "state the pre-collapse enumeration returned nobody for")
            .isZero();

        int refreshed = job.runOnce();

        assertThat(refreshed)
            .as("the job enumerates households with TRANSACTIONS, not the result table")
            .isGreaterThanOrEqualTo(1);
        assertThat(rowsIn(fresh.householdId())).isEqualTo(1);

        JsonNode row = subscriptionFor(listSubscriptions(fresh), merchant);
        assertThat(row.path("billingCycle").asString()).isEqualTo("MONTHLY");
        assertThat(row.path("normalizedAmount").asString()).isEqualTo("8.7500");
        assertThat(row.path("createdBy").asString())
            .as("a scheduled run has no caller, so it is attributed to the household's own "
                + "administrator — the same rule NetWorthSnapshotService uses")
            .isEqualTo(fresh.userId().toString());
    }

    @Test
    @DisplayName("The manual trigger detects a fresh household's first subscription too")
    void theManualTriggerAlsoWorksFromNothing() {
        Session fresh = createFreshHousehold();
        String accountId = createManualAccount(fresh, "Manual ledger " + UUID.randomUUID());
        String merchant = uniqueMerchant("MANUAL FIRST");
        seedMonthlyCharges(fresh, accountId, merchant, "21.00", 3, 1);

        assertThat(rowsIn(fresh.householdId())).isZero();

        JsonNode row = subscriptionFor(scan(fresh), merchant);

        assertThat(row.path("normalizedAmount").asString()).isEqualTo("21.0000");
        assertThat(rowsIn(fresh.householdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A household with no transactions at all is skipped without writing anything")
    void aHouseholdWithNothingToDetectIsSkipped() {
        Session fresh = createFreshHousehold();

        job.runOnce();

        assertThat(rowsIn(fresh.householdId())).isZero();
        assertThat(listSubscriptions(fresh)).isEmpty();
    }

    private int rowsIn(UUID householdId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM detected_subscription WHERE household_id = ?", Integer.class,
            householdId);
        return count == null ? 0 : count;
    }

    /**
     * A household that has never held a detected subscription.
     *
     * <p>Two inserts, the same fixture {@code AccessControlIT} uses and for the same reason: the
     * application deliberately offers no way to create a second household, one per install being
     * the owner's decision. Everything after them — the registration, the credential, the
     * session, every transaction — goes through production code.
     */
    private Session createFreshHousehold() {
        UUID householdId = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES ('Fresh install', false) "
                + "RETURNING id", UUID.class);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdId, InviteService.hash(token));

        return registerWithInvite(token, uniqueEmail("fresh-install-admin"), MEMBER_PASSWORD);
    }
}
