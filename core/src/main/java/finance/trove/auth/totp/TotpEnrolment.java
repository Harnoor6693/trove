package finance.trove.auth.totp;

/**
 * What {@code POST /v1/auth/totp/setup} hands back: the shared secret in the two forms a client
 * needs, and nothing else.
 *
 * <p>No recovery codes here. They are issued only at {@code POST /v1/auth/totp/confirm}, after a
 * live code has proved the authenticator actually works — issuing them earlier would hand out ten
 * bypasses for a factor that may never be enabled.
 *
 * @param secret      base32, for manual entry when a camera is not available
 * @param otpauthUri  the {@code otpauth://} URI to render as a QR code
 * @param digits      code length, so the client can size its input
 * @param periodSeconds  time step, so the client can show a countdown
 */
public record TotpEnrolment(String secret, String otpauthUri, int digits, int periodSeconds) {
}
