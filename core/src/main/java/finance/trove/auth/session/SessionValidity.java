package finance.trove.auth.session;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers, for one authenticated request, whether the session behind the access token is still a
 * session at all.
 *
 * <p><strong>Why a database read on the authentication path.</strong> Every revocation this
 * application performs — logout, "sign out my other devices", a password change, a redeemed reset
 * link, a detected refresh replay, deleting a member — writes to {@code refresh_token} rows and
 * nothing else. An access token is a signed bearer statement that is checked only against its own
 * signature and expiry, so before this class existed a revoked session kept full read/write access
 * to every route for the remainder of the token's fifteen minutes. That is a defensible design in
 * general, and wrong for the specific promises made here:
 * {@code AuthService#changePassword} tells the user "anybody who had the old password and a live
 * session on another device loses it immediately", and a password <em>reset</em> is what somebody
 * does when they believe an attacker already has their password. Fifteen minutes of continued API
 * access is exactly the window those two features exist to close. The same window let a
 * just-deleted administrator go on minting invites for themselves.
 *
 * <p><strong>The check is on the family, not the user.</strong> {@code fid} names the refresh
 * family the session belongs to, so "end every session except this one" stays expressible: the
 * caller's own family is untouched and keeps working, every other family's access tokens stop at
 * the next request. A user-level cut-off (a {@code sessions_valid_from} stamp) would have been one
 * fewer join and would have signed out the device that just changed its own password — the exact
 * regression the {@code fid} claim was added to prevent.
 *
 * <p><strong>A family with no rows is not live.</strong> That covers a deleted account, whose
 * {@code refresh_token} rows go with it through {@code ON DELETE CASCADE}, and a family whose rows
 * have been purged. A token whose {@code fid} claim is absent is accepted: it was minted outside
 * the refresh flow (or before the claim existed), there is no family to ask about, and every path
 * that issues one today — login, registration, refresh, the OIDC callback — opens the family
 * first. It cannot be forged: an attacker who could omit a claim could sign a token outright.
 *
 * <p>No cache. One indexed lookup on {@code refresh_token_family_id_idx} per authenticated
 * request is cheap next to the argon2id verification the same session paid for at login, and a
 * cache is a window in which a revoked session still works — which is the whole defect.
 */
@Service
public class SessionValidity {

    private final RefreshTokenRepository tokens;

    public SessionValidity(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    /**
     * @param familyId the {@code fid} claim of an access token, or null when it carries none
     * @return whether a request presenting that token should still be authenticated
     */
    @Transactional(readOnly = true)
    public boolean isLive(UUID familyId) {
        return familyId == null || tokens.existsByFamilyIdAndRevokedAtIsNull(familyId);
    }
}
