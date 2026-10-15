package finance.trove.ai;

import finance.trove.app.HttpClientProperties;
import java.time.Duration;

/**
 * Turns a member's requested timeouts into the ones a call may actually use.
 *
 * <p>The per-user values exist because a 7B model on a home server legitimately takes two minutes
 * to answer where a cloud endpoint takes two seconds, and one install-wide number cannot serve
 * both. The cap exists because a per-user value is a per-user way to hold a request thread: without
 * one, a member could set ten minutes and pin a thread for ten minutes, and enough members doing
 * that is the whole thread pool.
 *
 * <p><strong>Connect</strong> is capped at the install's {@code TROVE_HTTP_CONNECT_TIMEOUT_MS}
 * exactly. Establishing a TCP connection is not slower for a big model, so there is no case for
 * letting a member raise it.
 *
 * <p><strong>Read</strong> is capped at {@link #READ_TIMEOUT_MULTIPLIER} times the install's
 * {@code TROVE_HTTP_READ_TIMEOUT_MS}, which is 120 s against the 30 s default — the figure §5.5
 * names for AI, arrived at from the install's own setting rather than hard-coded, so an operator
 * who lowers the general read timeout lowers this with it. A flat cap at the install's read
 * timeout would be wrong in the other direction: it would hold every AI call to the 30 s meant for
 * ordinary JSON calls and make a local model unusable, which is the primary story.
 */
public final class AiTimeouts {

    /** 4 × 30 s = the 120 s §5.5 names for AI. */
    static final int READ_TIMEOUT_MULTIPLIER = 4;

    private AiTimeouts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The effective connect timeout: what was asked for, or the install's limit if that is less. */
    public static Duration connect(int requestedMs, HttpClientProperties install) {
        return Duration.ofMillis(Math.min(requestedMs, install.connectTimeoutMs()));
    }

    /** The effective read timeout: what was asked for, or the AI ceiling if that is less. */
    public static Duration read(int requestedMs, HttpClientProperties install) {
        long ceiling = (long) install.readTimeoutMs() * READ_TIMEOUT_MULTIPLIER;
        return Duration.ofMillis(Math.min(requestedMs, ceiling));
    }
}
