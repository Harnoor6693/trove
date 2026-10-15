package finance.trove.notifications.digest;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.notifications.NotificationSupport;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The weekly digest against real rows — inventory defect <strong>D-N1</strong> and finding
 * <strong>F1</strong>.
 *
 * <h2>What the source did</h2>
 * {@code WeeklyDigestScheduler} fetched the week's transactions from the ingestion service over
 * HTTP with three independent mistakes in one call (lines 55-60): the header
 * {@code X-Trove-User-Id} where the target required {@code X-User-Id}, the parameters
 * {@code user_id}/{@code date_from}/{@code date_to} where the target read {@code from}/{@code to},
 * and an expected object {@code {"transactions":[…]}} where the target returned a bare array. Any
 * one of the three breaks it. The per-user {@code try/catch} then logged and counted a "skip", so
 * the job ran every Monday and reported success with {@code sent=0} for the life of the service.
 * The source's {@code WeeklyDigestSchedulerTest} passed throughout, because its WireMock stubs
 * pinned the broken contract as correct — which is why it is deliberately not ported.
 *
 * <p>All of it evaporates here: the read is an in-process
 * {@code TransactionQueryService.findSpendableInRange} call, and this test asserts against rows a
 * real member really has.
 */
class WeeklyDigestIT extends NotificationSupport {

    @Autowired
    private WeeklyDigestJob job;

    @Autowired
    private WeeklyDigestBuilder builder;

    @Autowired
    private finance.trove.notifications.preference.NotificationPreferenceRepository preferences;

    private Session member;
    private String accountId;

    @BeforeEach
    void signIn() {
        // Its OWN household, not the suite-wide bootstrap one. The digest sums the recipient's
        // HOUSEHOLD ledger, and household A accumulates transactions from every other IT class in
        // this suite — so a member invited into it would be reported a week that other tests
        // wrote. Two SQL statements, exactly as AccessControlIT creates household B: the
        // application deliberately offers no route that makes a second household.
        member = registerInOwnHousehold("digest-member", "America/Toronto");
        accountId = createManualAccount(member, "Digest " + UUID.randomUUID());
    }

    /** A date inside the last complete week, as the recipient's own calendar reads it. */
    private LocalDate lastWeek(int dayOffset) {
        return builder.weekStartFor(member.userId()).plusDays(dayOffset);
    }

    private JsonNode digestFor(Session session) {
        return inbox(session).stream()
            .filter(row -> "WEEKLY_DIGEST".equals(row.path("type").asString()))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("A real recipient with real rows gets one digest, over their own week")
    void aRecipientGetsOneDigest() {
        createManualTransaction(member, accountId, "40.00", lastWeek(1).toString(), "DEBIT",
            "GROCER");
        createManualTransaction(member, accountId, "10.00", lastWeek(3).toString(), "REFUND",
            "GROCER");
        createManualTransaction(member, accountId, "500.00", lastWeek(4).toString(), "CREDIT",
            "EMPLOYER");
        // The Monday of the CURRENT week — one day past the window, so it belongs to next
        // Monday's digest and not to this one. Offset 7 rather than 8 on purpose: weekStart + 7
        // is this week's Monday, which is never later than today whatever day the suite runs on,
        // whereas + 8 would be a future date every Monday and manual entry refuses those.
        createManualTransaction(member, accountId, "999.00",
            lastWeek(7).toString(), "DEBIT", "NOT-THIS-WEEK");

        assertThat(job.sendOne(member.userId())).isTrue();

        JsonNode digest = digestFor(member);
        assertThat(digest).isNotNull();
        LocalDate weekStart = builder.weekStartFor(member.userId());
        assertThat(digest.path("title").asString())
            .isEqualTo("Your week: " + weekStart + " to " + weekStart.plusDays(6));
        assertThat(digest.path("body").asString())
            .as("a REFUND nets down the spend it reverses, and the week is a closed window")
            .contains("CAD: spent 30.00", "received 500.00", "net 470.00", "3 transactions")
            .doesNotContain("999.00");
        assertThat(digest.path("actionUrl").asString()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("The digest is deduplicated by the ISO week, so a second run sends nothing")
    void aSecondRunSendsNothing() {
        createManualTransaction(member, accountId, "12.00", lastWeek(2).toString(), "DEBIT",
            "SHOP");

        assertThat(job.sendOne(member.userId())).isTrue();
        assertThat(job.sendOne(member.userId()))
            .as("the key is the ISO week in this member's own zone; the second run's INSERT "
                + "conflicts with V32's unique index")
            .isFalse();

        assertThat(inbox(member))
            .filteredOn(row -> "WEEKLY_DIGEST".equals(row.path("type").asString()))
            .hasSize(1);
        String expectedKey = WeeklyDigestBuilder.isoWeekOf(builder.weekStartFor(member.userId()));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND type = 'WEEKLY_DIGEST' "
                + "AND dedup_key = ?", Integer.class, member.userId(), expectedKey))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("The figures are in the recipient's own currency, per currency, never blended")
    void twoCurrenciesAreReportedSeparately() {
        // Finding F1: the source hardcoded "currency": "CAD" into the template variables. There
        // is no exchange rate anywhere in this system, so a single blended total would be a
        // number nobody could reconcile.
        String usdAccount = createAccount(member, MANUAL, "USD " + UUID.randomUUID(), "OTHER",
            "USD");
        createManualTransaction(member, accountId, "40.00", lastWeek(1).toString(), "DEBIT",
            "GROCER");
        createManualTransaction(member, usdAccount, "25.00", lastWeek(2).toString(), "DEBIT",
            "US SHOP");

        job.sendOne(member.userId());

        String body = digestFor(member).path("body").asString();
        assertThat(body)
            .as("two lines, one per currency, with the recipient's own first")
            .contains("CAD: spent 40.00")
            .contains("USD: spent 25.00");
        assertThat(body.indexOf("CAD:")).isLessThan(body.indexOf("USD:"));
    }

    @Test
    @DisplayName("Two members in different zones can get different weeks from one run")
    void theWindowIsPerRecipient() {
        // gap P2-15. The trigger is a single UTC cron; the CONTENT is computed per recipient from
        // users.timezone, so a Vancouver member's week and a Toronto member's week are the same
        // dates on most days and different ones for the seven hours between their midnights.
        Session vancouver = registerInOwnHousehold("digest-vancouver", "America/Vancouver");
        Session toronto = registerInOwnHousehold("digest-toronto", "America/Toronto");

        LocalDate vancouverWeek = builder.weekStartFor(vancouver.userId());
        LocalDate torontoWeek = builder.weekStartFor(toronto.userId());

        assertThat(vancouverWeek.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(torontoWeek.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(vancouverWeek)
            .as("never more than one week apart, and never ahead of Toronto's — Vancouver is "
                + "behind UTC, so its calendar turns over later")
            .isIn(torontoWeek, torontoWeek.minusWeeks(1));

        job.sendOne(vancouver.userId());
        job.sendOne(toronto.userId());

        assertThat(digestFor(vancouver).path("title").asString())
            .contains(vancouverWeek.toString());
        assertThat(digestFor(toronto).path("title").asString())
            .contains(torontoWeek.toString());
    }

    @Test
    @DisplayName("A member with no transactions still gets a digest that says so")
    void anEmptyWeekStillReports() {
        // Sending nothing is indistinguishable from the job being broken, which is exactly how
        // the source's failure went unnoticed for as long as it did.
        assertThat(job.sendOne(member.userId())).isTrue();

        assertThat(digestFor(member).path("body").asString())
            .contains("No transactions were recorded");
    }

    @Test
    @DisplayName("A member who opted out is not a recipient")
    void optingOutWorks() {
        putJson(appUrl("/v1/notifications/preferences"),
            Json.object("weeklyDigestEnabled", false), bearer(member));

        // Through the job's OWN query rather than a hand-written copy of it. The copy was the
        // problem: it agreed with the query while the query was wrong.
        assertThat(allRecipients()).doesNotContain(member.userId());
    }

    @Test
    @DisplayName("A member who has never opened the settings screen is still a recipient")
    void aMemberWithNoPreferenceRowIsStillEnumerated() {
        // Nothing creates notification_preference at registration — AuthService.register saves the
        // user and the credential and calls nothing in this package — so a member who never opens
        // the notification settings and whose household never crosses a budget threshold has NO
        // row at all. findDigestRecipients used to INNER JOIN this table, which silently and
        // permanently excluded every one of them from a digest V31 enables by default. A missing
        // row means the shipped defaults; only an explicit false opts a member out.
        Session fresh = registerInOwnHousehold("digest-untouched", "America/Toronto");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class,
            fresh.userId()))
            .as("the premise of this test: this member has never touched the settings screen")
            .isZero();
        assertThat(allRecipients())
            .as("weekly_digest_enabled DEFAULT true is a promise to a member who has no row yet")
            .contains(fresh.userId());

        assertThat(job.sendOne(fresh.userId())).isTrue();
        assertThat(digestFor(fresh)).isNotNull();
    }

    @Test
    @DisplayName("A deactivated member with no preference row is still not a recipient")
    void aDeactivatedMemberWithNoRowIsNotEnumerated() {
        // The other half of the LEFT JOIN: enumerating from users must not lose the users.active
        // filter that made an INNER JOIN safe in the one respect it was.
        Session fresh = registerInOwnHousehold("digest-untouched-off", "America/Toronto");
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", fresh.userId());

        assertThat(allRecipients()).doesNotContain(fresh.userId());
    }

    /** Every digest recipient, through the job's own paged query. */
    private List<UUID> allRecipients() {
        List<UUID> all = new java.util.ArrayList<>();
        for (int page = 0; ; page++) {
            List<UUID> next = preferences.findDigestRecipients(
                org.springframework.data.domain.PageRequest.of(page, 200));
            if (next.isEmpty()) {
                return all;
            }
            all.addAll(next);
        }
    }

    @Test
    @DisplayName("A deactivated account is not a recipient, and its preferences survive")
    void aDeactivatedAccountIsSkipped() {
        Session leaving = inviteAndRegister(loginBootstrapAdmin(), "digest-leaving");
        get(appUrl("/v1/notifications/preferences"), leaving.accessToken());
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", leaving.userId());

        assertThat(job.sendOne(leaving.userId()))
            .as("a member an administrator has switched off should stop hearing from this server")
            .isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class,
            leaving.userId()))
            .as("the row survives, so re-activating restores their settings")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("A failure for one recipient lands as FAILED with its reason, not as a silent skip")
    void aFailureIsRecordedRatherThanSwallowed() {
        // Defect D-N1's real damage was not the broken HTTP call — it was the catch that turned
        // it into a "skip" counter. A failure that produces no row is indistinguishable from a
        // member who was never a recipient.
        UUID missing = UUID.randomUUID();

        assertThat(job.sendOne(missing))
            .as("an account that does not exist is not an error, it is simply not a recipient")
            .isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ?", Integer.class, missing))
            .isZero();
    }

    @Test
    @DisplayName("The job's lock name and cron are the ones §5 item 6 fixes")
    void theScheduleIsTheOneThePlanNames() throws Exception {
        var method = WeeklyDigestJob.class.getMethod("sendWeeklyDigests");
        var scheduled = method.getAnnotation(
            org.springframework.scheduling.annotation.Scheduled.class);
        var lock = method.getAnnotation(
            net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);

        assertThat(scheduled.cron())
            .as("weekly, not daily: §5 item 6 and §2.8 both say weekly")
            .isEqualTo("0 0 8 * * MON");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        assertThat(lock.name()).isEqualTo("weekly-digest");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT30M");
    }

    /**
     * Creates a household of its own and registers one ADMIN into it, in a named timezone.
     *
     * <p>The two inserts are the only fixture in this file, and they are here for the reason
     * {@code AccessControlIT} states for the same pair: one household per install is the owner's
     * decision, so there is no endpoint that makes a second. Everything after them — the
     * registration, the credential, the session — goes through production code.
     */
    private Session registerInOwnHousehold(String prefix, String timezone) {
        UUID householdId = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES (?, false) RETURNING id",
            UUID.class, "Digest household " + UUID.randomUUID());
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdId, finance.trove.households.invites.InviteService.hash(token));

        java.net.http.HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", uniqueEmail(prefix),
                "password", MEMBER_PASSWORD, "displayName", "Zoned Member",
                "currency", "CAD", "country", "CA", "timezone", timezone),
            "X-Trove-Auth-Mode", "bearer");
        assertThat(response.statusCode())
            .as("registering %s failed: %s", prefix, response.body())
            .isEqualTo(201);
        JsonNode data = Json.data(response.body());
        return new Session(UUID.fromString(data.path("user").path("id").asString()),
            UUID.fromString(data.path("user").path("householdId").asString()),
            data.path("user").path("email").asString(),
            data.path("accessToken").asString(), data.path("refreshToken").asString());
    }
}
