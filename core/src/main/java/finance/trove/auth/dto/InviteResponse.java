package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One invitation as {@code GET/POST /v1/admin/invites} returns it (handoff §4a point 6).
 *
 * <p>{@code token} and {@code url} are populated <strong>only on the response to the POST that
 * created the invite</strong>. The database stores a SHA-256 hash, exactly as it does for refresh
 * and reset tokens, so a later listing physically cannot reproduce them — an operator with a
 * database dump can revoke an invite but cannot use one. The admin copies the URL once, at
 * creation, which is the same interaction the "no SMTP required" design already assumes.
 *
 * <p>Every field is emitted, nulls included: the web client types {@code email},
 * {@code acceptedAt} and {@code createdByEmail} as {@code string | null}
 * ({@code apps/web/src/api/types.ts:196-206}) rather than optional.
 *
 * @param id             the id {@code DELETE /v1/admin/invites/{id}} takes
 * @param email          the address this invite is bound to, or null for a hand-out link
 * @param token          the raw token, on the creation response only; null afterwards
 * @param url            {@code TROVE_PUBLIC_URL + "/invite/" + token}; null afterwards
 * @param expiresAt      72 hours after creation by default
 * @param createdAt      when the admin minted it
 * @param acceptedAt     when it was redeemed, or null
 * @param createdByEmail the admin who minted it, or null if that account has since been deleted
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InviteResponse(
    UUID id,
    String email,
    String token,
    String url,
    Instant expiresAt,
    Instant createdAt,
    Instant acceptedAt,
    String createdByEmail) {
}
