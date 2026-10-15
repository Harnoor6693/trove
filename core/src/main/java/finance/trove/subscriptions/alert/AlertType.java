package finance.trove.subscriptions.alert;

/**
 * What a {@code subscription_alert} row is about.
 *
 * <p>All three values are declared because the column's CHECK constraint admits them and a
 * generated client's union should be complete. This step writes only {@link #PRICE_INCREASE}:
 * upcoming charges are served by {@code GET /v1/subscriptions/summary}'s {@code next14Days}, and
 * delivering any of these as a notification belongs to a later collapse step, not this one.
 */
public enum AlertType {

    /**
     * The detected price went UP. Only an increase writes one: a decrease still publishes
     * {@code SubscriptionPriceChangedEvent}, which carries a signed delta, but there is no
     * {@code PRICE_DECREASE} value and filing a decrease under this one would be a lie.
     */
    PRICE_INCREASE,

    /** A charge is due soon. Declared, not yet written — the summary is the upcoming surface. */
    UPCOMING,

    /** A charge appears to have failed. Declared, not yet written. */
    PAYMENT_FAILED
}
