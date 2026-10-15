package finance.trove.subscriptions.subscription;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.subscriptions.detection.BillingCycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One recurring charge detected in the household's ledger (V29 {@code detected_subscription};
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7, §3, §6 D2).
 *
 * <p><strong>Nothing writes this entity through JPA except the cancel flag.</strong> Detection
 * writes it with a single {@code INSERT … ON CONFLICT (household_id, merchant_name) DO UPDATE}
 * (see {@code SubscriptionUpsert}), because the pre-collapse read-then-write was a
 * duplicate-insert race the moment more than one thing could scan a household — and this step
 * adds two more things that can (a manual trigger and an ingestion listener). The entity is the
 * READ model for the API and the target of the one member-driven write.
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope; the source table had
 * {@code user_id} only, which under a shared ledger would hide a partner's subscriptions.
 * {@link #getUserId()} means created-by, for audit — the calling member for
 * {@code POST /v1/subscriptions/scan}, the household's own administrator for the nightly job,
 * which has no caller.
 *
 * <p><strong>{@link #getCurrency()} has no default.</strong> The source column and entity both
 * defaulted to {@code "CAD"} and nothing ever overrode either (finding F1 / SD-3), so every
 * detected subscription on every install was Canadian. It now comes from the transactions the row
 * was detected from.
 *
 * <p><strong>{@link #isCancelled()} is a soft delete.</strong> A hard delete would simply be
 * re-detected by the next scan, so the flag is what makes "stop showing me this one" stick. A scan
 * keeps a cancelled row's figures current but never un-cancels it and never raises an alert for
 * it.
 *
 * <p>The transactions a detection was built from are in {@code subscription_transaction}, which
 * replaces the source's {@code transaction_ids uuid[]}. It is deliberately not mapped here: the
 * links are written natively alongside the upsert, and mapping them as a JPA collection would give
 * Hibernate a second opinion about rows it does not own.
 */
@Entity
@Table(name = "detected_subscription")
public class DetectedSubscription {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "merchant_name", nullable = false, length = 200, updatable = false)
    private String merchantName;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "normalized_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal normalizedAmount;

    @Column(name = "previous_amount", precision = 19, scale = 4)
    private BigDecimal previousAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 12)
    private BillingCycle billingCycle;

    @Column(name = "next_charge_date")
    private LocalDate nextChargeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "signal", nullable = false, length = 6)
    private SubscriptionSignal signal;

    @Column(name = "price_changed", nullable = false)
    private boolean priceChanged;

    @Column(name = "first_seen_date", nullable = false)
    private LocalDate firstSeenDate;

    @Column(name = "last_seen_date", nullable = false)
    private LocalDate lastSeenDate;

    @Column(name = "is_cancelled", nullable = false)
    private boolean cancelled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DetectedSubscription() {
        // JPA
    }

    public UUID getId() {
        return id;
    }

    /** The owning household — the scope, and the same for every member. */
    public UUID getHouseholdId() {
        return householdId;
    }

    /** Who the detection run was attributed to. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    /** The normalised merchant name; the natural key within the household. */
    public String getMerchantName() {
        return merchantName;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    /** What the subscription costs now, in {@link #getCurrency()}. */
    public BigDecimal getNormalizedAmount() {
        return normalizedAmount;
    }

    /** What it cost before the most recent price change, or {@code null}. */
    public BigDecimal getPreviousAmount() {
        return previousAmount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BillingCycle getBillingCycle() {
        return billingCycle;
    }

    /** {@link #getLastSeenDate()} advanced by one cycle. A date in the past means RED, not error. */
    public LocalDate getNextChargeDate() {
        return nextChargeDate;
    }

    public SubscriptionSignal getSignal() {
        return signal;
    }

    /** Whether the detected price has ever changed. Sticky; {@link #getPreviousAmount()} says how. */
    public boolean isPriceChanged() {
        return priceChanged;
    }

    public LocalDate getFirstSeenDate() {
        return firstSeenDate;
    }

    public LocalDate getLastSeenDate() {
        return lastSeenDate;
    }

    /** Soft-deleted by {@code DELETE /v1/subscriptions/{id}}; the row survives and stays readable. */
    public boolean isCancelled() {
        return cancelled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
