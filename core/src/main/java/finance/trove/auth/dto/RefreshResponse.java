package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code data} of {@code POST /v1/auth/refresh}.
 *
 * <p>Bearer mode returns the rotated pair. Cookie mode returns {@code expiresIn} only — the pair
 * went out as {@code Set-Cookie}, and echoing it into a readable body would defeat the point of
 * the {@code HttpOnly} cookie the client chose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(String accessToken, String refreshToken, Long expiresIn) {

    public static RefreshResponse bearer(String accessToken, String refreshToken, long expiresIn) {
        return new RefreshResponse(accessToken, refreshToken, expiresIn);
    }

    public static RefreshResponse cookie(long expiresIn) {
        return new RefreshResponse(null, null, expiresIn);
    }
}
