package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One invitation as {@code GET/POST /v1/admin/invites} returns it (handoff §4a point 6).
 *
 * <p>{@code token} and {@code url} are populated while the invite can still be redeemed: on the
 * response to the POST that created it and on every later GET, until it is accepted, revoked or
 * expired, after which both are null. The database keeps a SHA-256 hash for lookup and, since
 * V9_2, the token encrypted with the field-encryption key (household_invite.token_encrypted),
 * which {@code InviteService#list} decrypts for a still-usable invite.
 *
 * <p>Every field is emitted, nulls included: the web client types {@code email},
 * {@code acceptedAt} and {@code createdByEmail} as {@code string | null}
 * ({@code apps/web/src/api/types.ts:196-206}) rather than optional.
 *
 * @param id             the id {@code DELETE /v1/admin/invites/{id}} takes
 * @param email          the address this invite is bound to, or null for a hand-out link
 * @param token          the raw token while the invite is redeemable, else null
 * @param url            {@code TROVE_PUBLIC_URL + "/invite/" + token}; null once spent
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
