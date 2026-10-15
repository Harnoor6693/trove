package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code data} of every successful authentication, in all three of its shapes
 * (handoff §4a points 4 and 5).
 *
 * <p>One record rather than three, with {@link JsonInclude.Include#NON_NULL}, because the clients
 * discriminate on <em>presence</em>:
 * <ul>
 *   <li><strong>bearer</strong> — {@code {user, accessToken, refreshToken, expiresIn}};</li>
 *   <li><strong>cookie</strong> — {@code {user}} alone; the tokens are in {@code __Host-} cookies
 *       and must not also be in a body a script can read. Note that this is still an object:
 *       cookie-mode success never returns {@code data: null} (handoff point 5), because the SPA
 *       reads {@code data.user} directly out of the login response to seed its store;</li>
 *   <li><strong>TOTP owed</strong> — {@code {challengeToken}} alone. The client tests for this
 *       field to decide whether to show the code screen, so it must be absent, not null, in the
 *       other two shapes.</li>
 * </ul>
 *
 * @param user         the authenticated user; absent only on the TOTP challenge
 * @param accessToken  bearer mode only
 * @param refreshToken bearer mode only
 * @param expiresIn    access-token lifetime in seconds, bearer mode only
 * @param challengeToken present only when a second factor is still owed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
    AuthUser user,
    String accessToken,
    String refreshToken,
    Long expiresIn,
    String challengeToken) {

    /** Bearer mode: the client stores both tokens itself. */
    public static LoginResponse bearer(AuthUser user, String accessToken, String refreshToken,
                                       long expiresIn) {
        return new LoginResponse(user, accessToken, refreshToken, expiresIn, null);
    }

    /** Cookie mode: the tokens went out as {@code Set-Cookie} and appear nowhere in the body. */
    public static LoginResponse cookie(AuthUser user) {
        return new LoginResponse(user, null, null, null, null);
    }

    /** Password accepted, second factor still owed. */
    public static LoginResponse challenge(String challengeToken) {
        return new LoginResponse(null, null, null, null, challengeToken);
    }
}
