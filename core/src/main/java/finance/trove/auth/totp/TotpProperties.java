package finance.trove.auth.totp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.totp.*} (gap P2-6).
 *
 * <p>The algorithm parameters are RFC 6238's defaults and are the ones every authenticator app
 * assumes when they are absent from the {@code otpauth://} URI: SHA-1, six digits, a thirty
 * second step. They are exposed as configuration for testability, not for tuning — changing them
 * on a live install invalidates every enrolled authenticator, because the secret alone does not
 * tell the app which digest to use.
 *
 * @param issuer         the label an authenticator app shows; appears twice in the otpauth URI
 * @param digits         code length
 * @param periodSeconds  time step
 * @param allowedDrift   how many steps either side of "now" are accepted — 1 gives the ±30 s
 *                       tolerance a phone with a slightly wrong clock needs, without widening
 *                       the window an attacker can replay in
 * @param recoveryCodes  how many single-use recovery codes are issued at confirmation
 */
@ConfigurationProperties(prefix = "trove.auth.totp")
public record TotpProperties(
    String issuer,
    int digits,
    int periodSeconds,
    int allowedDrift,
    int recoveryCodes) {

    public TotpProperties {
        issuer = issuer == null || issuer.isBlank() ? "Trove" : issuer;
        digits = digits <= 0 ? 6 : digits;
        periodSeconds = periodSeconds <= 0 ? 30 : periodSeconds;
        allowedDrift = allowedDrift < 0 ? 1 : allowedDrift;
        recoveryCodes = recoveryCodes <= 0 ? 10 : recoveryCodes;
    }
}
