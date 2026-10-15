package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/login/totp} — the second half of a two-factor login.
 *
 * <p>{@code challengeToken} is the {@code typ=totp_challenge} JWT the first half returned. It is
 * the only credential this route accepts: a full access token presented here is rejected by
 * {@code JwtVerifier}, and the challenge token is rejected everywhere else (§9 risk 28).
 *
 * <p>It is <strong>optional in the body</strong>, and that is not a relaxation. The password
 * login hands the token to client code, which puts it back here; the OIDC login ends in a
 * full-page redirect with no client code in the loop, so its challenge travels in the
 * {@code __Host-trove_totp_challenge} HttpOnly cookie instead
 * ({@code TroveOidcSuccessHandler}). The controller reads the body first and the cookie second,
 * and a request carrying neither is refused exactly as a bad token is — the credential is still
 * required, only its carrier varies.
 *
 * <p>{@code code} is either six digits from an authenticator or one of the ten recovery codes.
 * One field for both, because the user does not think of them as different kinds of thing — they
 * think "the app is gone, I have the paper".
 */
public record TotpLoginRequest(
    @Size(max = 4096) String challengeToken,
    @NotBlank @Size(max = 32) String code) {

    /** The token from the body, or {@code null} when the caller left it to the cookie. */
    public String presentedChallengeToken() {
        return challengeToken == null || challengeToken.isBlank() ? null : challengeToken;
    }
}
