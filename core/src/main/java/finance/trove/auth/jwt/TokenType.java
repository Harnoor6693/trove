package finance.trove.auth.jwt;

import java.util.Arrays;
import java.util.Optional;

/**
 * The value of the {@code typ} claim every Trove-issued token carries
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §9 risk 28).
 *
 * <p>This enum exists to close a type-confusion hole that is a complete second-factor bypass in
 * the pre-collapse gateway: its verifier accepted <em>any</em> HS256 token carrying a {@code sub}
 * as a full access token, including the 60-second challenge token handed out between the password
 * step and the TOTP step. An attacker who knew a password could therefore stop at the challenge
 * token and use it as a session.
 *
 * <p>Every consumer names the type it will accept — {@link JwtVerifier#verify} takes one — so
 * "which tokens does this route accept?" is answered at the call site rather than by omission.
 */
public enum TokenType {

    /** A full session token. The only type any protected route accepts. */
    ACCESS("access"),

    /** Exchanged at {@code POST /v1/auth/refresh} for a new pair; never authenticates a route. */
    REFRESH("refresh"),

    /** Valid only at {@code POST /v1/auth/login/totp}; carries no roles and lives 60 seconds. */
    TOTP_CHALLENGE("totp_challenge");

    private final String claim;

    TokenType(String claim) {
        this.claim = claim;
    }

    /** The wire value of the {@code typ} claim. */
    public String claim() {
        return claim;
    }

    static Optional<TokenType> fromClaim(String value) {
        return Arrays.stream(values()).filter(t -> t.claim.equals(value)).findFirst();
    }
}
