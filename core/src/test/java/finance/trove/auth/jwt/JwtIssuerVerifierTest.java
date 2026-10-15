package finance.trove.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.exception.UnauthorizedException;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code typ}-claim contract, which is the whole of §9 risk 28.
 *
 * <p>The pre-collapse verifier accepted any HS256 token carrying a {@code sub} as a full access
 * token, including the sixty-second challenge handed out between the password step and the TOTP
 * step. An attacker who knew a password could stop at the challenge and use it as a session, so
 * the second factor was decorative. Every refusal below is a token the old verifier accepted.
 */
class JwtIssuerVerifierTest {

    private static final String SECRET = "issuer-verifier-test-secret-32-chars+++";

    private final JwtProperties properties =
        new JwtProperties(SECRET, "k1", "trove-core", 900, 86_400, 60, 300);
    private final JwtIssuerService issuer = new JwtIssuerService(properties);
    private final JwtVerifier verifier = new JwtVerifier(properties);

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    /**
     * "Now", to the second, from the real clock — not a hardcoded instant.
     *
     * <p>It used to be {@code Instant.parse("2026-09-04T12:00:00Z")}, which made these tests a
     * wall-clock time bomb: {@link JwtVerifier} verifies against the system clock, so every token
     * minted at that fixed instant stopped verifying once real UTC passed it by the token's
     * lifetime plus the sixty-second skew tolerance — 12:16Z on 2026-09-04 — and five tests in
     * this file began failing on a build that had changed nothing. Every assertion below is
     * relative to this value, so taking it from the clock costs no determinism within a run.
     * Truncated to seconds because {@code exp} and {@code iat} are second-precision claims and a
     * sub-second {@code now} would make the equality assertions flaky.
     */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private IssuedToken access() {
        return issuer.issueAccessToken(userId, householdId, "member@trove.test", UserRole.MEMBER,
            familyId, false, null, now);
    }

    @Test
    @DisplayName("An access token round-trips with every claim the principal needs")
    void accessTokenCarriesTenancyAndRole() {
        VerifiedToken verified = verifier.verify(access().value(), TokenType.ACCESS);

        assertThat(verified.type()).isEqualTo(TokenType.ACCESS);
        assertThat(verified.userId()).isEqualTo(userId);
        assertThat(verified.householdId())
            .as("tenancy travels in the signed token, never in a request body (§9 risk 2)")
            .isEqualTo(householdId);
        assertThat(verified.familyId())
            .as("the refresh family travels in the signed token so 'end every other session' can "
                + "name the one to spare without the client handing its refresh token back")
            .isEqualTo(familyId);
        assertThat(verified.email()).isEqualTo("member@trove.test");
        assertThat(verified.role()).isEqualTo("MEMBER");
        assertThat(verified.tokenId()).isNotBlank();
        assertThat(verified.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("A totp_challenge token is refused where an access token is required")
    void challengeTokenIsNotASession() {
        String challenge = issuer.issueTotpChallengeToken(userId, now).value();

        assertThatThrownBy(() -> verifier.verify(challenge, TokenType.ACCESS))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("Invalid or expired token");

        assertThat(verifier.verify(challenge, TokenType.TOTP_CHALLENGE).type())
            .isEqualTo(TokenType.TOTP_CHALLENGE);
    }

    @Test
    @DisplayName("The challenge carries no household — there is nothing it could authorise")
    void challengeTokenCarriesNoTenancy() {
        VerifiedToken verified = verifier.verify(
            issuer.issueTotpChallengeToken(userId, now).value(), TokenType.TOTP_CHALLENGE);

        assertThat(verified.householdId()).isNull();
        assertThat(verified.role()).isNull();
        assertThat(verified.expiresAt()).isEqualTo(now.plusSeconds(60));
    }

    @Test
    @DisplayName("A refresh token is refused as an access token, and the reverse")
    void refreshAndAccessAreNotInterchangeable() {
        String refresh = issuer.issueRefreshToken(userId, now, Duration.ofHours(24)).value();

        assertThatThrownBy(() -> verifier.verify(refresh, TokenType.ACCESS))
            .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> verifier.verify(access().value(), TokenType.REFRESH))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A token from another install sharing the secret is refused on its issuer")
    void rejectsAnotherInstallsToken() {
        JwtProperties other =
            new JwtProperties(SECRET, "k1", "someone-elses-trove", 900, 86_400, 60, 300);
        String foreign = new JwtIssuerService(other).issueAccessToken(userId, householdId,
            "member@trove.test", UserRole.MEMBER, familyId, false, null, now).value();

        assertThatThrownBy(() -> verifier.verify(foreign, TokenType.ACCESS))
            .as("a restored backup or a staging copy of the production .env must not be able to "
                + "mint sessions for production")
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A token stamped with an unknown key id is refused")
    void rejectsAnUnknownKeyId() {
        JwtProperties other =
            new JwtProperties(SECRET, "k99", "trove-core", 900, 86_400, 60, 300);
        String foreign = new JwtIssuerService(other).issueAccessToken(userId, householdId,
            "member@trove.test", UserRole.MEMBER, familyId, false, null, now).value();

        assertThatThrownBy(() -> verifier.verify(foreign, TokenType.ACCESS))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A tampered signature, an expired token and a garbage string all say the same")
    void everyFailureIsIndistinguishable() {
        String token = access().value();
        String tampered = token.substring(0, token.length() - 3) + "aaa";
        String expired = new JwtIssuerService(
            new JwtProperties(SECRET, "k1", "trove-core", 1, 86_400, 60, 300))
            .issueAccessToken(userId, householdId, "m@t.test", UserRole.MEMBER, familyId, false,
                null, now.minus(Duration.ofDays(1))).value();

        for (String candidate : new String[] {tampered, expired, "not-a-jwt", ""}) {
            assertThatThrownBy(() -> verifier.verify(candidate, TokenType.ACCESS))
                .as("a verifier that distinguishes 'expired' from 'bad signature' tells an "
                    + "attacker which half of a forgery worked")
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired token");
        }
    }

    @Test
    @DisplayName("A step-up stamps sua and amr=mfa; an ordinary login does not")
    void stepUpIsRecordedOnTheToken() {
        Instant steppedUp = now.minusSeconds(30);
        IssuedToken token = issuer.issueAccessToken(userId, householdId, "m@t.test",
            UserRole.ADMIN, familyId, true, steppedUp, now);

        VerifiedToken verified = verifier.verify(token.value(), TokenType.ACCESS);
        assertThat(verified.steppedUpAt()).isEqualTo(steppedUp);
        assertThat(verified.role()).isEqualTo("ADMIN");
        assertThat(verifier.verify(access().value(), TokenType.ACCESS).steppedUpAt()).isNull();
    }

    @Test
    @DisplayName("A session minted without a family verifies with no fid, it does not fail")
    void familyClaimIsOptional() {
        IssuedToken token = issuer.issueAccessToken(userId, householdId, "m@t.test",
            UserRole.MEMBER, null, false, null, now);

        assertThat(verifier.verify(token.value(), TokenType.ACCESS).familyId())
            .as("a token minted outside the refresh flow has no family to name; the claim is "
                + "absent rather than the string \"null\"")
            .isNull();
    }

    @Test
    @DisplayName("expiresInSeconds counts down from the moment asked, not from issue")
    void issuedTokenReportsRemainingLifetime() {
        IssuedToken token = access();
        assertThat(token.expiresInSeconds(now)).isEqualTo(900);
        assertThat(token.expiresInSeconds(now.plusSeconds(300))).isEqualTo(600);
    }
}
