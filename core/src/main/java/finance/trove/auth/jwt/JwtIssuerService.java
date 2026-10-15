package finance.trove.auth.jwt;

import finance.trove.users.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * The one and only caller of {@code Jwts.builder()} in the monolith
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §2.1).
 *
 * <p>Every token it issues carries, beyond the gateway's original {@code sub}/{@code email}/
 * {@code roles}:
 * <ul>
 *   <li><strong>{@code typ}</strong> — {@code access}, {@code refresh} or {@code totp_challenge}.
 *       Required by {@link JwtVerifier} on every consumer, which is what makes a challenge token
 *       useless as a session (§9 risk 28).</li>
 *   <li><strong>{@code kid} (header)</strong> — names the signing key. HS256 with a single key
 *       today; the header is emitted now so a second key can be introduced later and old tokens
 *       stay verifiable through their natural expiry instead of everyone being logged out.</li>
 *   <li><strong>{@code iss} and {@code jti}</strong> — the issuer this install verifies against,
 *       and a unique id per token so a session can be named in an audit row without the row
 *       containing the token.</li>
 *   <li><strong>{@code hid}</strong> — the household. Tenancy travels in the signed token, so no
 *       request body or path variable is ever an input to a scoping decision (§9 risk 2).</li>
 *   <li><strong>{@code amr} and {@code sua}</strong> — how the session authenticated
 *       ({@code pwd}, {@code otp}, {@code mfa}) and when it last stepped up, for the sensitive
 *       actions of §1.5.</li>
 * </ul>
 *
 * <p>Refresh tokens are JWTs here rather than the gateway's opaque random strings. The reason is
 * the same {@code typ} rule: an opaque string has no type, so "this is a refresh token, not an
 * access token" would have to be inferred from which table it appears in — and a lookup miss
 * would be indistinguishable from a wrong type. As a signed token, a refresh presented on a
 * protected route is rejected by signature-and-type before any database work happens. What is
 * stored is still only a SHA-256 hash of it (V7).
 */
@Service
public class JwtIssuerService {

    /** {@code amr} value for a password login. */
    public static final String AMR_PASSWORD = "pwd";

    /** {@code amr} value for a completed TOTP step. */
    public static final String AMR_OTP = "otp";

    /** {@code amr} value marking a step-up re-authentication (§1.5). */
    public static final String AMR_MFA = "mfa";

    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_HOUSEHOLD = "hid";
    static final String CLAIM_FAMILY = "fid";
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_ROLES = "roles";
    static final String CLAIM_AMR = "amr";
    static final String CLAIM_STEPPED_UP_AT = "sua";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtIssuerService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A full session token.
     *
     * @param familyId    the refresh-token family this session belongs to. Carried so that an
     *                    action which must end "every OTHER session" can name the one to spare
     *                    without the caller having to hand its refresh token back — see
     *                    {@code AuthService#changePassword}. Nullable only for a session minted
     *                    outside the refresh flow
     * @param steppedUpAt when this session last completed a step-up, or {@code null}; when
     *                    present the token additionally carries {@code amr=mfa}
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IssuedToken issueAccessToken(UUID userId, UUID householdId, String email, UserRole role,
                                        UUID familyId, boolean otpUsed, Instant steppedUpAt,
                                        Instant now) {
        List<String> amr = steppedUpAt != null
            ? List.of(AMR_PASSWORD, AMR_OTP, AMR_MFA)
            : otpUsed ? List.of(AMR_PASSWORD, AMR_OTP) : List.of(AMR_PASSWORD);
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put(CLAIM_HOUSEHOLD, householdId.toString());
        if (familyId != null) {
            claims.put(CLAIM_FAMILY, familyId.toString());
        }
        claims.put(CLAIM_EMAIL, email);
        claims.put(CLAIM_ROLE, role.name());
        claims.put(CLAIM_ROLES, List.of("ROLE_USER", role.authority()));
        claims.put(CLAIM_AMR, amr);
        if (steppedUpAt != null) {
            claims.put(CLAIM_STEPPED_UP_AT, steppedUpAt.getEpochSecond());
        }
        return build(TokenType.ACCESS, userId, claims, properties.accessTtl(), now);
    }

    /** The token exchanged at {@code POST /v1/auth/refresh}; only its SHA-256 hash is stored. */
    public IssuedToken issueRefreshToken(UUID userId, Instant now, Duration ttl) {
        return build(TokenType.REFRESH, userId, Map.of(), ttl, now);
    }

    /**
     * The 60-second token handed out when a password is right but TOTP is still owed.
     *
     * <p>It carries no household and no roles on purpose: there is nothing it could authorise,
     * and the smaller its claim set the less a leaked one is worth.
     */
    public IssuedToken issueTotpChallengeToken(UUID userId, Instant now) {
        return build(TokenType.TOTP_CHALLENGE, userId, Map.of(CLAIM_AMR, List.of(AMR_PASSWORD)),
            properties.totpChallengeTtl(), now);
    }

    private IssuedToken build(TokenType type, UUID subject, Map<String, Object> claims,
                              Duration ttl, Instant now) {
        String jti = UUID.randomUUID().toString();
        Instant expiry = now.plus(ttl);
        String compact = Jwts.builder()
            .header().keyId(properties.keyId()).and()
            .issuer(properties.issuer())
            .id(jti)
            .subject(subject.toString())
            .claims(claims)
            .claim(CLAIM_TYPE, type.claim())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(key)
            .compact();
        return new IssuedToken(compact, jti, expiry);
    }
}
