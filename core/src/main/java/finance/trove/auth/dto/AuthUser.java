package finance.trove.auth.dto;

import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.util.UUID;

/**
 * The user object every successful authentication returns inside the envelope
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5; handoff §4a point 4).
 *
 * <p>It exists so that a client has a session to persist without a second round trip. Mobile's
 * session store needs {@code id} and a display name the moment login returns, and the pre-collapse
 * login body carried neither — the client had to call {@code /auth/me} before it could render a
 * name, which is one avoidable failure point on the least forgiving screen in the app.
 *
 * <p>It is deliberately smaller than {@link MeResponse}: preferences change, and a copy of them
 * frozen into a login response is a copy that goes stale. {@code GET /v1/auth/me} is the
 * authoritative read.
 */
public record AuthUser(
    UUID id,
    String email,
    String displayName,
    UUID householdId,
    UserRole role,
    boolean totpEnabled) {

    public static AuthUser of(User user, boolean totpEnabled) {
        return new AuthUser(user.getId(), user.getEmail(), user.getDisplayName(),
            user.getHouseholdId(), user.getRole(), totpEnabled);
    }
}
