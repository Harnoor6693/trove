package finance.trove.notifications;

/**
 * What a notification is about ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3).
 *
 * <p>The set is closed and is exactly what this build's producers can write. Schema V32's
 * {@code notification_log.type} CHECK and V33's {@code in_app_notification.type} CHECK carry the
 * same five names — V44 widened both — so a producer that invented a sixth fails its INSERT
 * rather than shipping an inbox row no client can render, and the spec's
 * {@code NotificationTypeCode} enum is the same list again.
 *
 * <p>Two of the source's values are gone. {@code TAX_REMINDER} goes with the tax engine (owner
 * decision 2; inventory defect D-N3 found it surviving in six places, all of them deleted here).
 * {@code CUSTOM} goes with {@code POST /internal/notifications/send}, the inter-service route this
 * collapse replaces with a plain bean call — an arbitrary caller-named type was only ever
 * reachable through it.
 *
 * <p>{@code RECEIPT_PROCESSED} arrived with collapse step 18, which is where the value, V44's
 * CHECK widening and both listeners could finally land together — step 14 shipped
 * {@code ReceiptEventPublisher} and no consumer, and step 16 could not write one because
 * {@code finance.trove.receipts} did not exist at its base. None of the three halves is valid
 * alone: a CHECK value no producer can write is a promise the schema cannot keep, and a producer
 * whose value the CHECK refuses fails its INSERT at run time.
 */
public enum NotificationType {

    /**
     * An envelope's spend reached the recipient's own alert threshold.
     *
     * <p>Deduplicated by envelope and budget month, so one envelope alerts one member once per
     * period however many transactions land in it.
     */
    BUDGET_THRESHOLD,

    /**
     * A detected recurring charge changed price.
     *
     * <p>Deduplicated by subscription and the NEW amount, so a price that changes twice is two
     * alerts and a rescan that sees the same new price is one.
     */
    SUBSCRIPTION_PRICE_CHANGE,

    /**
     * An account sync failed, wholly or in part.
     *
     * <p>Deduplicated by account and the recipient's own local date, so a connection failing
     * hourly tells them once a day. A sync that SUCCEEDS notifies nobody: an inbox that says
     * "nothing happened" every hour is an inbox nobody reads.
     */
    SYNC_FAILED,

    /** The Monday digest. Deduplicated by the ISO week in the recipient's own timezone. */
    WEEKLY_DIGEST,

    /**
     * An uploaded receipt finished its pipeline and needs a human.
     *
     * <p>Deduplicated by the receipt id alone: a receipt is processed once, and a re-run of the
     * pipeline over the same image is the same fact about the same photograph.
     *
     * <p>Sent to the UPLOADER alone, never fanned out across the household — the receipt is a
     * shared ledger row but "your photograph could not be read" is one person's problem. And sent
     * only on {@code REVIEW_REQUIRED} or {@code FAILED}: a receipt that processed cleanly is
     * nothing for the member to do, and an inbox that says so every time is an inbox people stop
     * opening. The {@code receipt.processed} WEBHOOK is delivered on every outcome, because a
     * machine endpoint that asked for the event by name wants the event; the webhook is not the
     * inbox.
     */
    RECEIPT_PROCESSED
}
