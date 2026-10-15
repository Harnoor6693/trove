package finance.trove.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * {@code PUT /v1/me} — the two identity fields a user may change about themselves (§4.2).
 *
 * <p>Not here, on purpose: {@code email} (it is the login identifier and changing it needs a
 * verification step this install has no mail server for), {@code currency} and {@code country}
 * (every historical transaction, budget and export assumes a stable reporting currency), and
 * {@code role} (a member cannot promote themselves).
 */
public record UpdateMeRequest(
    @Size(max = 100) String displayName,
    @Size(max = 64) String timezone) {
}
