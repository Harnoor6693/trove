package finance.trove.households;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.ImportSupport;
import finance.trove.app.support.CrossTenantAssertions;
import finance.trove.app.support.Json;
import finance.trove.auth.totp.TotpGenerator;
import finance.trove.households.invites.InviteService;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * §9 risk 2 — household isolation, and the harness later collapse steps extend.
 *
 * <p>The pre-collapse code had one documented IDOR (G1). The structural cause was that any
 * repository call could omit the scoping column, and that survives a one-endpoint fix, so the
 * defence is a rule plus a standing regression gate: {@code ArchitectureTest} bans unscoped
 * finders on a scoped repository, and this suite proves the endpoints behave.
 *
 * <p><strong>Both directions are asserted.</strong> Isolation between households is the obvious
 * one. Full mutual visibility <em>within</em> a household is the one that is easy to get wrong and
 * invisible on a single-member install: the owner's decision is one shared ledger per household,
 * so an implementation that scoped by {@code user_id} instead of {@code household_id} would pass
 * every isolation test and hide a partner's data. §1.5 corrects the original per-user assertion
 * for exactly this reason.
 *
 * <p>A second household is created here with two SQL statements — the install is
 * one-household-per-install by design, so there is no endpoint that makes one — and then joined
 * through the real registration endpoint, so household B's members are as genuine as A's.
 */
class AccessControlIT extends ImportSupport {

    private final SecureRandom random = new SecureRandom();

    private CrossTenantAssertions harness;
    private CrossTenantAssertions.Tenant householdAdminA;
    private CrossTenantAssertions.Tenant householdMemberA;
    private CrossTenantAssertions.Tenant householdAdminB;

    @BeforeEach
    void setUpTwoHouseholds() {
        harness = new CrossTenantAssertions((method, path, token, body) ->
            token == null
                ? exchange(method, appUrl(path), body)
                : exchange(method, appUrl(path), body, "Authorization", "Bearer " + token));

        Session adminA = loginBootstrapAdmin();
        Session memberA = inviteAndRegister(adminA, "tenant-a-member");
        Session adminB = createSecondHousehold();

        householdAdminA = tenant("household A admin", adminA);
        householdMemberA = tenant("household A member", memberA);
        householdAdminB = tenant("household B admin", adminB);

        assertThat(householdAdminB.householdId())
            .as("the whole suite is meaningless if both accounts landed in one household")
            .isNotEqualTo(householdAdminA.householdId());
    }

    private static CrossTenantAssertions.Tenant tenant(String label, Session session) {
        return new CrossTenantAssertions.Tenant(label, session.householdId(), session.userId(),
            session.email(), session.accessToken());
    }

    /**
     * Creates household B and an ADMIN invite into it directly, then redeems that invite through
     * the real endpoint.
     *
     * <p>The two inserts are the only fixture in this file, and they exist because the application
     * deliberately offers no way to create a second household: one per install is the owner's
     * decision. Everything after them — the registration, the credential, the session — goes
     * through production code.
     */
    private Session createSecondHousehold() {
        UUID householdB = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES ('Household B', false) "
                + "RETURNING id", UUID.class);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdB, InviteService.hash(token));

        return registerWithInvite(token, uniqueEmail("tenant-b-admin"), MEMBER_PASSWORD);
    }

    /** Household A's by-id surface as of collapse step 3. Later steps add their own. */
    private List<CrossTenantAssertions.Probe> householdAProbes() {
        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        UUID memberId = householdMemberA.userId();

        return List.of(
            CrossTenantAssertions.Probe.mutate(
                "revoke household A's invite", "DELETE", "/v1/admin/invites/" + inviteId, null),
            CrossTenantAssertions.Probe.mutate(
                "unlock a household A member", "POST",
                "/v1/admin/users/" + memberId + "/unlock", null),
            CrossTenantAssertions.Probe.mutate(
                "clear a household A member's second factor", "DELETE",
                "/v1/admin/users/" + memberId + "/totp", null),
            // Last, deliberately: assertOwnerAccessGranted runs the list in order and this one
            // really does remove the member, so anything that needs them must come before it.
            CrossTenantAssertions.Probe.mutate(
                "delete a household A member", "DELETE", "/v1/admin/users/" + memberId, null));
    }

    /**
     * Every administrative route, as seen by an ordinary MEMBER of the household that owns them.
     *
     * <p>The refusal here is on role, before any id is read, so it is 403 rather than the 404 a
     * foreign household gets: the rows genuinely are this caller's household's, and pretending
     * they do not exist would be a lie that helps nobody. What must not happen is a 200.
     */
    private List<CrossTenantAssertions.Probe> memberProbes(String inviteId) {
        UUID memberId = householdMemberA.userId();
        return List.of(
            CrossTenantAssertions.Probe.adminOnly(
                "list the household's members", "GET", "/v1/admin/users", null),
            CrossTenantAssertions.Probe.adminOnly(
                "delete a household member", "DELETE", "/v1/admin/users/" + memberId, null),
            CrossTenantAssertions.Probe.adminOnly(
                "unlock a household member", "POST",
                "/v1/admin/users/" + memberId + "/unlock", null),
            CrossTenantAssertions.Probe.adminOnly(
                "clear a household member's second factor", "DELETE",
                "/v1/admin/users/" + memberId + "/totp", null),
            CrossTenantAssertions.Probe.adminOnly(
                "revoke an invite", "DELETE", "/v1/admin/invites/" + inviteId, null),
            CrossTenantAssertions.Probe.adminOnly(
                "read household settings", "GET", "/v1/admin/settings", null),
            CrossTenantAssertions.Probe.adminOnly(
                "open self-serve registration", "PUT", "/v1/admin/settings",
                Json.object("registrationOpen", true)),
            // Collapse step 4. Not under /v1/admin, so the chain matcher that covers the rest of
            // this list does not cover it; the guard is AuditController's @PreAuthorize plus an
            // explicit matcher for this one path, and this probe is what proves both are there.
            // The log names every administrative action in the household, so a member who could
            // read it could watch the admin.
            CrossTenantAssertions.Probe.adminOnly(
                "read the household's audit log", "GET", "/v1/audit", null));
    }

    @Test
    @DisplayName("An ordinary MEMBER is refused on every admin route of their own household")
    void membersCannotAdministerTheirOwnHousehold() {
        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        harness.assertRoleRefusedWithinHousehold(householdAdminA, householdMemberA,
            memberProbes(inviteId));

        assertThat(Json.data(get(appUrl("/v1/admin/settings"),
            householdAdminA.accessToken()).body()).path("registrationOpen").asBoolean())
            .as("the refused PUT must not have taken effect")
            .isFalse();
        assertThat(get(appUrl("/v1/auth/me"), householdMemberA.accessToken()).statusCode())
            .as("the member's own session still works — the refusal is about the route, not the "
                + "token")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A refused clear-TOTP leaves the member's second factor intact")
    void aRefusedTotpClearIsANoOp() {
        // Without this the 404 and the 403 above prove only that a status code came back. A
        // route that removed the row and then failed to answer would look identical.
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "totp-noop");
        enrolTotp(member);
        assertThat(totpRowsFor(member.userId())).isEqualTo(1);

        assertThat(delete(appUrl("/v1/admin/users/" + member.userId() + "/totp"), null,
            "Authorization", "Bearer " + householdAdminB.accessToken()).statusCode())
            .as("a foreign admin: the member is not in their household")
            .isEqualTo(404);
        assertThat(totpRowsFor(member.userId()))
            .as("the 404 must be a refusal, not a late failure after the row was already gone")
            .isEqualTo(1);

        assertThat(delete(appUrl("/v1/admin/users/" + member.userId() + "/totp"), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode())
            .as("the member is not an admin, even of their own second factor on this route")
            .isEqualTo(403);
        assertThat(totpRowsFor(member.userId())).isEqualTo(1);

        assertThat(delete(appUrl("/v1/admin/users/" + member.userId() + "/totp"), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode())
            .as("their own household's admin is the lost-phone escape hatch and does succeed")
            .isEqualTo(200);
        assertThat(totpRowsFor(member.userId())).isZero();
    }

    @Test
    @DisplayName("An admin cannot use the admin routes on their own security state")
    void anAdminCannotAdministerTheirOwnSecondFactor() {
        // The escape hatch is for somebody else — §1.5 states it as "admin can clear a household
        // member's TOTP (actor != subject)". Aimed at the caller's own account it is a way round
        // the step-up: DELETE /v1/auth/totp demands the password AND a live code before it will
        // remove a factor, precisely because holding a session is not the same as knowing the
        // password. An admin who could clear their own factor from here would need neither, so a
        // borrowed unlocked laptop or an exfiltrated bearer token would be enough to permanently
        // strip the strongest control on the account.
        Session adminB = loginBearer(householdAdminB.email(), MEMBER_PASSWORD);
        enrolTotp(adminB);
        assertThat(totpRowsFor(adminB.userId())).isEqualTo(1);

        HttpResponse<String> clearedSelf = delete(
            appUrl("/v1/admin/users/" + adminB.userId() + "/totp"), null,
            "Authorization", "Bearer " + adminB.accessToken());
        assertThat(clearedSelf.statusCode()).as(clearedSelf.body()).isEqualTo(403);
        assertThat(Json.errorCode(clearedSelf.body()))
            .as("403 step-up-required, not 401: the caller IS authenticated, and a 401 would send "
                + "every client interceptor off to refresh a perfectly good session")
            .isEqualTo("step-up-required");
        assertThat(totpRowsFor(adminB.userId()))
            .as("the refusal is a refusal, not a late failure after the row was already gone")
            .isEqualTo(1);

        HttpResponse<String> unlockedSelf = postJson(
            appUrl("/v1/admin/users/" + adminB.userId() + "/unlock"), null,
            "Authorization", "Bearer " + adminB.accessToken());
        assertThat(unlockedSelf.statusCode())
            .as("same shape, same answer, so 'an admin route never acts on the admin's own "
                + "security state' is one rule rather than a list of exceptions")
            .isEqualTo(403);
        assertThat(Json.errorCode(unlockedSelf.body())).isEqualTo("step-up-required");

        // And the escape hatch that does exist still works: another admin of the same household.
        Session coAdmin = registerWithInvite(
            Json.data(postJson(appUrl("/v1/admin/invites"), Json.object("role", "ADMIN"),
                "Authorization", "Bearer " + adminB.accessToken()).body())
                .path("token").asString(),
            uniqueEmail("tenant-b-clearer"), MEMBER_PASSWORD);
        assertThat(delete(appUrl("/v1/admin/users/" + adminB.userId() + "/totp"), null,
            "Authorization", "Bearer " + coAdmin.accessToken()).statusCode())
            .as("actor != subject is exactly the case the route is for")
            .isEqualTo(200);
        assertThat(totpRowsFor(adminB.userId())).isZero();
    }

    @Test
    @DisplayName("Two simultaneous deletes cannot empty a household of administrators")
    void theLastAdminGuardHoldsUnderConcurrency() throws Exception {
        // Counting the admins and then deleting is a check-then-act. With no lock and no shared
        // transaction, two DELETEs against a household's last two administrators both read
        // count == 2, both walk past the "<= 1" guard, and both commit — and a household with no
        // administrator has no way back in through the API at all: registration is closed,
        // opening it is an admin route, and so is minting an invite.
        Session adminB = loginBearer(householdAdminB.email(), MEMBER_PASSWORD);
        Session coAdmin = registerWithInvite(
            Json.data(postJson(appUrl("/v1/admin/invites"), Json.object("role", "ADMIN"),
                "Authorization", "Bearer " + adminB.accessToken()).body())
                .path("token").asString(),
            uniqueEmail("tenant-b-race"), MEMBER_PASSWORD);
        assertThat(adminsIn(householdAdminB.householdId())).isEqualTo(2);

        String bearer = adminB.accessToken();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = List.of(
                pool.submit(() -> deleteRace(ready, go, bearer, coAdmin.userId())),
                pool.submit(() -> deleteRace(ready, go, bearer, adminB.userId())));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                results.get(1).get(60, TimeUnit.SECONDS));
            assertThat(statuses)
                .as("one of them must lose: %s", statuses)
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(adminsIn(householdAdminB.householdId()))
            .as("whichever one lost, the household still has somebody who can administer it")
            .isEqualTo(1);
    }

    /**
     * One racer: announce readiness, wait for the flag, then delete.
     *
     * <p>A warm-up call first, so that the loser's own authentication — which happens before it
     * blocks on the household lock — is not the thing that pays for the first connection.
     */
    private int deleteRace(CountDownLatch ready, CountDownLatch go, String bearer, UUID target)
        throws Exception {
        get(appUrl("/v1/auth/me"), bearer);
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        return delete(appUrl("/v1/admin/users/" + target), null,
            "Authorization", "Bearer " + bearer).statusCode();
    }

    /**
     * How many administrators household {@code householdId} still has who can actually sign in.
     *
     * <p>{@code is_active} is in the predicate because collapse step 18 made the delete SOFT: a
     * departed administrator's row survives the seven-day restore window, so a bare
     * {@code role = 'ADMIN'} count would include somebody who has already left and every
     * last-admin assertion below would be counting the wrong thing.
     */
    private int adminsIn(UUID householdId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE household_id = ? AND role = 'ADMIN' AND is_active",
            Integer.class, householdId);
    }

    private int totpRowsFor(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM totp_credential WHERE user_id = ? AND enabled",
            Integer.class, userId);
    }

    /** Enrols {@code member} in TOTP through the real setup/confirm endpoints. */
    private void enrolTotp(Session member) {
        TotpGenerator generator = new TotpGenerator();
        String secret = Json.data(postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken()).body())
            .path("secret").asString();
        HttpResponse<String> confirmed = postJson(appUrl("/v1/auth/totp/confirm"),
            Json.object("code",
                generator.codeAt(secret, generator.timeStep(Instant.now(), 30), 6)),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("The member listing is the household's own, and the last ADMIN cannot be deleted")
    void adminUserRoutesAreScopedAndKeepAnAdmin() {
        List<String> aIds = Json.elements(Json.data(
                get(appUrl("/v1/admin/users"), householdAdminA.accessToken()).body())).stream()
            .map(node -> node.path("id").asString())
            .toList();
        assertThat(aIds)
            .contains(householdAdminA.userId().toString(), householdMemberA.userId().toString())
            .doesNotContain(householdAdminB.userId().toString());

        // The last-ADMIN guard is exercised on household B, which this test method's setUp just
        // created with exactly one administrator. Household A is the install's original household
        // and shares its bootstrap admin with every other IT in the suite; deleting that account
        // would take the whole run down with it, and FirstRunBootstrap does not put it back.
        assertThat(delete(appUrl("/v1/admin/users/" + householdAdminB.userId()), null,
            "Authorization", "Bearer " + householdAdminB.accessToken()).statusCode())
            .as("a household whose only administrator deleted themselves has no way back in: "
                + "registration is closed, invites are an admin route, and so is the flag that "
                + "would open registration")
            .isEqualTo(409);
        assertThat(get(appUrl("/v1/auth/me"), householdAdminB.accessToken()).statusCode())
            .as("the refused delete left the account alone")
            .isEqualTo(200);

        Session coAdmin = registerWithInvite(
            Json.data(postJson(appUrl("/v1/admin/invites"), Json.object("role", "ADMIN"),
                "Authorization", "Bearer " + householdAdminB.accessToken()).body())
                .path("token").asString(),
            uniqueEmail("tenant-b-coadmin"), MEMBER_PASSWORD);

        assertThat(delete(appUrl("/v1/admin/users/" + coAdmin.userId()), null,
            "Authorization", "Bearer " + householdAdminB.accessToken()).statusCode())
            .as("with a second administrator in the household the guard steps aside")
            .isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/me"), coAdmin.accessToken()).statusCode())
            .as("the deleted account's sessions are revoked, so its very next call is refused "
                + "even though collapse step 18 leaves the row in place for the restore window")
            .isEqualTo(401);
        // Collapse step 18 changed this from "the row is gone" to "the row is inactive", and the
        // assertion is deliberately stronger rather than weaker: the member MUST still be listed,
        // because GET /v1/admin/users is where the administrator who has to decide whether to
        // restore them looks, and `active` MUST be false, because that flag is what every
        // liveness check in the tree reads.
        assertThat(Json.elements(Json.data(
                get(appUrl("/v1/admin/users"), householdAdminB.accessToken()).body())))
            .anySatisfy(node -> {
                assertThat(node.path("id").asString()).isEqualTo(coAdmin.userId().toString());
                assertThat(node.path("active").asBoolean()).isFalse();
            });
    }

    @Test
    @DisplayName("Only the install's original household can open self-serve registration")
    void onlyTheInstallHouseholdCanOpenRegistration() {
        // The documented limitation, asserted rather than left implicit: a bare POST
        // /v1/auth/register names no household, so the target cannot depend on which row happens
        // to be open. It is always the install's original household (InstallHousehold).
        try {
            assertThat(putJson(appUrl("/v1/admin/settings"),
                Json.object("registrationOpen", true),
                "Authorization", "Bearer " + householdAdminB.accessToken()).statusCode())
                .isEqualTo(200);

            assertThat(Json.data(get(appUrl("/v1/auth/providers")).body())
                .path("registrationOpen").asBoolean())
                .as("the providers response reports the install household, not household B")
                .isFalse();
            assertThat(postJson(appUrl("/v1/auth/register"),
                Json.object("email", uniqueEmail("b-open"), "password", MEMBER_PASSWORD,
                    "displayName", "B Open")).statusCode())
                .as("household B's flag admits nobody; the only way into B is an invite")
                .isEqualTo(403);
        } finally {
            putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", false),
                "Authorization", "Bearer " + householdAdminB.accessToken());
        }
    }

    @Test
    @DisplayName("Household B's admin cannot reach any of household A's rows by id")
    void householdsAreIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("A foreign admin gets 404, not 403 — a 403 confirms the row exists")
    void refusalsDoNotConfirmExistence() {
        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        HttpResponse<String> response = delete(appUrl("/v1/admin/invites/" + inviteId), null,
            "Authorization", "Bearer " + householdAdminB.accessToken());

        assertThat(response.statusCode())
            .as("over enough probes a 403 is an enumeration oracle for another household's row "
                + "ids; a scoped finder that returns empty produces 404 by construction")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("Every probed route requires a session at all")
    void anonymousCallersAreRefused() {
        harness.assertAnonymousRefused(householdAProbes());
    }

    @Test
    @DisplayName("Two members of ONE household see the same rows — the shared-ledger requirement")
    void householdMatesShareEverything() {
        Session adminA = loginBootstrapAdmin();
        Session secondAdmin = registerWithInvite(
            Json.data(postJson(appUrl("/v1/admin/invites"), Json.object("role", "ADMIN"),
                "Authorization", "Bearer " + adminA.accessToken()).body())
                .path("token").asString(),
            uniqueEmail("tenant-a-coadmin"), MEMBER_PASSWORD);

        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + adminA.accessToken()).body()).path("id").asString();

        HttpResponse<String> listed = get(appUrl("/v1/admin/invites"), secondAdmin.accessToken());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(listed.body())))
            .as("an implementation scoped by user_id instead of household_id would pass every "
                + "isolation test above and hide a partner's rows here")
            .anySatisfy(invite -> assertThat(invite.path("id").asString()).isEqualTo(inviteId));

        harness.assertHouseholdMatesShareVisibility(
            tenant("household A admin", adminA),
            tenant("household A co-admin", secondAdmin),
            List.of(
                CrossTenantAssertions.Probe.get("list household invites", "/v1/admin/invites",
                    inviteId),
                CrossTenantAssertions.Probe.get("list household members", "/v1/admin/users",
                    secondAdmin.userId().toString())));
    }

    @Test
    @DisplayName("Household B cannot see household A's invites in its own listing")
    void listingsAreHouseholdScoped() {
        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        HttpResponse<String> bList =
            get(appUrl("/v1/admin/invites"), householdAdminB.accessToken());

        assertThat(bList.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(bList.body())))
            .noneSatisfy(invite ->
                assertThat(invite.path("id").asString()).isEqualTo(inviteId));
        assertThat(bList.body()).doesNotContain(householdAdminA.email());
    }

    @Test
    @DisplayName("Household B's audit log contains none of household A's requests")
    void theAuditLogIsHouseholdScoped() {
        // A mutation by A's admin, whose audit row therefore carries A's household id.
        HttpResponse<String> mutation = postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + householdAdminA.accessToken());
        assertThat(mutation.statusCode()).isEqualTo(201);
        String requestId = mutation.headers().firstValue("X-Request-Id").orElseThrow();

        // The write is asynchronous by design (§4.2: after the response, never before it).
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && Json.elements(Json.data(
            get(appUrl("/v1/audit?limit=200"), householdAdminA.accessToken()).body())).isEmpty()) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for the audit row", e);
            }
        }

        HttpResponse<String> bLog = get(appUrl("/v1/audit?limit=200"), householdAdminB.accessToken());
        assertThat(bLog.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(bLog.body())))
            .as("the route has no id to tamper with, so the whole of its tenancy story is that "
                + "the query is scoped by the principal's household — and the query service has "
                + "no method that reads the table without one")
            .noneSatisfy(row ->
                assertThat(row.path("requestId").asString()).isEqualTo(requestId));
        assertThat(bLog.body())
            .as("nor may a row echo the other household's member ids")
            .doesNotContain(householdAdminA.userId().toString());
    }

    @Test
    @DisplayName("Household settings are each household's own")
    void settingsAreHouseholdScoped() {
        try {
            putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", true),
                "Authorization", "Bearer " + householdAdminB.accessToken());

            assertThat(Json.data(get(appUrl("/v1/admin/settings"),
                householdAdminA.accessToken()).body()).path("registrationOpen").asBoolean())
                .as("household B opening its own registration must not open household A's")
                .isFalse();
        } finally {
            putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", false),
                "Authorization", "Bearer " + householdAdminB.accessToken());
        }
    }

    @Test
    @DisplayName("An admin of one household is not an admin of another")
    void adminIsNotATenancyBypass() {
        assertThat(Json.data(get(appUrl("/v1/auth/me"), householdAdminB.accessToken()).body())
            .path("householdId").asString())
            .isEqualTo(householdAdminB.householdId().toString());

        assertThat(postJson(appUrl("/v1/auth/password/reset-request"),
            Json.object("email", householdAdminA.email()),
            "Authorization", "Bearer " + householdAdminB.accessToken()).statusCode())
            .as("an admin of household B minting a reset link for a member of household A would "
                + "be an account takeover dressed as an administrative action; the lookup is "
                + "scoped to the caller's own household, so the address simply does not exist")
            .isEqualTo(404);

        assertThat(postJson(appUrl("/v1/auth/password/reset-request"),
            Json.object("email", householdMemberA.email()),
            "Authorization", "Bearer " + householdAdminA.accessToken()).statusCode())
            .as("household A's own admin can still reset its own member")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A member of one household cannot read another member's security events")
    void securityEventsAreNotCrossReadable() {
        HttpResponse<String> aEvents =
            get(appUrl("/v1/me/security-events"), householdMemberA.accessToken());
        HttpResponse<String> bEvents =
            get(appUrl("/v1/me/security-events"), householdAdminB.accessToken());

        assertThat(aEvents.statusCode()).isEqualTo(200);
        assertThat(bEvents.statusCode()).isEqualTo(200);
        assertThat(bEvents.body()).doesNotContain(householdMemberA.userId().toString());
    }

    // ------------------------------------------------------------ collapse step 5 (categories)

    /**
     * Household A's category and rule by-id routes — collapse step 5's addition to this suite's
     * probe matrix ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.5: "Add a Probe to
     * AccessControlIT / CrossTenantAssertions for EVERY new by-id route"). A fresh category and
     * rule are created per call, deliberately separate from {@link #householdAProbes()} — that
     * list's probe ORDER is load-bearing (its last entry deletes {@code householdMemberA}), and
     * mixing an unrelated resource into it would make that fact easy to break by accident.
     */
    private List<CrossTenantAssertions.Probe> categoryAndRuleProbes() {
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "AccessControlIT Probe Category"),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String coffeeShopsId = systemCategoryId(householdAdminA.accessToken(), "food.coffee");
        String ruleId = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", "AccessControlIT Probe Merchant " + UUID.randomUUID(),
                "matchType", "EXACT", "categoryId", coffeeShopsId),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.mutate("rename household A's category", "PUT",
                "/v1/categories/" + categoryId,
                Json.object("name", "AccessControlIT Probe Category Renamed")),
            CrossTenantAssertions.Probe.mutate("update household A's rule", "PUT",
                "/v1/categories/rules/" + ruleId,
                Json.object("merchant", "Renamed Probe Merchant", "matchType", "EXACT",
                    "categoryId", coffeeShopsId)),
            // Deletes last, deliberately: assertOwnerAccessGranted runs this list in order and
            // these two actually remove the rows, so anything that still needs them must precede.
            CrossTenantAssertions.Probe.mutate("delete household A's rule", "DELETE",
                "/v1/categories/rules/" + ruleId, null),
            CrossTenantAssertions.Probe.mutate("delete household A's category", "DELETE",
                "/v1/categories/" + categoryId, null));
    }

    @Test
    @DisplayName("Household B cannot reach household A's categories or rules by id")
    void categoriesAndRulesAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = categoryAndRuleProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Every category and rule route requires a session")
    void categoryAndRuleRoutesRequireASession() {
        harness.assertAnonymousRefused(categoryAndRuleProbes());
    }

    @Test
    @DisplayName("Household mates share visibility of categories and rules — no role restriction")
    void categoriesAndRulesShareWithinHousehold() {
        // Unlike the admin routes above, ANY household member may create/edit a category or
        // rule (§4.5: "household rows are fully editable by any member") — there is no
        // assertRoleRefusedWithinHousehold probe for this group, only shared visibility.
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Shared Category Probe " + UUID.randomUUID()),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String coffeeShopsId = systemCategoryId(householdAdminA.accessToken(), "food.coffee");
        String ruleId = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", "Shared Rule Probe Merchant " + UUID.randomUUID(),
                "matchType", "EXACT", "categoryId", coffeeShopsId),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA,
            List.of(
                CrossTenantAssertions.Probe.get("list categories", "/v1/categories", categoryId),
                CrossTenantAssertions.Probe.get("list rules", "/v1/categories/rules", ruleId)));
    }

    private String systemCategoryId(String accessToken, String slug) {
        return Json.elements(Json.data(get(appUrl("/v1/categories"), accessToken).body())).stream()
            .filter(c -> slug.equals(c.path("slug").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(slug + " is missing from the seed"))
            .path("id").asString();
    }

    /**
     * The AI settings of collapse step 12, which are the one resource in this application that is
     * scoped to a <strong>user</strong> rather than to a household.
     *
     * <p>It gets its own assertion rather than a {@code Probe} because the harness's three
     * cross-tenant assertions all encode household scoping, and one of them —
     * {@code assertHouseholdMatesShareVisibility} — asserts the exact opposite of what must be
     * true here. Under owner decision 9 each member configures and pays for their own AI endpoint,
     * so a household mate seeing another member's endpoint, API key or privacy tier is a leak,
     * where a household mate seeing another member's transactions is a requirement. The
     * distinction is invisible on a single-member install, which is why it is asserted at all.
     */
    @Test
    @DisplayName("AI settings are per-user: a household mate can neither read nor write yours")
    void aiSettingsAreNotSharedInsideAHousehold() {
        String endpoint = "https://203.0.113.10/v1";
        String apiKey = "sk-household-mate-must-not-see-this";
        String body = Json.object("enabled", true, "endpointUrl", endpoint, "apiKey", apiKey,
            "model", "llama3", "allowPrivateEndpoint", false, "privacyTier", "LOCAL_ONLY");

        assertThat(putJson(appUrl("/v1/ai/settings"), body,
            "Authorization", "Bearer " + householdAdminA.accessToken()).statusCode())
            .isEqualTo(200);

        HttpResponse<String> asMate =
            get(appUrl("/v1/ai/settings"), householdMemberA.accessToken());
        assertThat(asMate.statusCode())
            .as("the mate reads their OWN row, which is the defaults — not a 403, not a 404")
            .isEqualTo(200);
        assertThat(asMate.body())
            .as("a household mate must see neither the endpoint nor any trace of the key")
            .doesNotContain(apiKey)
            .doesNotContain("203.0.113.10");
        assertThat(Json.data(asMate.body()).path("enabled").asBoolean()).isFalse();

        HttpResponse<String> asForeigner =
            get(appUrl("/v1/ai/settings"), householdAdminB.accessToken());
        assertThat(asForeigner.body()).doesNotContain(apiKey).doesNotContain("203.0.113.10");

        // A mate's own write is a write to their own row and leaves the admin's alone.
        assertThat(putJson(appUrl("/v1/ai/settings"),
            Json.object("enabled", false, "endpointUrl", null, "model", null,
                "allowPrivateEndpoint", false, "privacyTier", "CLOUD_OK"),
            "Authorization", "Bearer " + householdMemberA.accessToken()).statusCode())
            .isEqualTo(200);
        assertThat(Json.data(get(appUrl("/v1/ai/settings"), householdAdminA.accessToken()).body())
            .path("endpointUrl").asString())
            .as("nothing a mate does may reach another member's row")
            .isEqualTo(endpoint);

        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("read AI settings", "/v1/ai/settings"),
            CrossTenantAssertions.Probe.get("list AI models", "/v1/ai/models"),
            CrossTenantAssertions.Probe.mutate("write AI settings", "PUT", "/v1/ai/settings", body),
            CrossTenantAssertions.Probe.mutate("test the AI endpoint", "POST",
                "/v1/ai/settings/test", null),
            CrossTenantAssertions.Probe.mutate("forget the AI API key", "DELETE",
                "/v1/ai/settings/api-key", null)));
    }

    // ------------------------------------------------------- collapse step 6 (accounts, ledger)

    /**
     * Household A's step-6 by-id surface: every route this step adds that takes an id.
     *
     * <p>A new by-id route without a probe here is the whole class of defect §9 risk 2 is about,
     * and the reason the harness exists at all: the structural cause of the G1 IDOR — a repository
     * call that omits the scoping column — is not fixed by fixing one endpoint, and every later
     * step is a fresh chance to reintroduce it.
     *
     * <p>The two DELETEs are last and in that order, deliberately: {@code assertOwnerAccessGranted}
     * runs the list in sequence and both of them really do remove the row, so anything that needs
     * the account or the transaction must come before them. Deleting the account also cascades to
     * its transactions, so the transaction's own delete comes first.
     */
    private List<CrossTenantAssertions.Probe> householdAStepSixProbes() {
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Probe account " + UUID.randomUUID());
        String transactionId = createManualTransaction(sessionOf(householdAdminA), accountId,
            "12.34", "2026-09-04", "DEBIT", "Probe merchant").path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.get(
                "read household A's account", "/v1/accounts/" + accountId, accountId),
            CrossTenantAssertions.Probe.get("read household A's account sync history",
                "/v1/accounts/" + accountId + "/sync-logs"),
            CrossTenantAssertions.Probe.get(
                "read household A's transaction", "/v1/transactions/" + transactionId,
                transactionId),
            CrossTenantAssertions.Probe.mutate("rename household A's account", "PUT",
                "/v1/accounts/" + accountId,
                Json.object("displayName", "Renamed by a probe", "accountType", "OTHER")),
            CrossTenantAssertions.Probe.mutate("enrich household A's transaction", "PATCH",
                "/v1/transactions/" + transactionId, Json.object("notes", "Touched by a probe")),
            // The account is MANUAL, so its owner is answered 409 (no source is registered for a
            // manual account) rather than 200 — which is why this probe declares that as the
            // owner's outcome. What matters for tenancy is that a FOREIGN caller gets 404, and
            // that it gets it before the account's state is ever consulted.
            new CrossTenantAssertions.Probe("sync household A's account", "POST",
                "/v1/accounts/" + accountId + "/sync", null, Set.of(404), Set.of(409), null),
            CrossTenantAssertions.Probe.mutate("delete household A's transaction", "DELETE",
                "/v1/transactions/" + transactionId, null),
            CrossTenantAssertions.Probe.mutate("delete household A's account", "DELETE",
                "/v1/accounts/" + accountId, null));
    }

    /** The read-only half, for the shared-ledger assertion: two mates must see the same rows. */
    private List<CrossTenantAssertions.Probe> householdASharedLedgerProbes() {
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Shared probe account " + UUID.randomUUID());
        String transactionId = createManualTransaction(sessionOf(householdAdminA), accountId,
            "56.78", "2026-09-04", "DEBIT", "Shared probe merchant").path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.get("list accounts", "/v1/accounts", accountId),
            CrossTenantAssertions.Probe.get("read one account", "/v1/accounts/" + accountId,
                accountId),
            CrossTenantAssertions.Probe.get("read one transaction",
                "/v1/transactions/" + transactionId, transactionId),
            CrossTenantAssertions.Probe.get("read the dashboard", "/v1/dashboard/today"));
    }

    @Test
    @DisplayName("Household B cannot reach household A's accounts or transactions by id")
    void theLedgerIsHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepSixProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Every account and ledger route requires a session")
    void ledgerRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepSixProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list accounts", "/v1/accounts"),
            CrossTenantAssertions.Probe.get("list transactions", "/v1/transactions"),
            CrossTenantAssertions.Probe.get("read the dashboard", "/v1/dashboard/today"),
            CrossTenantAssertions.Probe.mutate("add an account", "POST", "/v1/accounts",
                Json.object("sourceKind", "MANUAL", "displayName", "Anonymous",
                    "accountType", "OTHER")),
            CrossTenantAssertions.Probe.mutate("record a transaction", "POST", "/v1/transactions",
                Json.object("accountId", UUID.randomUUID().toString(), "amount", "1.00",
                    "date", "2026-09-04"))));
    }

    @Test
    @DisplayName("Household mates share the ledger — accounts, transactions and the dashboard")
    void theLedgerIsSharedWithinTheHousehold() {
        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA,
            householdASharedLedgerProbes());
    }

    @Test
    @DisplayName("A foreign caller gets 404 on a ledger row, and the row is untouched")
    void aRefusedLedgerWriteIsANoOp() {
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Untouched " + UUID.randomUUID());
        String transactionId = createManualTransaction(sessionOf(householdAdminA), accountId,
            "99.99", "2026-09-04", "DEBIT", "Untouched merchant").path("id").asString();

        HttpResponse<String> refused = exchange("PATCH", appUrl("/v1/transactions/"
                + transactionId), Json.object("notes", "Written by another household"),
            "Authorization", "Bearer " + householdAdminB.accessToken());

        assertThat(refused.statusCode())
            .as("404 and not 403: a 403 confirms the row exists, which over enough probes is an "
                + "enumeration oracle for another household's ids")
            .isEqualTo(404);
        assertThat(Json.data(get(appUrl("/v1/transactions/" + transactionId),
            householdAdminA.accessToken()).body()).path("notes").isNull())
            .as("a refusal must change nothing")
            .isTrue();
    }

    // ------------------------------------------------------------- collapse step 9 (net worth)

    /**
     * Household A's net-worth by-id routes — collapse step 9's addition to this suite's probe
     * matrix ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 2: "Add a Probe to
     * AccessControlIT / CrossTenantAssertions for EVERY new by-id route"). A fresh asset and
     * liability are created per call, for the same reason {@code categoryAndRuleProbes} keeps
     * its own fixtures separate from {@link #householdAProbes()}: that list's order is
     * load-bearing and mixing an unrelated resource into it would make that easy to break by
     * accident.
     */
    private List<CrossTenantAssertions.Probe> netWorthProbes() {
        String assetId = Json.data(postJson(appUrl("/v1/networth/assets"),
            Json.object("name", "AccessControlIT Probe Asset", "assetType", "CASH", "value",
                "1.00", "currency", "CAD", "accountId", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String liabilityId = Json.data(postJson(appUrl("/v1/networth/liabilities"),
            Json.object("name", "AccessControlIT Probe Liability", "liabilityType",
                "CREDIT_CARD", "balance", "1.00", "interestRate", null, "currency", "CAD",
                "accountId", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.mutate("rename household A's asset", "PUT",
                "/v1/networth/assets/" + assetId,
                Json.object("name", "AccessControlIT Probe Asset Renamed", "assetType", "CASH",
                    "value", "2.00", "accountId", null)),
            CrossTenantAssertions.Probe.mutate("update household A's liability", "PUT",
                "/v1/networth/liabilities/" + liabilityId,
                Json.object("name", "AccessControlIT Probe Liability Renamed", "liabilityType",
                    "CREDIT_CARD", "balance", "2.00", "interestRate", null, "accountId", null)),
            // Deletes last, deliberately: assertOwnerAccessGranted runs this list in order and
            // these two actually remove the rows, so anything that still needs them must precede.
            CrossTenantAssertions.Probe.mutate("delete household A's asset", "DELETE",
                "/v1/networth/assets/" + assetId, null),
            CrossTenantAssertions.Probe.mutate("delete household A's liability", "DELETE",
                "/v1/networth/liabilities/" + liabilityId, null));
    }

    @Test
    @DisplayName("Household B cannot reach household A's assets or liabilities by id")
    void netWorthIsHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = netWorthProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Every net-worth route requires a session")
    void netWorthRoutesRequireASession() {
        harness.assertAnonymousRefused(netWorthProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list assets", "/v1/networth/assets"),
            CrossTenantAssertions.Probe.get("list liabilities", "/v1/networth/liabilities"),
            CrossTenantAssertions.Probe.get("read the net-worth series", "/v1/networth/series"),
            CrossTenantAssertions.Probe.mutate("add an asset", "POST", "/v1/networth/assets",
                Json.object("name", "Anonymous", "assetType", "CASH", "value", "1.00")),
            CrossTenantAssertions.Probe.mutate("add a liability", "POST",
                "/v1/networth/liabilities",
                Json.object("name", "Anonymous", "liabilityType", "CREDIT_CARD", "balance",
                    "1.00")),
            CrossTenantAssertions.Probe.mutate("take a snapshot", "POST",
                "/v1/networth/snapshot", null)));
    }

    @Test
    @DisplayName("Household mates share the net-worth ledger — assets and liabilities")
    void netWorthIsSharedWithinTheHousehold() {
        String assetId = Json.data(postJson(appUrl("/v1/networth/assets"),
            Json.object("name", "Shared Probe Asset " + UUID.randomUUID(), "assetType", "CASH",
                "value", "1.00", "currency", "CAD", "accountId", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String liabilityId = Json.data(postJson(appUrl("/v1/networth/liabilities"),
            Json.object("name", "Shared Probe Liability " + UUID.randomUUID(), "liabilityType",
                "CREDIT_CARD", "balance", "1.00", "interestRate", null, "currency", "CAD",
                "accountId", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("list assets", "/v1/networth/assets", assetId),
            CrossTenantAssertions.Probe.get("list liabilities", "/v1/networth/liabilities",
                liabilityId)));
    }

    // ------------------------------------------------------- collapse step 7 (file imports)

    /**
     * Household A's step-7 by-id surface: the three routes {@code /v1/imports} adds that take an
     * id.
     *
     * <p>{@code POST /v1/imports} itself takes no id and is covered by the anonymous check and by
     * {@code ImportLimitsIT}; these three are the ones a foreign caller could otherwise reach by
     * guessing a batch id.
     *
     * <p>The order is load-bearing, exactly as the step-6 block's is:
     * {@code assertOwnerAccessGranted} runs the list in sequence, so the preview has to come
     * before the commit (which moves the batch out of {@code PREVIEW}) and the undo has to come
     * last — it is the only one that can follow a commit at all.
     */
    private List<CrossTenantAssertions.Probe> householdAStepSevenProbes() {
        String accountId = createAccount(sessionOf(householdAdminA), CSV,
            "Import probe account " + UUID.randomUUID(), "CHECKING", "CAD");
        String batchId = uploadCsv(sessionOf(householdAdminA),
            "Date,Description,Amount\n2026-07-01,IMPORT PROBE,-7.00\n", accountId)
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.get("read household A's import preview",
                "/v1/imports/" + batchId + "/preview", batchId),
            CrossTenantAssertions.Probe.mutate("commit household A's import", "POST",
                "/v1/imports/" + batchId + "/commit", "{}"),
            CrossTenantAssertions.Probe.mutate("undo household A's import", "DELETE",
                "/v1/imports/" + batchId, null));
    }

    @Test
    @DisplayName("Household B cannot read, commit or undo household A's imports")
    void importsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepSevenProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Household mates share an import batch — the ledger it writes to is shared")
    void importsAreSharedWithinTheHousehold() {
        String accountId = createAccount(sessionOf(householdAdminA), CSV,
            "Shared import account " + UUID.randomUUID(), "CHECKING", "CAD");
        String batchId = uploadCsv(sessionOf(householdAdminA),
            "Date,Description,Amount\n2026-07-02,SHARED IMPORT PROBE,-8.00\n", accountId)
            .path("id").asString();

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("read the import preview",
                "/v1/imports/" + batchId + "/preview", batchId)));
    }

    @Test
    @DisplayName("Every import route requires a session")
    void importRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepSevenProbes());
    }

    @Test
    @DisplayName("A foreign commit is 404, and the batch it aimed at is untouched")
    void aRefusedImportCommitIsANoOp() {
        String accountId = createAccount(sessionOf(householdAdminA), CSV,
            "Untouched import " + UUID.randomUUID(), "CHECKING", "CAD");
        String batchId = uploadCsv(sessionOf(householdAdminA),
            "Date,Description,Amount\n2026-07-03,UNTOUCHED IMPORT,-9.00\n", accountId)
            .path("id").asString();

        HttpResponse<String> refused = exchange("POST",
            appUrl("/v1/imports/" + batchId + "/commit"), "{}",
            "Authorization", "Bearer " + householdAdminB.accessToken());

        assertThat(refused.statusCode())
            .as("404 and not 403: a 403 confirms the batch exists, which over enough probes is an "
                + "enumeration oracle for another household's ids")
            .isEqualTo(404);
        assertThat(previewOf(sessionOf(householdAdminA), batchId).path("status").asString())
            .as("a refusal must change nothing")
            .isEqualTo("PREVIEW");
        assertThat(transactionIds(sessionOf(householdAdminA), "accountId=" + accountId))
            .as("and it must certainly not have written a row")
            .isEmpty();
    }

    /** Adapts a harness {@link CrossTenantAssertions.Tenant} back to a {@link Session}. */
    private Session sessionOf(CrossTenantAssertions.Tenant tenant) {
        return new Session(tenant.userId(), tenant.householdId(), tenant.email(),
            tenant.accessToken(), null);
    }

    // =========================================================================================
    // Collapse step 10 — subscriptions (GET/DELETE /v1/subscriptions/{id},
    // PATCH /v1/subscriptions/alerts/{id}/read)
    //
    // Appended at the END, as the step brief requires: steps 8, 9 and 10 append their own blocks
    // to this file in parallel worktrees and the orchestrator resolves the append conflicts at
    // merge. Nothing above this banner is reordered or restructured.
    //
    // The ledger is SHARED, so both directions matter here: household B must not reach household
    // A's detection or its alert, and household A's ordinary member must see both — a
    // subscription scoped by user_id instead of household_id would pass the isolation half and
    // hide a partner's bills, which is invisible on a single-member install (§1.5).
    // =========================================================================================

    /** A merchant no other test uses, already in MerchantNormaliser's canonical form. */
    private static String subscriptionMerchant() {
        // The trailing " CO" keeps MerchantNormaliser's digit-bearing trailing-reference pass from
        // stripping the random token and collapsing every test onto one row.
        return ("SUBS PROBE " + UUID.randomUUID().toString().substring(0, 8) + " CO")
            .toUpperCase(java.util.Locale.ROOT);
    }

    /** Runs the manual scan for a household and returns its active subscriptions. */
    private List<JsonNode> scanSubscriptions(Session session) {
        HttpResponse<String> response = exchange("POST", appUrl("/v1/subscriptions/scan"), null,
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("the manual scan failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /**
     * Household A's step-10 by-id surface: a detected subscription and the alert its price rise
     * raised.
     *
     * <p>The order is load-bearing, exactly as the step-6 and step-7 blocks' is:
     * {@code assertOwnerAccessGranted} runs the list in sequence and the DELETE really does
     * dismiss the subscription, so it comes last. The soft delete leaves the row readable, but a
     * cancelled subscription is off {@code GET /v1/subscriptions}, which the shared-visibility
     * probe reads.
     */
    private List<CrossTenantAssertions.Probe> householdAStepTenProbes() {
        String merchant = subscriptionMerchant();
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Subscription probe " + UUID.randomUUID());
        java.time.LocalDate today =
            java.time.LocalDate.parse(todayForCaller(sessionOf(householdAdminA)));
        for (int months = 3; months >= 1; months--) {
            createManualTransaction(sessionOf(householdAdminA), accountId, "9.99",
                today.minusDays(30L * months + 1).toString(), "DEBIT", merchant);
        }
        scanSubscriptions(sessionOf(householdAdminA));
        // A fourth charge at a higher price is what writes the alert the third probe needs.
        createManualTransaction(sessionOf(householdAdminA), accountId, "12.99",
            today.minusDays(1).toString(), "DEBIT", merchant);

        String subscriptionId = scanSubscriptions(sessionOf(householdAdminA)).stream()
            .filter(row -> merchant.equals(row.path("merchantName").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the probe's own subscription was not detected"))
            .path("id").asString();
        String alertId = Json.elements(Json.data(
                get(appUrl("/v1/subscriptions/alerts"), householdAdminA.accessToken()).body()))
            .stream()
            .filter(alert -> subscriptionId.equals(alert.path("subscriptionId").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the price rise raised no alert to probe"))
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.get("read household A's detected subscription",
                "/v1/subscriptions/" + subscriptionId, subscriptionId),
            CrossTenantAssertions.Probe.mutate("acknowledge household A's subscription alert",
                "PATCH", "/v1/subscriptions/alerts/" + alertId + "/read", null),
            // Last, deliberately: this one dismisses the subscription.
            CrossTenantAssertions.Probe.mutate("dismiss household A's detected subscription",
                "DELETE", "/v1/subscriptions/" + subscriptionId, null));
    }

    @Test
    @DisplayName("Household B cannot read, dismiss or acknowledge household A's subscriptions")
    void subscriptionsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepTenProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Household mates share a subscription and its alert — the ledger is shared")
    void subscriptionsAreSharedWithinTheHousehold() {
        String merchant = subscriptionMerchant();
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Shared subscription " + UUID.randomUUID());
        java.time.LocalDate today =
            java.time.LocalDate.parse(todayForCaller(sessionOf(householdAdminA)));
        for (int months = 3; months >= 1; months--) {
            createManualTransaction(sessionOf(householdAdminA), accountId, "4.50",
                today.minusDays(30L * months + 1).toString(), "DEBIT", merchant);
        }
        String subscriptionId = scanSubscriptions(sessionOf(householdAdminA)).stream()
            .filter(row -> merchant.equals(row.path("merchantName").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the shared subscription was not detected"))
            .path("id").asString();

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("read the detected subscription",
                "/v1/subscriptions/" + subscriptionId, subscriptionId),
            CrossTenantAssertions.Probe.get("list the household's subscriptions",
                "/v1/subscriptions", subscriptionId),
            CrossTenantAssertions.Probe.get("read the household's subscription summary",
                "/v1/subscriptions/summary")));
    }

    @Test
    @DisplayName("Every subscription route requires a session")
    void subscriptionRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepTenProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list subscriptions", "/v1/subscriptions"),
            CrossTenantAssertions.Probe.get("read the summary", "/v1/subscriptions/summary"),
            CrossTenantAssertions.Probe.get("list alerts", "/v1/subscriptions/alerts"),
            CrossTenantAssertions.Probe.mutate("run a scan", "POST", "/v1/subscriptions/scan",
                null)));
    }

    @Test
    @DisplayName("A foreign dismissal is 404, and the subscription it aimed at is untouched")
    void aRefusedSubscriptionDismissalIsANoOp() {
        String merchant = subscriptionMerchant();
        String accountId = createManualAccount(sessionOf(householdAdminA),
            "Untouched subscription " + UUID.randomUUID());
        java.time.LocalDate today =
            java.time.LocalDate.parse(todayForCaller(sessionOf(householdAdminA)));
        for (int months = 3; months >= 1; months--) {
            createManualTransaction(sessionOf(householdAdminA), accountId, "8.00",
                today.minusDays(30L * months + 1).toString(), "DEBIT", merchant);
        }
        String subscriptionId = scanSubscriptions(sessionOf(householdAdminA)).stream()
            .filter(row -> merchant.equals(row.path("merchantName").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the subscription was not detected"))
            .path("id").asString();

        HttpResponse<String> refused = exchange("DELETE",
            appUrl("/v1/subscriptions/" + subscriptionId), null,
            "Authorization", "Bearer " + householdAdminB.accessToken());

        assertThat(refused.statusCode())
            .as("404 and not 403: a 403 confirms the row exists, which over enough probes is an "
                + "enumeration oracle for another household's ids")
            .isEqualTo(404);
        assertThat(Json.data(get(appUrl("/v1/subscriptions/" + subscriptionId),
            householdAdminA.accessToken()).body()).path("cancelled").asBoolean())
            .as("a refusal must change nothing")
            .isFalse();
    }

    // ------------------------------------------------------- collapse step 8 (budgets and goals)

    /**
     * Household A's step-8 by-id surface: every route {@code /v1/budgets} and {@code /v1/goals}
     * add that take an id.
     *
     * <p>The order is load-bearing, exactly as the step-6 and step-7 blocks' is:
     * {@code assertOwnerAccessGranted} runs the list in sequence, so the envelope reads have to
     * come before the envelope delete, the period reads before the period delete, and the two
     * deletes last of all — a probe that ran after them would be asking for a row that the probe
     * before it had just removed.
     *
     * <p>The goal probes take a goal of their own rather than sharing one, for the same reason.
     */
    private List<CrossTenantAssertions.Probe> householdAStepEightProbes() {
        String budgetPeriodId = createBudgetPeriodForProbes();
        String categoryId = systemCategoryId(sessionOf(householdAdminA), "food.grocery");
        String envelopeId = Json.data(postJson(
            appUrl("/v1/budgets/" + budgetPeriodId + "/envelopes"),
            Json.object("categoryId", categoryId, "allocated", "400.00", "currency", "CAD"),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String goalId = Json.data(postJson(appUrl("/v1/goals"),
            Json.object("name", "Probe Goal " + UUID.randomUUID(), "targetAmount", "1000.00",
                "currency", "CAD", "deadline", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String envelopePath = "/v1/budgets/" + budgetPeriodId + "/envelopes/" + envelopeId;

        return List.of(
            CrossTenantAssertions.Probe.get("read household A's budget period",
                "/v1/budgets/" + budgetPeriodId, budgetPeriodId),
            CrossTenantAssertions.Probe.get("list household A's envelopes",
                "/v1/budgets/" + budgetPeriodId + "/envelopes", envelopeId),
            CrossTenantAssertions.Probe.get("read household A's envelope", envelopePath,
                envelopeId),
            CrossTenantAssertions.Probe.mutate("re-allocate household A's envelope", "PUT",
                envelopePath, Json.object("allocated", "450.00")),
            // Not Probe.mutate: that helper's owner statuses are {200, 204}, and a create answers
            // 201. Spelling the probe out is the only way to say so.
            new CrossTenantAssertions.Probe("budget another category in household A's period",
                "POST", "/v1/budgets/" + budgetPeriodId + "/envelopes",
                Json.object("categoryId",
                    systemCategoryId(sessionOf(householdAdminA), "food.coffee"),
                    "allocated", "50.00", "currency", "CAD"),
                Set.of(404), Set.of(201), null),
            CrossTenantAssertions.Probe.mutate("change household A's budget period", "PUT",
                "/v1/budgets/" + budgetPeriodId,
                Json.object("totalIncome", "5100.00", "budgetType", "FLEXIBLE")),
            CrossTenantAssertions.Probe.mutate("update household A's savings goal", "PUT",
                "/v1/goals/" + goalId,
                Json.object("name", "Probe Goal", "targetAmount", "1000.00", "currentAmount",
                    "250.00", "deadline", null)),
            CrossTenantAssertions.Probe.mutate("delete household A's savings goal", "DELETE",
                "/v1/goals/" + goalId, null),
            // The two deletes are last, and the envelope's before the period's: deleting the
            // period cascades to the envelope, so the reverse order would leave the envelope
            // probe asking for a row the period probe had already taken with it.
            CrossTenantAssertions.Probe.mutate("delete household A's envelope", "DELETE",
                envelopePath, null),
            CrossTenantAssertions.Probe.mutate("delete household A's budget period", "DELETE",
                "/v1/budgets/" + budgetPeriodId, null));
    }

    /**
     * A budget period for household A in a month it does not already have.
     *
     * <p>{@code UNIQUE (household_id, month)} means one household has exactly one September, and
     * household A here is the suite-wide bootstrap household that every other test writes into.
     * Walking forward from a far-future month until one is free keeps each probe run independent
     * without ever deleting a period some other test is relying on.
     */
    private String createBudgetPeriodForProbes() {
        for (int offset = 0; offset < 60; offset++) {
            HttpResponse<String> response = postJson(appUrl("/v1/budgets"),
                Json.object("month", java.time.LocalDate.of(2090, 1, 1).plusMonths(offset)
                        .toString(), "totalIncome", "5000.00", "currency", "CAD",
                    "budgetType", "FLEXIBLE"),
                "Authorization", "Bearer " + householdAdminA.accessToken());
            if (response.statusCode() == 201) {
                return Json.data(response.body()).path("id").asString();
            }
            assertThat(response.statusCode())
                .as("a free month must answer 201 and a taken one 409; anything else is a real "
                    + "failure. Body: %s", response.body())
                .isEqualTo(409);
        }
        throw new AssertionError("sixty consecutive months of 2090 are all taken");
    }

    @Test
    @DisplayName("Household B cannot read or change household A's budgets, envelopes or goals")
    void budgetsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepEightProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("Household mates share the budgets, the envelopes and the goals")
    void budgetsAreSharedWithinTheHousehold() {
        String budgetPeriodId = createBudgetPeriodForProbes();
        String categoryId = systemCategoryId(sessionOf(householdAdminA), "food.grocery");
        String envelopeId = Json.data(postJson(
            appUrl("/v1/budgets/" + budgetPeriodId + "/envelopes"),
            Json.object("categoryId", categoryId, "allocated", "400.00", "currency", "CAD"),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();
        String goalId = Json.data(postJson(appUrl("/v1/goals"),
            Json.object("name", "Shared Probe Goal " + UUID.randomUUID(), "targetAmount",
                "1000.00", "currency", "CAD", "deadline", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("read the budget period",
                "/v1/budgets/" + budgetPeriodId, budgetPeriodId),
            CrossTenantAssertions.Probe.get("list the period's envelopes",
                "/v1/budgets/" + budgetPeriodId + "/envelopes", envelopeId),
            CrossTenantAssertions.Probe.get("read the envelope",
                "/v1/budgets/" + budgetPeriodId + "/envelopes/" + envelopeId, envelopeId),
            CrossTenantAssertions.Probe.get("list the household's goals", "/v1/goals", goalId)));
    }

    @Test
    @DisplayName("Every budget and goal route requires a session")
    void budgetRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepEightProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list budget periods", "/v1/budgets"),
            CrossTenantAssertions.Probe.get("read this month's budget", "/v1/budgets/current"),
            CrossTenantAssertions.Probe.get("list savings goals", "/v1/goals"),
            CrossTenantAssertions.Probe.mutate("open a budget period", "POST", "/v1/budgets",
                Json.object("month", "2091-01-01", "totalIncome", "1.00", "currency", "CAD",
                    "budgetType", "FLEXIBLE")),
            CrossTenantAssertions.Probe.mutate("start a savings goal", "POST", "/v1/goals",
                Json.object("name", "Anonymous", "targetAmount", "1.00", "currency", "CAD",
                    "deadline", null))));
    }

    // =========================================================================================
    // Collapse step 15 — tax export (§4.12).
    //
    // THIS BLOCK ASSERTS THE OPPOSITE OF EVERY BLOCK ABOVE IT, and that is the point. Everywhere
    // else on this API a household mate must SEE a household mate's rows, because the ledger is
    // shared (§1.5). A tax export run is scoped by user_id, not household_id: a filing is
    // individual (§3's V41 row, §6 "Tax export"), so a mate is refused. There is therefore
    // deliberately NO assertHouseholdMatesShareVisibility call for these routes — adding one
    // would assert the bug this table's tenancy exists to avoid.
    //
    // Three assertions cover the four new by-id routes:
    //   * assertForeignAccessRefused  — household B against household A's run and own category
    //   * assertRoleRefusedWithinHousehold — household A's MEMBER against the ADMIN's own run.
    //     Named for role because that is what every earlier caller uses it for; its mechanism is
    //     "a member of the same household is refused", which is exactly the claim here. The
    //     refusal is about individual ownership rather than about role, and the probes say 404.
    //   * assertAnonymousRefused      — no credential at all
    // =========================================================================================

    /** A tax year no other integration test records into, so a probe run generates almost nothing. */
    private static final int PROBE_TAX_YEAR = 2003;

    /**
     * Household A's tax-export surface: a completed run of the ADMIN's, plus a category that
     * belongs to household A itself rather than to the system seed.
     *
     * <p>The category has to be household A's OWN. A system category is visible to every
     * household by design, so household B writing a tax line against one is a 200 and proves
     * nothing; only a household-owned id can distinguish tenancy here.
     */
    private List<CrossTenantAssertions.Probe> householdAStepFifteenProbes() {
        String runId = completedTaxExportRunFor(householdAdminA);
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Tax probe category " + UUID.randomUUID(), "parentId", null,
                "icon", null),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.mutate("label household A's own category for tax", "PUT",
                "/v1/tax-export/lines/" + categoryId,
                Json.object("jurisdiction", "CA", "lineCode", "9270", "lineLabel",
                    "T2125 line 9270 - Other expenses", "deductible", true)),
            CrossTenantAssertions.Probe.get("read household A admin's export run",
                "/v1/tax-export/runs/" + runId, runId),
            CrossTenantAssertions.Probe.get("download household A admin's export",
                "/v1/tax-export/runs/" + runId + "/download"),
            // Last, as in every block above: the delete takes the row the two probes before it
            // ask for.
            CrossTenantAssertions.Probe.mutate("delete household A admin's export run", "DELETE",
                "/v1/tax-export/runs/" + runId, null));
    }

    /** Starts a run for {@code tenant} and polls it to COMPLETE, so the download probe is real. */
    private String completedTaxExportRunFor(CrossTenantAssertions.Tenant tenant) {
        HttpResponse<String> created = postJson(appUrl("/v1/tax-export/runs"),
            Json.object("taxYear", PROBE_TAX_YEAR, "format", "CSV"),
            "Authorization", "Bearer " + tenant.accessToken());
        assertThat(created.statusCode())
            .as("starting a probe export failed: %s", created.body())
            .isEqualTo(202);
        String runId = Json.data(created.body()).path("id").asString();

        for (int attempt = 0; attempt < 120; attempt++) {
            JsonNode run = Json.data(get(appUrl("/v1/tax-export/runs/" + runId),
                tenant.accessToken()).body());
            String status = run.path("status").asString();
            if ("COMPLETE".equals(status)) {
                return runId;
            }
            assertThat(status)
                .as("the probe run failed: %s", run.path("errorMessage").asString(""))
                .isNotEqualTo("FAILED");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling a probe run", e);
            }
        }
        throw new AssertionError("probe run " + runId + " never reached COMPLETE");
    }

    @Test
    @DisplayName("Household B cannot read, download, delete or relabel household A's tax export")
    void taxExportIsHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepFifteenProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("A household MATE is refused another member's export run — a filing is individual")
    void taxExportRunsAreNotSharedWithinTheHousehold() {
        String runId = completedTaxExportRunFor(householdAdminA);

        harness.assertRoleRefusedWithinHousehold(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("read the admin's export run",
                "/v1/tax-export/runs/" + runId),
            CrossTenantAssertions.Probe.get("download the admin's export",
                "/v1/tax-export/runs/" + runId + "/download"),
            CrossTenantAssertions.Probe.mutate("delete the admin's export run", "DELETE",
                "/v1/tax-export/runs/" + runId, null)));
    }

    @Test
    @DisplayName("Every tax-export route requires a session")
    void taxExportRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepFifteenProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list tax lines", "/v1/tax-export/lines"),
            CrossTenantAssertions.Probe.get("list export runs", "/v1/tax-export/runs"),
            new CrossTenantAssertions.Probe("start an export run", "POST", "/v1/tax-export/runs",
                Json.object("taxYear", PROBE_TAX_YEAR, "format", "CSV"),
                Set.of(404), Set.of(202), null)));
    }

    // =========================================================================================
    // Collapse step 11 — notifications, preferences and webhooks (§4.9).
    //
    // LIKE THE STEP-15 BLOCK ABOVE, AND UNLIKE EVERY LEDGER BLOCK BEFORE IT, this asserts that a
    // household MATE is refused. The owner's tenancy decision is one shared ledger, so a mate sees
    // every transaction, budget and account; a notification is addressed to one person, a
    // preference row is that person's own, and a webhook points at a URL that person controls and
    // is signed with a secret only they hold (§1.5, §3). There is therefore deliberately NO
    // assertHouseholdMatesShareVisibility call for these routes — adding one would assert the very
    // leak these four tables' tenancy exists to prevent.
    //
    // Three by-id routes are added by this step, and all three are probed three ways:
    //   * assertForeignAccessRefused        — household B against household A's rows
    //   * assertRoleRefusedWithinHousehold  — household A's MEMBER against the ADMIN's own rows.
    //     Named for role because that is what every earlier caller uses it for; its mechanism is
    //     "a member of the same household is refused", which is exactly the claim here, and the
    //     probes say 404 rather than 403.
    //   * assertAnonymousRefused            — no credential at all
    //
    // Appended at the very end, and nothing above this banner is reordered or restructured:
    // collapse steps 15 and 17 append their own blocks to this same file in parallel worktrees,
    // and the orchestrator resolves the appends at merge as it did for waves A and B.
    // =========================================================================================

    /** A public IPv4 literal. Nothing connects to it; OutboundUrlPolicy only has to accept it. */
    private static final String WEBHOOK_TARGET = "https://192.0.2.1/access-control-probe";

    /** Sixteen characters is the floor POST /v1/webhooks enforces. */
    private static final String WEBHOOK_SECRET = "a-long-enough-webhook-secret";

    /**
     * Household A's admin's step-11 by-id surface: one notification of theirs and one webhook.
     *
     * <p>The notification is written straight into {@code in_app_notification} rather than by
     * driving a budget over its threshold: this file is about tenancy, and building a realistic
     * crossing here would make an access-control test also a test of budgets. What the row has to
     * be is genuinely the admin's, which a direct insert makes unambiguous.
     *
     * <p>The order is load-bearing, as in every block above: {@code assertOwnerAccessGranted} runs
     * the list in sequence, so the read-modifying probe comes before the two deletes.
     */
    private List<CrossTenantAssertions.Probe> householdAStepElevenProbes() {
        String notificationId = notificationForProbes(householdAdminA);
        String webhookId = Json.data(postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", WEBHOOK_TARGET, "secret", WEBHOOK_SECRET,
                "eventTypes", List.of()),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        return List.of(
            CrossTenantAssertions.Probe.mutate("acknowledge household A admin's notification",
                "PATCH", "/v1/notifications/" + notificationId + "/read", null),
            // The two deletes last: each takes the row the probe before it asks for.
            CrossTenantAssertions.Probe.mutate("dismiss household A admin's notification",
                "DELETE", "/v1/notifications/" + notificationId, null),
            CrossTenantAssertions.Probe.mutate("delete household A admin's webhook", "DELETE",
                "/v1/webhooks/" + webhookId, null));
    }

    /**
     * One notification belonging to {@code tenant}, inserted directly.
     *
     * <p>{@code NotificationService} is not reachable from this class without autowiring a bean
     * that no other probe here needs, and the row's SHAPE is all that matters for a tenancy probe.
     * The id is generated here so the probe path is known before the insert returns.
     */
    private String notificationForProbes(CrossTenantAssertions.Tenant tenant) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO in_app_notification (id, user_id, type, title, body, action_url) "
                + "VALUES (?, ?, 'BUDGET_THRESHOLD', ?, 'probe body', '/dashboard')",
            id, tenant.userId(), "Access-control probe " + id);
        return id.toString();
    }

    @Test
    @DisplayName("Household B cannot read or change household A's notifications or webhooks")
    void notificationsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes = householdAStepElevenProbes();

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("A household MATE is refused another member's notifications and webhooks")
    void notificationsAreNotSharedWithinTheHousehold() {
        // The assertion this API makes in exactly three places — here, the AI settings, and a tax
        // export run. Everywhere else on this ledger a mate must SEE a mate's rows; a notification
        // is addressed to one person, so here they must not.
        String notificationId = notificationForProbes(householdAdminA);
        String webhookId = Json.data(postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", WEBHOOK_TARGET, "secret", WEBHOOK_SECRET,
                "eventTypes", List.of()),
            "Authorization", "Bearer " + householdAdminA.accessToken()).body())
            .path("id").asString();

        harness.assertRoleRefusedWithinHousehold(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.mutate("acknowledge the admin's notification", "PATCH",
                "/v1/notifications/" + notificationId + "/read", null),
            CrossTenantAssertions.Probe.mutate("dismiss the admin's notification", "DELETE",
                "/v1/notifications/" + notificationId, null),
            CrossTenantAssertions.Probe.mutate("delete the admin's webhook", "DELETE",
                "/v1/webhooks/" + webhookId, null)));

        // And the listings: a mate's own inbox and webhook list must not contain the admin's rows.
        assertThat(Json.elements(Json.data(get(appUrl("/v1/notifications"),
            householdMemberA.accessToken()).body())))
            .as("a household mate does not see another member's inbox at all")
            .noneMatch(row -> notificationId.equals(row.path("id").asString()));
        assertThat(Json.elements(Json.data(get(appUrl("/v1/webhooks"),
            householdMemberA.accessToken()).body())))
            .noneMatch(row -> webhookId.equals(row.path("id").asString()));
    }

    @Test
    @DisplayName("Each member's notification preferences are their own")
    void preferencesAreNotSharedWithinTheHousehold() {
        putJson(appUrl("/v1/notifications/preferences"),
            Json.object("budgetAlertThresholdPercent", 55),
            "Authorization", "Bearer " + householdAdminA.accessToken());

        JsonNode mine = Json.data(get(appUrl("/v1/notifications/preferences"),
            householdAdminA.accessToken()).body());
        JsonNode theirs = Json.data(get(appUrl("/v1/notifications/preferences"),
            householdMemberA.accessToken()).body());

        assertThat(mine.path("budgetAlertThresholdPercent").asInt()).isEqualTo(55);
        assertThat(theirs.path("budgetAlertThresholdPercent").asInt())
            .as("there is no route through which one member could read or write another's "
                + "notification settings, so a mate sees their own shipped defaults")
            .isEqualTo(90);
    }

    @Test
    @DisplayName("Every notification, preference and webhook route requires a session")
    void notificationRoutesRequireASession() {
        harness.assertAnonymousRefused(householdAStepElevenProbes());
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list notifications", "/v1/notifications"),
            CrossTenantAssertions.Probe.get("read the unread count",
                "/v1/notifications/unread-count"),
            CrossTenantAssertions.Probe.get("read notification preferences",
                "/v1/notifications/preferences"),
            CrossTenantAssertions.Probe.get("list webhooks", "/v1/webhooks"),
            CrossTenantAssertions.Probe.mutate("mark every notification read", "POST",
                "/v1/notifications/read-all", null),
            CrossTenantAssertions.Probe.mutate("change notification preferences", "PUT",
                "/v1/notifications/preferences", Json.object("inAppEnabled", true)),
            new CrossTenantAssertions.Probe("create a webhook", "POST", "/v1/webhooks",
                Json.object("targetUrl", WEBHOOK_TARGET, "secret", WEBHOOK_SECRET,
                    "eventTypes", List.of()),
                Set.of(404), Set.of(201), null)));
    }

    // =========================================================================================
    // Collapse step 13 — the AI coach (§4.11).
    //
    // LIKE STEP 15's BLOCK ABOVE AND UNLIKE EVERY LEDGER BLOCK, this one asserts that a household
    // mate is REFUSED. coach_conversation and coach_insight are scoped by user_id, not
    // household_id (§1.5, §3's user_id enumeration): the FIGURES a coach answer discusses come
    // from the shared household ledger, but the conversation about them belongs to one member.
    // There is therefore deliberately NO assertHouseholdMatesShareVisibility call for these
    // routes — adding one would assert the leak this table's tenancy exists to prevent.
    //
    // Three assertions cover the two routes that can name one member's conversation:
    //   * assertForeignAccessRefused      — household B against household A's conversation
    //   * assertRoleRefusedWithinHousehold — household A's MEMBER against the ADMIN's own
    //     conversation. Named for role because that is what every earlier caller uses it for; its
    //     mechanism is "a member of the same household is refused", which is exactly the claim
    //     here, and the probes say 404 because ownership is individual rather than role-based.
    //   * assertAnonymousRefused          — no credential at all
    //
    // §4.11 gives no per-conversation by-id PATH, so there is nothing else to probe: one
    // conversation is named by the `sessionId` query parameter of GET and DELETE /v1/chat/history.
    //
    // The conversation row is inserted directly. Creating one through POST /v1/chat/completions
    // needs a working AI endpoint, and the integration suite cannot reach one: AiStubServer binds
    // to loopback, OutboundUrlPolicy refuses loopback unless TROVE_EGRESS_ALLOW_PRIVATE is set,
    // and no IT can set it without forking a second Spring context. The probes are about who may
    // NAME a conversation, and an empty conversation is enough to ask that.
    // =========================================================================================

    /** A conversation owned by {@code tenant}, for the two routes that can name one. */
    private String coachConversationFor(CrossTenantAssertions.Tenant tenant) {
        UUID conversationId = UUID.randomUUID();
        jdbcTemplate.update("insert into coach_conversation (id, user_id) values (?, ?)",
            conversationId, tenant.userId());
        return conversationId.toString();
    }

    private List<CrossTenantAssertions.Probe> householdAStepThirteenProbes(String sessionId) {
        return List.of(
            CrossTenantAssertions.Probe.get("read household A admin's coach conversation",
                "/v1/chat/history?sessionId=" + sessionId),
            // Last, as in every block above: the delete takes the row the probe before it reads.
            CrossTenantAssertions.Probe.mutate("clear household A admin's coach conversation",
                "DELETE", "/v1/chat/history?sessionId=" + sessionId, null));
    }

    @Test
    @DisplayName("Household B cannot read or clear household A's coach conversation")
    void coachConversationsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes =
            householdAStepThirteenProbes(coachConversationFor(householdAdminA));

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("A household MATE is refused another member's coach conversation")
    void coachConversationsAreNotSharedWithinTheHousehold() {
        String sessionId = coachConversationFor(householdAdminA);

        harness.assertRoleRefusedWithinHousehold(householdAdminA, householdMemberA,
            householdAStepThirteenProbes(sessionId));

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where id = cast(? as uuid)", Long.class,
            sessionId))
            .as("a refused delete deletes nothing")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Every coach and insight route requires a session")
    void coachRoutesRequireASession() {
        harness.assertAnonymousRefused(
            householdAStepThirteenProbes(coachConversationFor(householdAdminA)));
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("read coach history", "/v1/chat/history"),
            CrossTenantAssertions.Probe.get("read coach suggestions", "/v1/chat/suggestions"),
            CrossTenantAssertions.Probe.get("read weekly insights", "/v1/insights"),
            CrossTenantAssertions.Probe.mutate("clear all coach history", "DELETE",
                "/v1/chat/history", null),
            new CrossTenantAssertions.Probe("ask the coach a question", "POST",
                "/v1/chat/completions",
                "{\"messages\":[{\"role\":\"user\",\"content\":\"Anonymous?\"}]}",
                Set.of(404), Set.of(200), null)));
    }

    // =========================================================================================
    // COLLAPSE STEP 14 — receipts
    //
    // A receipt is ORDINARY shared ledger data, and this block is therefore the OPPOSITE of the
    // step 11, 13 and 15 blocks above it. `user_id` on `receipt` is the UPLOADER, not the scope;
    // `household_id` is the scope (§1.5, §3's tenancy note). So all four assertions apply here and
    // the third one is the one those other blocks deliberately omit:
    //   * assertForeignAccessRefused        — household B against household A's receipt
    //   * assertOwnerAccessGranted          — the uploader still reads their own
    //   * assertHouseholdMatesShareVisibility — a household MATE sees and reads it too, which is
    //     what one shared ledger means and what an over-tight per-user scoping would break
    //     invisibly on a single-member install
    //   * assertAnonymousRefused            — no credential at all, including on the two
    //     byte-streaming routes (there is no presigned URL and no public path — RS-7)
    //
    // §9 risk 2 names `receipt` explicitly among the five entities household B must not reach.
    //
    // Every by-id route this step adds has a probe: GET, PATCH and DELETE /v1/receipts/{id},
    // PATCH /{id}/review, GET /{id}/status, /{id}/download and /{id}/thumbnail. The DELETE probe
    // is LAST in the list, as in every block above, because it takes the row every probe before
    // it reads.
    //
    // The receipt is uploaded through the real endpoint rather than inserted, because the two
    // stored files are what the download and thumbnail probes read and a hand-written row would
    // have none.
    // =========================================================================================

    /** A receipt uploaded by {@code tenant}, through the real endpoint. */
    private String receiptFor(CrossTenantAssertions.Tenant tenant) {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                finance.trove.receipts.ReceiptImageFixtures.jpeg(400, 300))),
            "Authorization", "Bearer " + tenant.accessToken());
        assertThat(response.statusCode())
            .as("uploading household A's receipt failed: %s", response.body())
            .isEqualTo(202);
        return Json.data(response.body()).path("id").asString();
    }

    private List<CrossTenantAssertions.Probe> householdAStepFourteenProbes(String receiptId) {
        return List.of(
            CrossTenantAssertions.Probe.get("read household A's receipt",
                "/v1/receipts/" + receiptId, receiptId),
            CrossTenantAssertions.Probe.get("poll household A's receipt status",
                "/v1/receipts/" + receiptId + "/status", receiptId),
            CrossTenantAssertions.Probe.get("download household A's receipt image",
                "/v1/receipts/" + receiptId + "/download"),
            CrossTenantAssertions.Probe.get("read household A's receipt thumbnail",
                "/v1/receipts/" + receiptId + "/thumbnail"),
            CrossTenantAssertions.Probe.mutate("edit household A's receipt", "PATCH",
                "/v1/receipts/" + receiptId, "{\"extractedMerchant\":\"Edited\"}"),
            CrossTenantAssertions.Probe.mutate("review household A's receipt", "PATCH",
                "/v1/receipts/" + receiptId + "/review", "{\"accepted\":true}"),
            // Last: the delete takes the row every probe above it reads.
            CrossTenantAssertions.Probe.mutate("delete household A's receipt", "DELETE",
                "/v1/receipts/" + receiptId, null));
    }

    @Test
    @DisplayName("Household B cannot read, download, edit or delete household A's receipt")
    void receiptsAreHouseholdIsolated() {
        List<CrossTenantAssertions.Probe> probes =
            householdAStepFourteenProbes(receiptFor(householdAdminA));

        harness.assertForeignAccessRefused(householdAdminA, householdAdminB, probes);
        harness.assertOwnerAccessGranted(householdAdminA, probes);
    }

    @Test
    @DisplayName("A household mate sees and downloads a receipt somebody else uploaded")
    void receiptsAreSharedWithinTheHousehold() {
        // The uploader is household A's ADMIN and the reader is household A's MEMBER. `user_id`
        // on the row is the uploader and is NOT the scope: hiding a member's receipts from their
        // own household is the mistake this asserts against, and it is invisible on an install
        // with one member.
        String receiptId = receiptFor(householdAdminA);

        harness.assertHouseholdMatesShareVisibility(householdAdminA, householdMemberA, List.of(
            CrossTenantAssertions.Probe.get("read the household's receipt",
                "/v1/receipts/" + receiptId, receiptId),
            CrossTenantAssertions.Probe.get("list the household's receipts", "/v1/receipts",
                receiptId)));

        // The two byte-streaming routes are asserted here rather than through the harness:
        // assertHouseholdMatesShareVisibility compares the row ids inside a 200 body, and these
        // two answer a JPEG. The claim is the same one — a mate reaches them — so it is made
        // directly. (ReceiptDownloadIT carries the header and household-B halves.)
        for (String path : List.of("/download", "/thumbnail")) {
            HttpResponse<String> mate = get(appUrl("/v1/receipts/" + receiptId + path),
                householdMemberA.accessToken());
            assertThat(mate.statusCode())
                .as("a household mate must be able to fetch %s of a receipt somebody else in "
                    + "their household uploaded", path)
                .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Every receipt route requires a session, the two image routes included")
    void receiptRoutesRequireASession() {
        harness.assertAnonymousRefused(
            householdAStepFourteenProbes(receiptFor(householdAdminA)));
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list receipts", "/v1/receipts")));
    }

    // ------------------------------------- collapse step 18 (data export, account deletion)

    /**
     * A member of household A with a COMPLETE data-export run, and the run's id.
     *
     * <p>A FRESH member per call, deliberately: gap row 16 caps a member at one run a day, so
     * reusing household A's admin would make the second test in this block 409 and the third fail
     * on whichever ran first. The run is started through the real route with the real step-up and
     * polled to COMPLETE, because a probe against a hand-inserted row would prove the finder is
     * scoped and nothing at all about the route that reaches it.
     */
    private CrossTenantAssertions.Tenant exportOwnerInHouseholdA(String prefix) {
        Session owner = inviteAndRegister(loginBootstrapAdmin(), prefix);
        return tenant("household A export owner", owner);
    }

    private String dataExportRunOf(CrossTenantAssertions.Tenant owner) {
        HttpResponse<String> created = postJson(appUrl("/v1/me/export"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + owner.accessToken());
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(202);
        String runId = Json.data(created.body()).path("id").asString();

        for (int attempt = 0; attempt < 200; attempt++) {
            JsonNode run = Json.data(get(appUrl("/v1/me/export/" + runId),
                owner.accessToken()).body());
            if ("COMPLETE".equals(run.path("status").asString())) {
                return runId;
            }
            assertThat(run.path("status").asString())
                .as("run failed: %s", run.path("errorMessage").asString(""))
                .isNotEqualTo("FAILED");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling", e);
            }
        }
        throw new AssertionError("data export run " + runId + " never reached COMPLETE");
    }

    private List<CrossTenantAssertions.Probe> exportProbes(String runId) {
        return List.of(
            CrossTenantAssertions.Probe.get("read another member's data-export run",
                "/v1/me/export/" + runId),
            CrossTenantAssertions.Probe.get("download another member's data-export archive",
                "/v1/me/export/" + runId + "/download"));
    }

    private List<CrossTenantAssertions.Probe> restoreProbe(UUID targetId) {
        return List.of(CrossTenantAssertions.Probe.adminOnly(
            "restore a member of household A", "POST",
            "/v1/admin/users/" + targetId + "/restore", null));
    }

    @Test
    @DisplayName("Household B cannot read or download household A's data-export archive")
    void dataExportRunsAreIsolatedAcrossHouseholds() {
        CrossTenantAssertions.Tenant owner = exportOwnerInHouseholdA("tenant-a-export-iso");
        List<CrossTenantAssertions.Probe> probes = exportProbes(dataExportRunOf(owner));

        harness.assertForeignAccessRefused(owner, householdAdminB, probes);
        harness.assertOwnerAccessGranted(owner, probes);
    }

    @Test
    @DisplayName("A household MATE is refused an export run that is not theirs — 404, not 403")
    void anExportRunIsNotSharedWithinTheHousehold() {
        // NOTE: this step deliberately does NOT call assertHouseholdMatesShareVisibility for the
        // export routes, and the omission IS the assertion. An archive is a copy of one person's
        // whole financial life — individual exactly as a tax filing is (§1.5, §6) — so the
        // shared-ledger rule every other by-id route in this file follows is the wrong rule here.
        // The LEDGER inside the archive is still shared; the archive is not.
        CrossTenantAssertions.Tenant owner = exportOwnerInHouseholdA("tenant-a-export-mate");
        assertThat(owner.householdId())
            .as("this assertion is about two members of the SAME household")
            .isEqualTo(householdMemberA.householdId());
        String runId = dataExportRunOf(owner);

        for (CrossTenantAssertions.Probe probe : exportProbes(runId)) {
            HttpResponse<String> mate = get(appUrl(probe.path()),
                householdMemberA.accessToken());
            assertThat(mate.statusCode())
                .as("%s: 404 and not 403, so the refusal never confirms another member's run "
                    + "exists. Body: %s", probe.description(), mate.body())
                .isEqualTo(404);
            assertThat(Json.errorCode(mate.body())).isEqualTo("not-found");
        }
    }

    @Test
    @DisplayName("Every step-18 route requires a session, and restore requires the ADMIN role")
    void stepEighteenRoutesAreGuarded() {
        CrossTenantAssertions.Tenant owner = exportOwnerInHouseholdA("tenant-a-export-guard");
        String runId = dataExportRunOf(owner);

        harness.assertAnonymousRefused(exportProbes(runId));
        harness.assertAnonymousRefused(List.of(
            CrossTenantAssertions.Probe.get("list my export runs", "/v1/me/export")));
        harness.assertAnonymousRefused(restoreProbe(householdMemberA.userId()));

        harness.assertRoleRefusedWithinHousehold(householdAdminA, householdMemberA,
            restoreProbe(householdAdminA.userId()));
    }

    @Test
    @DisplayName("Household B's admin cannot restore a member of household A")
    void restoreIsHouseholdScoped() {
        // The target is real and belongs to household A, so a 404 here is the scoped finder
        // answering rather than an unknown id answering by accident. Household B's admin holds the
        // ADMIN role, so the role gate lets them through and only the tenancy check stands between
        // them and somebody else's member.
        HttpResponse<String> refused = postJson(
            appUrl("/v1/admin/users/" + householdMemberA.userId() + "/restore"), null,
            "Authorization", "Bearer " + householdAdminB.accessToken());

        assertThat(refused.statusCode())
            .as("'not yours' and 'does not exist' are the same answer, and neither confirms an "
                + "id is real. Body: %s", refused.body())
            .isEqualTo(404);
    }
}
