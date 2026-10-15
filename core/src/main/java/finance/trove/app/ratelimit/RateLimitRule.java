package finance.trove.app.ratelimit;

/**
 * One row of {@code rate_limit_config} (V12): an Ant pattern and the token bucket it describes.
 *
 * <p>The pattern is matched against the <strong>full request URI</strong>, servlet context path
 * included ({@code /api/v1/auth/login}), because that is the string an operator sees in a log line
 * and in {@code audit_log.path} — a table whose patterns silently dropped the {@code /api} prefix
 * would be edited wrongly the first time somebody added a row by hand.
 *
 * @param pattern           Ant pattern, e.g. {@code /api/v1/auth/**}
 * @param requestsPerMinute greedy refill rate
 * @param burstCapacity     bucket maximum: what a caller may spend at once after a quiet period
 */
public record RateLimitRule(String pattern, int requestsPerMinute, int burstCapacity) {

    public RateLimitRule {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("a rate limit rule needs a pattern");
        }
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException(
                "requestsPerMinute must be positive for " + pattern + ", got " + requestsPerMinute);
        }
        if (burstCapacity < requestsPerMinute) {
            // A capacity below the refill rate is a bucket that can never fill: the effective
            // limit becomes the capacity and the configured rate is a lie. V12 has a CHECK
            // constraint saying the same thing; this is the half that holds for a row inserted
            // before the constraint existed, or by a future migration that forgets it.
            throw new IllegalArgumentException("burstCapacity must be >= requestsPerMinute for "
                + pattern + ", got " + burstCapacity + " < " + requestsPerMinute);
        }
    }
}
