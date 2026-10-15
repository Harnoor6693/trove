package finance.trove.app.ratelimit;

/**
 * What the limiter decided about one request.
 *
 * @param allowed           whether the request may proceed
 * @param retryAfterSeconds seconds to wait before retrying; meaningful only when refused
 * @param rule              the rule that was applied, or {@code null} when none matched and the
 *                          request was allowed unconditionally. Carried so the refusal can be
 *                          logged against the pattern an operator would edit
 */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds, RateLimitRule rule) {

    private static final RateLimitDecision UNLIMITED = new RateLimitDecision(true, 0, null);

    /** No rule matched: the path is not limited at all. */
    public static RateLimitDecision unlimited() {
        return UNLIMITED;
    }

    public static RateLimitDecision allowed(RateLimitRule rule) {
        return new RateLimitDecision(true, 0, rule);
    }

    public static RateLimitDecision refused(RateLimitRule rule, int retryAfterSeconds) {
        return new RateLimitDecision(false, retryAfterSeconds, rule);
    }
}
