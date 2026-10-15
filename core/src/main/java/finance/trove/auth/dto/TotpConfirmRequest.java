package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /v1/auth/totp/confirm} — one live code proves the authenticator actually works. */
public record TotpConfirmRequest(@NotBlank @Size(max = 32) String code) {
}
