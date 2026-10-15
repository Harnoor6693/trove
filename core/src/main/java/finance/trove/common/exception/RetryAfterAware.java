package finance.trove.common.exception;

/**
 * Implemented by a {@link TroveException} that knows how long the caller should wait.
 *
 * <p>The sibling of {@link FieldAwareException}, and for the same reason: the handler stays free
 * of a mapping table, and an exception that has the information carries it.
 *
 * <p>It exists because {@code openapi/trove-core.yaml} promises {@code Retry-After} on
 * <em>every</em> 429 this API answers — {@code LoginThrottled} says so in as many words — while
 * only {@code RateLimitFilter}, which writes its own response, was setting it. The brute-force
 * gate's 429 {@code account-locked}, which is the one a client is most likely to back off from,
 * came out of {@link GlobalExceptionHandler} with no header at all, so a client following the
 * documented contract had nothing to back off by (confirmed review finding 3).
 *
 * <p>RFC 9110 also permits an HTTP-date; this server always sends the delta form, in whole
 * seconds, which is what the spec's {@code RetryAfter} header component describes.
 */
public interface RetryAfterAware {

    /** Whole seconds the caller should wait. Always at least 1 — the spec's minimum. */
    long getRetryAfterSeconds();
}
