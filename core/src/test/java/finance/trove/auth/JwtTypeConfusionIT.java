package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.app.support.JwtTestHelper;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §9 risk 28 — the complete second-factor bypass the pre-collapse verifier shipped.
 *
 * <p>That verifier accepted any HS256 token carrying a {@code sub} as a full access token
 * ({@code JwtAuthenticationProvider.java:52-65}), including the sixty-second challenge handed out
 * between the password step and the TOTP step. An attacker who knew a password could stop at the
 * challenge and use it as a session, so the second factor was decorative.
 *
 * <p>Every token below is signed with this install's real key and would have been accepted then.
 */
class JwtTypeConfusionIT extends AuthFlowSupport {

    @Test
    @DisplayName("A totp_challenge token is refused on GET /v1/auth/me")
    void challengeTokenIsNotASession() {
        String challenge = JwtTestHelper.totpChallengeToken(UUID.randomUUID());

        HttpResponse<String> response = get(appUrl("/v1/auth/me"), challenge);

        assertThat(response.statusCode())
            .as("this token proves a password and nothing else")
            .isEqualTo(401);
        assertThat(Json.parse(response.body()).path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A totp_challenge token is refused on every other protected route too")
    void challengeTokenIsRefusedEverywhere() {
        String challenge = JwtTestHelper.totpChallengeToken(UUID.randomUUID());

        for (String path : new String[] {
            "/v1/me/preferences", "/v1/admin/invites", "/v1/admin/settings"}) {
            assertThat(get(appUrl(path), challenge).statusCode())
                .as("%s", path)
                .isEqualTo(401);
        }
        assertThat(putJson(appUrl("/v1/me"), Json.object("displayName", "X"),
            "Authorization", "Bearer " + challenge).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("A refresh token is refused as an access token")
    void refreshTokenIsNotASession() {
        assertThat(get(appUrl("/v1/auth/me"),
            JwtTestHelper.refreshToken(UUID.randomUUID())).statusCode())
            .as("a signed refresh token presented on a protected route is rejected on its type "
                + "before any database work happens")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("An access token is refused at POST /v1/auth/login/totp — the mirror rule")
    void accessTokenIsNotAChallenge() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> response = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", admin.accessToken(), "code", "123456"));

        assertThat(response.statusCode())
            .as("the challenge route accepts only a challenge token; a full session presented "
                + "here would let a caller re-enter the TOTP step with a token that has already "
                + "passed it")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("A token from another install sharing this secret is refused on its issuer")
    void foreignIssuerIsRefused() {
        assertThat(get(appUrl("/v1/auth/me"),
            JwtTestHelper.tokenWithForeignIssuer(UUID.randomUUID(), UUID.randomUUID()))
            .statusCode())
            .as("a restored backup or a staging copy of production's .env must not mint "
                + "production sessions")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("A token stamped with an unknown key id is refused")
    void foreignKeyIdIsRefused() {
        assertThat(get(appUrl("/v1/auth/me"),
            JwtTestHelper.tokenWithForeignKeyId(UUID.randomUUID(), UUID.randomUUID()))
            .statusCode())
            .isEqualTo(401);
    }

    @Test
    @DisplayName("A token naming a role this build does not have is anonymous, not privileged")
    void unknownRoleIsNotADefault() {
        String token = JwtTestHelper.tokenWithUnknownRole(UUID.randomUUID(), UUID.randomUUID());

        assertThat(get(appUrl("/v1/auth/me"), token).statusCode())
            .as("defaulting an unrecognised role would be a silent privilege decision")
            .isEqualTo(401);
        assertThat(get(appUrl("/v1/admin/invites"), token).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("A well-formed token for a user who does not exist authenticates nothing useful")
    void aTokenForANonexistentUserReadsNothing() {
        String token = JwtTestHelper.accessToken(UUID.randomUUID(), UUID.randomUUID(),
            "ghost@trove.test", "ADMIN");

        assertThat(get(appUrl("/v1/auth/me"), token).statusCode())
            .as("the signature is valid, so the filter admits it; the household-scoped lookup "
                + "then finds nothing, which is the layer that actually protects data")
            .isEqualTo(401);
    }
}
