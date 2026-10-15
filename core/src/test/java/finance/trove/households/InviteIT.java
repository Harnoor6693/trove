package finance.trove.households;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.app.support.LogCapture;
import finance.trove.auth.AuthMode;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Invitations end to end (readiness gap P2-7): create, list, preview, accept, expire, revoke.
 *
 * <p>The premise these all serve: registration is closed by default, because an open
 * {@code /register} on the public internet lets strangers create accounts on somebody's finance
 * server. An invite is the way in, and it pre-binds the household so membership is something the
 * server decides rather than something a request body asserts.
 */
class InviteIT extends AuthFlowSupport {

    @Test
    @DisplayName("A created invite carries the token once and an absolute URL")
    void creationCarriesTheOnlyReadableCopy() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> created = postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode invite = Json.data(created.body());
        assertThat(invite.path("token").asString()).isNotBlank();
        assertThat(invite.path("url").asString())
            .isEqualTo(PUBLIC_URL + "/invite/" + invite.path("token").asString());
        assertThat(invite.path("email").isNull()).isTrue();
        assertThat(invite.path("acceptedAt").isNull()).isTrue();
        assertThat(invite.path("createdByEmail").asString()).isEqualTo(BOOTSTRAP_ADMIN_EMAIL);
        assertThat(Instant.parse(invite.path("expiresAt").asString()))
            .isAfter(Instant.now().plusSeconds(71 * 3600));
    }

    @Test
    @DisplayName("The listing carries a working link for an invite that can still be redeemed")
    void listingCarriesTheLinkOfAUsableInvite() {
        // The shipped web admin UI discards the creation response and renders the link from this
        // listing (apps/web/src/pages/Settings/index.tsx:166-169, 215), so a listing without it
        // made the invite feature unusable from the only UI there is.
        Session admin = loginBootstrapAdmin();
        JsonNode created = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken()).body());
        String id = created.path("id").asString();

        HttpResponse<String> listed = get(appUrl("/v1/admin/invites"), admin.accessToken());

        assertThat(listed.statusCode()).isEqualTo(200);
        String listedToken = Json.elements(Json.data(listed.body())).stream()
            .filter(node -> node.path("id").asString().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the invite just created is not in the listing"))
            .path("token").asString();
        assertThat(listedToken).isEqualTo(created.path("token").asString());

        assertThat(Json.data(get(appUrl("/v1/auth/invites/" + listedToken)).body())
            .path("valid").asBoolean())
            .as("the link an admin copies out of the listing is one a household member can open")
            .isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT token_encrypted FROM household_invite WHERE id = ?::uuid", String.class, id))
            .as("what sits in the row is ciphertext; a dump without TROVE_ENCRYPTION_KEY is "
                + "still worthless")
            .isNotNull()
            .doesNotContain(listedToken);
    }

    @Test
    @DisplayName("A spent invite lists with no token, and the row keeps no readable copy")
    void listingNeverCarriesASpentToken() {
        Session admin = loginBootstrapAdmin();
        JsonNode created = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken()).body());
        String id = created.path("id").asString();
        registerWithInvite(created.path("token").asString(), uniqueEmail("spent"),
            MEMBER_PASSWORD);

        assertThat(Json.elements(Json.data(get(appUrl("/v1/admin/invites"),
            admin.accessToken()).body())))
            .filteredOn(node -> node.path("id").asString().equals(id))
            .singleElement()
            .satisfies(node -> {
                assertThat(node.path("token").isNull()).isTrue();
                assertThat(node.path("url").isNull()).isTrue();
                assertThat(node.path("acceptedAt").isNull()).isFalse();
            });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT token_encrypted FROM household_invite WHERE id = ?::uuid", String.class, id))
            .as("redeeming forgets the readable copy — it is not merely hidden from the listing")
            .isNull();
    }

    @Test
    @DisplayName("One invite, two simultaneous registrations, exactly one new member")
    void aSingleUseInviteCannotBeRedeemedTwice() throws Exception {
        // Redemption is a check-then-act — read accepted_at, hash a password for tens of
        // milliseconds, write accepted_at — so without the row lock both callers read null and
        // both succeeded, and a one-seat invite admitted two people.
        Session admin = loginBootstrapAdmin();
        String token = createInvite(admin, null);
        String bodyOne = Json.object("inviteToken", token, "email", uniqueEmail("race-a"),
            "password", MEMBER_PASSWORD, "displayName", "Race A");
        String bodyTwo = Json.object("inviteToken", token, "email", uniqueEmail("race-b"),
            "password", MEMBER_PASSWORD, "displayName", "Race B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = List.of(
                pool.submit(() -> race(ready, go, bodyOne)),
                pool.submit(() -> race(ready, go, bodyTwo)));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                results.get(1).get(60, TimeUnit.SECONDS));
            assertThat(statuses)
                .as("exactly one seat: %s", statuses)
                .containsExactlyInAnyOrder(201, 410);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_invite WHERE token_hash = ? "
                + "AND accepted_user_id IS NOT NULL",
            Integer.class,
            finance.trove.households.invites.InviteService.hash(token)))
            .isEqualTo(1);
    }

    private int race(CountDownLatch ready, CountDownLatch go, String body) throws Exception {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        return postJson(appUrl("/v1/auth/register"), body).statusCode();
    }

    @Test
    @DisplayName("The public preview reports a valid invite and its bound address")
    void previewOfAValidInvite() {
        Session admin = loginBootstrapAdmin();
        String bound = uniqueEmail("bound");
        String token = createInvite(admin, bound);

        HttpResponse<String> preview = get(appUrl("/v1/auth/invites/" + token));

        assertThat(preview.statusCode())
            .as("public: the page that opens an invite link has no session yet")
            .isEqualTo(200);
        JsonNode data = Json.data(preview.body());
        assertThat(data.path("valid").asBoolean()).isTrue();
        assertThat(data.path("email").asString()).isEqualTo(bound);
        assertThat(data.path("expiresAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("Previewing an invite writes the token to no log line and to no error body")
    void previewingAnInviteNeverWritesTheTokenDown() {
        // The preview is anonymous by design — somebody handed a link has no session yet — so the
        // one credential that opens a household travels in the path of a public GET. The
        // completion line RequestIdMdcFilter writes for every request ran at INFO with the raw
        // URI in it, which put a still-redeemable token in `docker logs` for the whole 72-hour
        // TTL and handed anyone with log access a way into the household. That defeats the
        // property V9_2 encrypts the column to preserve: the log was a plaintext second copy.
        Session admin = loginBootstrapAdmin();
        String token = createInvite(admin, null);

        List<String> logged;
        HttpResponse<String> valid;
        HttpResponse<String> unknown;
        try (LogCapture capture = LogCapture.start()) {
            valid = get(appUrl("/v1/auth/invites/" + token));
            unknown = get(appUrl("/v1/auth/invites/" + token + "-not-a-real-token"));
            logged = capture.lines();
        }

        assertThat(valid.statusCode()).isEqualTo(200);
        assertThat(Json.data(valid.body()).path("valid").asBoolean())
            .as("the probe is only meaningful against a token that still works")
            .isTrue();
        assertThat(logged)
            .as("something has to have been logged, or this test proves nothing")
            .isNotEmpty();
        assertThat(logged)
            .as("no log line may carry the token, at any level, from any logger")
            .noneMatch(line -> line.contains(token));
        assertThat(logged)
            .as("the route template is what replaces it, which is also what §4.2 requires of the "
                + "audit log")
            .anyMatch(line -> line.contains("/api/v1/auth/invites/{token}"));

        assertThat(unknown.body())
            .as("the error body's `instance` is the other place a path is copied out verbatim")
            .doesNotContain(token);
    }

    @Test
    @DisplayName("The preview answers 200 with valid:false for an unknown token")
    void previewOfAnUnknownTokenIsNotA404() {
        HttpResponse<String> preview = get(appUrl("/v1/auth/invites/not-a-real-token"));

        assertThat(preview.statusCode())
            .as("a 404 here would make the endpoint an oracle for guessing token values, and the "
                + "page has nothing better to do with one than show the same copy")
            .isEqualTo(200);
        JsonNode data = Json.data(preview.body());
        assertThat(data.path("valid").asBoolean()).isFalse();
        assertThat(data.path("email").isNull()).isTrue();
    }

    @Test
    @DisplayName("An invite is single-use: the second registration is 410")
    void invitesAreSingleUse() {
        Session admin = loginBootstrapAdmin();
        String token = createInvite(admin, null);
        registerWithInvite(token, uniqueEmail("first"), MEMBER_PASSWORD);

        HttpResponse<String> second = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", uniqueEmail("second"),
                "password", MEMBER_PASSWORD, "displayName", "Second"));

        assertThat(second.statusCode()).isEqualTo(410);
        assertThat(Json.errorCode(second.body())).isEqualTo("invite-expired");
        assertThat(Json.data(get(appUrl("/v1/auth/invites/" + token)).body())
            .path("valid").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("An expired invite is 410 and previews as invalid")
    void expiredInvitesAreRefused() {
        Session admin = loginBootstrapAdmin();
        String token = createInvite(admin, null);
        jdbcTemplate.update(
            "UPDATE household_invite SET expires_at = now() - interval '1 hour' "
                + "WHERE token_hash = ?",
            finance.trove.households.invites.InviteService.hash(token));

        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", uniqueEmail("late"),
                "password", MEMBER_PASSWORD, "displayName", "Late"));

        assertThat(response.statusCode()).isEqualTo(410);
        assertThat(Json.errorCode(response.body())).isEqualTo("invite-expired");
        assertThat(Json.data(get(appUrl("/v1/auth/invites/" + token)).body())
            .path("valid").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A revoked invite is 404 — indistinguishable from one that never existed")
    void revokedInvitesAreRefused() {
        Session admin = loginBootstrapAdmin();
        JsonNode created = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken()).body());
        String token = created.path("token").asString();

        HttpResponse<String> revoked = delete(
            appUrl("/v1/admin/invites/" + created.path("id").asString()), null,
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(revoked.statusCode()).isEqualTo(200);

        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", uniqueEmail("revoked"),
                "password", MEMBER_PASSWORD, "displayName", "Revoked"));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(response.body())).isEqualTo("invite-invalid");
    }

    @Test
    @DisplayName("An email-bound invite refuses a different address, with no hint whose it was")
    void boundInvitesRefuseAnotherAddress() {
        Session admin = loginBootstrapAdmin();
        String bound = uniqueEmail("intended");
        String token = createInvite(admin, bound);

        HttpResponse<String> wrong = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", uniqueEmail("interloper"),
                "password", MEMBER_PASSWORD, "displayName", "Interloper"));

        assertThat(wrong.statusCode()).isEqualTo(404);
        assertThat(wrong.body())
            .as("a distinct refusal would confirm to whoever holds a leaked link which address "
                + "it was issued to — the fact the binding exists to protect")
            .doesNotContain(bound);

        assertThat(postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", token, "email", bound.toUpperCase(java.util.Locale.ROOT),
                "password", MEMBER_PASSWORD, "displayName", "Intended"),
            AuthMode.HEADER, "bearer").statusCode())
            .as("the bound address is matched case-insensitively; people capitalise")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("An admin can mint an invite that confers ADMIN")
    void adminInvitesCanConferAdmin() {
        Session admin = loginBootstrapAdmin();
        String token = Json.data(postJson(appUrl("/v1/admin/invites"),
            Json.object("role", "ADMIN"),
            "Authorization", "Bearer " + admin.accessToken()).body()).path("token").asString();

        Session promoted = registerWithInvite(token, uniqueEmail("coadmin"), MEMBER_PASSWORD);

        assertThat(Json.data(get(appUrl("/v1/auth/me"), promoted.accessToken()).body())
            .path("role").asString()).isEqualTo("ADMIN");
        assertThat(get(appUrl("/v1/admin/invites"), promoted.accessToken()).statusCode())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("An ordinary member cannot reach any admin invite route")
    void membersCannotAdministerInvites() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "invite-member");

        assertThat(get(appUrl("/v1/admin/invites"), member.accessToken()).statusCode())
            .isEqualTo(403);
        assertThat(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(403);

        String body = get(appUrl("/v1/admin/invites"), member.accessToken()).body();
        assertThat(Json.parse(body).path("success").asBoolean())
            .as("an admin-route 403 must be the JSON envelope, not the bare 500 gap P2-7 records")
            .isFalse();
        assertThat(Json.parse(body).path("error").path("status").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("The admin routes are 401 anonymously, not 403")
    void adminRoutesRequireASessionFirst() {
        assertThat(get(appUrl("/v1/admin/invites")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/admin/invites"), "{}").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("An admin can open and close self-serve registration at runtime")
    void registrationCanBeOpenedAndClosed() {
        Session admin = loginBootstrapAdmin();
        try {
            assertThat(Json.data(get(appUrl("/v1/admin/settings"), admin.accessToken()).body())
                .path("registrationOpen").asBoolean()).isFalse();

            HttpResponse<String> opened = putJson(appUrl("/v1/admin/settings"),
                Json.object("registrationOpen", true),
                "Authorization", "Bearer " + admin.accessToken());
            assertThat(opened.statusCode()).isEqualTo(200);
            assertThat(Json.data(opened.body()).path("registrationOpen").asBoolean()).isTrue();

            assertThat(Json.data(get(appUrl("/v1/auth/providers")).body())
                .path("registrationOpen").asBoolean())
                .as("the login page renders itself from the live household row, not from the "
                    + "environment variable that seeded it")
                .isTrue();

            assertThat(postJson(appUrl("/v1/auth/register"),
                Json.object("email", uniqueEmail("selfserve"), "password", MEMBER_PASSWORD,
                    "displayName", "Self Serve"), AuthMode.HEADER, "bearer").statusCode())
                .isEqualTo(201);
        } finally {
            putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", false),
                "Authorization", "Bearer " + admin.accessToken());
        }
        assertThat(postJson(appUrl("/v1/auth/register"),
            Json.object("email", uniqueEmail("closed"), "password", MEMBER_PASSWORD,
                "displayName", "Closed")).statusCode()).isEqualTo(403);
    }
}
