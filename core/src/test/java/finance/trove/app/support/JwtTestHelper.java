package finance.trove.app.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Mints Trove access tokens for integration tests — one helper for the whole monolith, in place
 * of the nine per-service copies (§5.14).
 *
 * <p>The claim set is the one {@code JwtIssuerService} will produce from collapse step 3, so
 * tests written now stay valid then: the gateway's existing {@code sub}/{@code email}/
 * {@code roles}/{@code iat}/{@code exp} shape, plus the {@code typ}, {@code iss} and {@code jti}
 * claims that §1.5 adds. {@code typ} is the load-bearing addition: {@code JwtAuthenticationProvider}
 * must require {@code typ=access} on every protected route, otherwise a 60-second
 * {@code totp_challenge} token is accepted as a full session and the second factor is
 * bypassable (§9 risk 28). {@link #totpChallengeToken} exists so that
 * {@code JwtTypeConfusionIT} can prove the refusal.
 *
 * <p>The issuer literal below is the reference value: step 3's {@code JwtIssuerService} and
 * {@code JwtProperties} must use the same string.
 */
public final class JwtTestHelper {

    /** 44 chars — comfortably over the 32-char floor JwtProperties.validate() enforces. */
    public static final String SECRET = "trove-core-integration-test-secret-32-chars+";

    public static final String ISSUER = "trove-core";

    /**
     * The {@code kid} header {@code JwtVerifier} requires. A token minted without it — which is
     * what this helper produced before collapse step 3 — is rejected, which is the point: the key
     * id exists so a second signing key can be introduced later without logging everyone out.
     */
    public static final String KEY_ID = "k1";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_TOTP_CHALLENGE = "totp_challenge";

    private static final long ACCESS_TTL_MS = 900_000L;
    private static final long CHALLENGE_TTL_MS = 60_000L;

    private JwtTestHelper() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A standard user access token for a synthetic household.
     *
     * <p>The household id is generated, so a token from this overload authenticates but owns
     * nothing — which is exactly what a cross-tenant probe wants. Tests that need a token for a
     * real account use {@link #accessToken(UUID, UUID, String, String)}.
     */
    public static String accessToken(UUID userId) {
        return accessToken(userId, UUID.randomUUID(), userId + "@trove.test", "MEMBER");
    }

    /** A household-admin access token for a synthetic household. */
    public static String adminToken(UUID userId) {
        return accessToken(userId, UUID.randomUUID(), userId + "@trove.test", "ADMIN");
    }

    /**
     * A full access token.
     *
     * <p>{@code hid} is not optional: {@code TroveUserDetails} rejects a null household, because
     * tenancy travels in the signed token and never in a request body (§9 risk 2). {@code role}
     * is the household-scoped MEMBER/ADMIN flag the security chain's admin matcher reads.
     */
    public static String accessToken(UUID userId, UUID householdId, String email, String role) {
        return accessToken(userId, householdId, email, role, null);
    }

    /**
     * A full access token naming the refresh family it belongs to, or none when {@code familyId}
     * is null.
     *
     * <p>{@code fid} is what lets "end every other session" spare the caller without asking a
     * bearer client to hand its refresh token back ({@code AuthService#changePassword}), and it
     * is what {@code SessionValidity} asks about on every request: a token whose family has been
     * revoked no longer authenticates anything.
     *
     * <p>Which is why the overloads above pass <strong>null</strong> rather than a random id. A
     * synthetic family id names a family that has no rows, and a family with no rows is not a
     * live session — a token minted with one would be refused by the filter before it reached
     * the route under test, and every such test would be asserting the wrong refusal. A token
     * with no {@code fid} at all is the honest description of what this helper mints: a session
     * that was never opened through the refresh flow. Tests that want the family check to bite
     * pass a real one.
     */
    public static String accessToken(UUID userId, UUID householdId, String email, String role,
                                     UUID familyId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("hid", householdId.toString());
        if (familyId != null) {
            claims.put("fid", familyId.toString());
        }
        claims.put("email", email);
        claims.put("role", role);
        claims.put("roles", List.of("ROLE_USER", "ROLE_" + role));
        claims.put("amr", List.of("pwd"));
        return token(userId, claims, TYPE_ACCESS, ACCESS_TTL_MS);
    }

    /** An access token whose {@code role} claim names a role this build does not have. */
    public static String tokenWithUnknownRole(UUID userId, UUID householdId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("hid", householdId.toString());
        claims.put("email", userId + "@trove.test");
        claims.put("role", "SUPERUSER");
        return token(userId, claims, TYPE_ACCESS, ACCESS_TTL_MS);
    }

    /**
     * The short-lived token issued between password and TOTP verification. Valid only on
     * {@code POST /v1/auth/login/totp}; anything else must answer 401.
     */
    public static String totpChallengeToken(UUID userId) {
        return token(userId, Map.of("amr", List.of("pwd")), TYPE_TOTP_CHALLENGE, CHALLENGE_TTL_MS);
    }

    /** A refresh token, for asserting that one is refused on a protected route. */
    public static String refreshToken(UUID userId) {
        return token(userId, Map.of(), "refresh", ACCESS_TTL_MS);
    }

    /** A token signed with the right key but stamped with a key id this install does not know. */
    public static String tokenWithForeignKeyId(UUID userId, UUID householdId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .header().keyId("someone-elses-key").and()
            .issuer(ISSUER)
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .claim("hid", householdId.toString())
            .claim("typ", TYPE_ACCESS)
            .issuedAt(new Date(now))
            .expiration(new Date(now + ACCESS_TTL_MS))
            .signWith(key())
            .compact();
    }

    /** A token from a different install that happens to share this one's signing secret. */
    public static String tokenWithForeignIssuer(UUID userId, UUID householdId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .header().keyId(KEY_ID).and()
            .issuer("someone-elses-trove")
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .claim("hid", householdId.toString())
            .claim("typ", TYPE_ACCESS)
            .issuedAt(new Date(now))
            .expiration(new Date(now + ACCESS_TTL_MS))
            .signWith(key())
            .compact();
    }

    private static String token(UUID userId, Map<String, Object> claims, String type,
                                long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .header().keyId(KEY_ID).and()
            .issuer(ISSUER)
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .claims(claims)
            .claim("typ", type)
            .issuedAt(new Date(now))
            .expiration(new Date(now + ttlMillis))
            .signWith(key())
            .compact();
    }

    private static SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }
}
