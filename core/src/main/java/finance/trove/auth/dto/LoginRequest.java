package finance.trove.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/login}.
 *
 * <p>The password is bounded but never validated against the policy here. A login is a
 * <em>comparison</em>, not a registration: telling a caller that their attempt "does not meet the
 * password policy" confirms the attempt was not the stored password, and does it without spending
 * an argon2 verification, which is a free oracle. The only bound is a length cap, so that a
 * megabyte of input cannot be turned into a memory-hard denial of service.
 */
public record LoginRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 1024) String password) {
}
