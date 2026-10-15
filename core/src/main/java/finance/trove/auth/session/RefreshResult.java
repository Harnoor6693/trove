package finance.trove.auth.session;

import java.util.UUID;

/**
 * The outcome of a successful rotation.
 *
 * @param userId    whose session this is
 * @param familyId  the family the new token belongs to
 * @param token     the new refresh token, in plaintext, for this response only
 * @param replayed  {@code true} when this call re-presented a just-rotated token inside the grace
 *                  window and was served the successor that the first call minted, rather than a
 *                  new one. Two tabs racing therefore converge on one token instead of forking
 *                  the family or tripping reuse detection (§1.5)
 */
public record RefreshResult(UUID userId, UUID familyId, finance.trove.auth.jwt.IssuedToken token,
                            boolean replayed) {
}
