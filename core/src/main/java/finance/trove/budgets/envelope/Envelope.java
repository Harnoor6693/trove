package finance.trove.budgets.envelope;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One budgeted category inside a {@code BudgetPeriod}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V24-row, §6 D1).
 *
 * <p><strong>This is the entity finding D1 was about.</strong> {@link #getSpent()} was declared,
 * defaulted to zero, and never written by any code path in the platform, so every health figure
 * was computed against zero and the rollover job carried the full allocation forward every month,
 * compounding without bound. {@code EnvelopeSpendService} is the writer that was missing;
 * {@link #rewriteSpent} is deliberately the only way in, and it <em>replaces</em> the figure
 * rather than adding to it, which is what makes recategorisation and re-sync unable to
 * double-count (§9 risk 4).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope (§1.5). The source
 * denormalised {@code user_id} here "so ownership can be verified without joining to the parent
 * period"; the same shortcut is kept with the column that actually scopes a shared ledger.
 *
 * <p><strong>No category name.</strong> The source carried a {@code category_name} copy taken at
 * creation time, so renaming a category left every envelope showing the old label. The name is
 * joined from {@code category} on read now that both tables share one database, and V24 backs
 * {@link #getCategoryId()} with a real foreign key declared {@code ON DELETE RESTRICT}.
 *
 * <p><strong>No {@code @ManyToOne} to the period</strong>, for the same reason the period holds no
 * collection of envelopes: the association would be a lazy proxy that has to be touched inside the
 * transaction before serialisation, and every query here already names both the household and the
 * period explicitly.
 */
@Entity
@Table(name = "envelope")
public class Envelope {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "period_id", nullable = false, updatable = false)
    private UUID periodId;

    @Column(name = "category_id", nullable = false, updatable = false)
    private UUID categoryId;

    @Column(name = "allocated", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocated;

    @Column(name = "spent", nullable = false, precision = 19, scale = 4)
    private BigDecimal spent;

    @Column(name = "rollover_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal rolloverAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Envelope() {
        // JPA
    }

    public Envelope(UUID householdId, UUID periodId, UUID categoryId, BigDecimal allocated,
                    CurrencyCode currency, Instant createdAt) {
        this.householdId = householdId;
        this.periodId = periodId;
        this.categoryId = categoryId;
        this.allocated = allocated;
        this.spent = currency.normalise(BigDecimal.ZERO);
        this.rolloverAmount = currency.normalise(BigDecimal.ZERO);
        this.currency = currency;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instants.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getPeriodId() {
        return periodId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public BigDecimal getAllocated() {
        return allocated;
    }

    public void setAllocated(BigDecimal allocated) {
        this.allocated = allocated;
    }

    /** What the household actually spent in this category, month and currency. */
    public BigDecimal getSpent() {
        return spent;
    }

    /**
     * Replaces {@link #getSpent()} with a freshly computed aggregate.
     *
     * <p>Named for what it does rather than {@code setSpent}, because the distinction is the whole
     * of finding D1's fix: an incrementing writer double-counts the moment a transaction is
     * recategorised or a sync re-runs, and both of those are ordinary events. There is no adding
     * variant, deliberately.
     */
    public void rewriteSpent(BigDecimal recomputed) {
        this.spent = recomputed;
    }

    /** What last month left over, carried in by the rollover job. Never client-writable. */
    public BigDecimal getRolloverAmount() {
        return rolloverAmount;
    }

    /** Set by the rollover job when it carries the previous month's unspent balance in. */
    public void setRolloverAmount(BigDecimal rolloverAmount) {
        this.rolloverAmount = rolloverAmount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Envelope{id=" + id + ", period=" + periodId + ", category=" + categoryId + '}';
    }
}
