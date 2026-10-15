package finance.trove.subscriptions.subscription;

/**
 * How a detected subscription looked at the last scan, so a client can flag one without reading
 * the alert feed.
 *
 * <p>All three values are computed fresh by every scan; none is a stored opinion that has to be
 * cleared by hand.
 */
public enum SubscriptionSignal {

    /** Charging as expected, at a price that has never changed. */
    GREEN,

    /**
     * The detected price has changed at some point. {@code previousAmount} says what it was, and
     * an alert was raised when the change was first seen.
     */
    AMBER,

    /**
     * A charge was expected and has not been observed: {@code nextChargeDate} is further in the
     * past than the cycle's own tolerance window allows.
     *
     * <p>The source declared this value and never wrote it ("reserved for future use"). Using it
     * for a missing charge is what it was reserved for, and it costs nothing to compute — the
     * tolerance that decides whether a charge is merely late is the same one the classifier
     * already uses to decide the cadence.
     */
    RED
}
