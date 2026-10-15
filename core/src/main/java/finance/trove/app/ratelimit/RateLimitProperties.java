package finance.trove.app.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The limiter's own settings ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, §5.8). The
 * <em>limits</em> are not here: they live in {@code rate_limit_config} (V12) so an admin can
 * retune one on a running install.
 *
 * @param enabled          master switch, {@code TROVE_RATE_LIMIT_ENABLED}. Default true, and the
 *                         pre-collapse gateway's version of this flag was never read at all
 *                         (gateway finding NEW-1) — {@link RateLimitFilter} reads this one on
 *                         every request, so switching it off is immediate and provable
 * @param reloadInterval   how often the rules are re-read from the table (§5.6
 *                         {@code rate-limit-reload}). Also the worst-case delay before an admin's
 *                         edit takes effect
 * @param bucketTtl        how long an idle bucket is kept. A bucket dropped while a caller is
 *                         quiet costs nothing — it comes back full, and a caller who has been
 *                         silent for this long would have refilled anyway
 * @param maxBuckets       ceiling on live buckets, so that a flood from many addresses cannot
 *                         turn the limiter itself into the memory exhaustion it exists to prevent
 * @param retryAfterSeconds the {@code Retry-After} sent with a refusal
 */
@ConfigurationProperties(prefix = "trove.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    Duration reloadInterval,
    Duration bucketTtl,
    int maxBuckets,
    int retryAfterSeconds) {

    public RateLimitProperties {
        reloadInterval = reloadInterval == null ? Duration.ofMinutes(5) : reloadInterval;
        bucketTtl = bucketTtl == null ? Duration.ofMinutes(10) : bucketTtl;
        maxBuckets = maxBuckets <= 0 ? 100_000 : maxBuckets;
        retryAfterSeconds = retryAfterSeconds <= 0 ? 60 : retryAfterSeconds;
        if (reloadInterval.isNegative() || reloadInterval.isZero()) {
            throw new IllegalArgumentException(
                "trove.rate-limit.reload-interval must be positive, got " + reloadInterval);
        }
        if (bucketTtl.isNegative() || bucketTtl.isZero()) {
            throw new IllegalArgumentException(
                "trove.rate-limit.bucket-ttl must be positive, got " + bucketTtl);
        }
    }
}
