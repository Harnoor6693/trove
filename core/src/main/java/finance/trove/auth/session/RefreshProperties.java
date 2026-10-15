package finance.trove.auth.session;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.refresh.*} — the two session limits that are not the token's own
 * expiry ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5).
 *
 * <p>The <strong>absolute lifetime</strong> is deliberately not here: it is
 * {@code trove.jwt.refresh-expiry-seconds}, which §1.5 fixes at 24 hours, and which is the
 * {@code exp} stamped on the family's first token and inherited by every successor. One concept,
 * one knob.
 *
 * @param idleTimeout how long a session may go unused before it dies, independently of the
 *                    absolute ceiling. A laptop closed at lunchtime should not still hold a
 *                    working session at midnight because the absolute clock had not run out
 * @param reuseGrace  the window in which re-presenting a just-rotated token returns the same
 *                    successor instead of revoking the family. Without it, two browser tabs that
 *                    both hit a 401 and both refresh — or one request that is retried — look
 *                    exactly like token theft, and the household gets logged out for using the
 *                    application normally (§1.5, and the reviewers' Client-Contract finding 13 /
 *                    Security finding 7, reported independently)
 */
@ConfigurationProperties(prefix = "trove.auth.refresh")
public record RefreshProperties(Duration idleTimeout, Duration reuseGrace) {

    public RefreshProperties {
        idleTimeout = idleTimeout == null ? Duration.ofHours(12) : idleTimeout;
        reuseGrace = reuseGrace == null ? Duration.ofSeconds(30) : reuseGrace;
    }
}
