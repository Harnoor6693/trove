package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * {@code GET /v1/auth/invites/{token}} — public, unauthenticated (handoff §4a point 6).
 *
 * <p>It answers 200 whatever the verdict, with {@code valid} carrying it. A 404 for an unknown
 * token would make this endpoint an oracle for guessing token values, and the page that calls it
 * has nothing better to do with a 404 than render the same "this link is no longer valid" copy
 * it renders for {@code valid: false}.
 *
 * <p>{@code email} is echoed only when the invite is both valid and address-bound, so that the
 * registration form can pre-fill and lock the field. On an invalid token it is null — an invalid
 * token must not disclose whom it was for.
 *
 * @param valid     whether registering with this token would be accepted right now
 * @param email     the bound address when there is one and the token is valid; otherwise null
 * @param expiresAt the deadline when the token is valid; otherwise null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InvitePreviewResponse(boolean valid, String email, Instant expiresAt) {

    /** Unknown, expired, revoked or already redeemed — one answer for all four. */
    public static InvitePreviewResponse invalid() {
        return new InvitePreviewResponse(false, null, null);
    }
}
