package finance.trove.app.ratelimit;

/**
 * The limiter could not reach a decision at all — as distinct from deciding to refuse.
 *
 * <p>{@link RateLimitFilter} catches this (with every other {@code RuntimeException}) and answers
 * <strong>503</strong> on {@code /v1/auth/**} and passes everything else through: §5.8's asymmetry.
 * It exists as a named type so that the one condition which is <em>not</em> an exception anywhere
 * else — "no rules have ever been loaded" — cannot be mistaken for "no rule matches this path",
 * which is an ordinary, unlimited answer.
 *
 * <p>The distinction is the whole of confirmed review finding 1: before this type existed,
 * {@code RateLimitRules} started life holding {@code List.of()}, an empty list matched nothing,
 * and every route including the sign-in surface was unmetered until the first successful reload —
 * for ever, if the very first reload failed. The fail-closed guarantee published in
 * {@code openapi/trove-core.yaml} as {@code LimiterUnavailable} was therefore reachable only when
 * the bucket machinery threw, which is not the failure that actually happens.
 */
public class RateLimitUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public RateLimitUnavailableException(String message) {
        super(message);
    }
}
