package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/refresh} and {@code POST /v1/auth/logout}.
 *
 * <p>The field is optional because cookie-mode clients send no body at all: their refresh token is
 * in {@code __Host-trove_refresh}, which the browser attaches. Bearer clients put it here. The
 * controller reads the body first and falls back to the cookie, so one route serves both without
 * a mode header.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshRequest(@Size(max = 4096) String refreshToken) {
}
