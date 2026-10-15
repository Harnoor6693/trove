package finance.trove.auth.dto;

import java.time.Instant;

/**
 * The link an admin copies. Returned once, to the admin who asked for it, and never stored in a
 * readable form — {@code password_reset_token} holds a SHA-256 hash (V9.1).
 *
 * @param url       the absolute URL the member opens
 * @param expiresAt one hour out (§1.5, gap P2-8)
 */
public record PasswordResetLinkResponse(String url, Instant expiresAt) {
}
