package finance.trove.auth;

import finance.trove.auth.jwt.IssuedToken;
import finance.trove.users.User;

/**
 * What an authentication attempt produced, before anything has been decided about cookies or
 * response bodies.
 *
 * <p>Two shapes, because a login has two possible successful endings and only one of them is a
 * session. Keeping them apart in the type means the controller cannot accidentally set session
 * cookies for a caller who has proved only half of what they owe — which is the same
 * type-confusion hole, one layer up, that the {@code typ} claim closes in the token (§9 risk 28).
 */
public sealed interface LoginOutcome {

    /**
     * Fully authenticated.
     *
     * @param user        the account, freshly read
     * @param totpEnabled whether this account has a second factor, for the client's settings view
     * @param access      the access token
     * @param refresh     the refresh token that opens (or continues) this session's family
     */
    record Session(User user, boolean totpEnabled, IssuedToken access, IssuedToken refresh)
        implements LoginOutcome {
    }

    /**
     * Password accepted, second factor still owed.
     *
     * @param challengeToken a 60-second {@code typ=totp_challenge} JWT, valid only at
     *                       {@code POST /v1/auth/login/totp}
     */
    record TotpRequired(String challengeToken) implements LoginOutcome {
    }
}
