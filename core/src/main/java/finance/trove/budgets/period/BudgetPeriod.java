package finance.trove.budgets.period;

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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * One calendar month of a household's budget — the container its {@code Envelope}s live in
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V23-row, §4.6).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope, not {@link #getUserId()} —
 * one shared ledger per household (§1.5), so every member sees and may change every period;
 * {@code user_id} records who opened it, for audit. The pre-collapse entity was scoped by
 * {@code user_id} alone and its uniqueness constraint was {@code (user_id, month)}, which under a
 * shared ledger would have let two members each own a January with nothing to say which of the
 * two an envelope belonged to.
 *
 * <p><strong>The month is normalised to day 1</strong> and that invariant is load-bearing twice
 * over: {@code UNIQUE (household_id, month)} is only meaningful if every row for September is
 * stored as the same date, and V23 backs it with a {@code CHECK} so a write that skipped
 * {@link #normaliseMonth} fails at the database rather than quietly creating a second September.
 *
 * <p><strong>{@link #getCurrency()} has no default and never changes</strong> (finding F1: the
 * source defaulted every period to the literal {@code "CAD"} in both the column and the entity).
 * It is set once at construction from the request or the creating member's own
 * {@code users.currency}. Re-denominating a period through an edit form would silently
 * reinterpret every envelope figure already recorded under it.
 *
 * <p><strong>No {@code @OneToMany} to the envelopes.</strong> The source held one and then had to
 * touch {@code getEnvelopes().size()} inside the transaction to force the lazy proxy before
 * serialisation. Envelopes are read through {@code EnvelopeRepository} with the household in the
 * query instead, which is both the scoping rule (§9 risk 2) and one less way to serialise an
 * uninitialised proxy.
 */
@Entity
@Table(name = "budget_period")
public class BudgetPeriod {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "month", nullable = false, updatable = false)
    private LocalDate month;

    @Column(name = "total_income", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalIncome;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_type", nullable = false, length = 10)
    private BudgetType budgetType;

    @Column(name = "rolled_over_at")
    private Instant rolledOverAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BudgetPeriod() {
        // JPA
    }

    public BudgetPeriod(UUID householdId, UUID userId, YearMonth month, BigDecimal totalIncome,
                        CurrencyCode currency, BudgetType budgetType, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.month = month.atDay(1);
        this.totalIncome = totalIncome;
        this.currency = currency;
        this.budgetType = budgetType;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * The first of the month {@code anyDay} falls in — the one normalisation every write goes
     * through, exposed so callers do not each reinvent {@code withDayOfMonth(1)}.
     */
    public static LocalDate normaliseMonth(LocalDate anyDay) {
        return anyDay.withDayOfMonth(1);
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

    /** Who opened the period. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    /** Always the first of the month; see the class javadoc. */
    public LocalDate getMonth() {
        return month;
    }

    /** The same value as {@link #getMonth()}, as the month it actually denotes. */
    public YearMonth month() {
        return YearMonth.from(month);
    }

    public BigDecimal getTotalIncome() {
        return totalIncome;
    }

    public void setTotalIncome(BigDecimal totalIncome) {
        this.totalIncome = totalIncome;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BudgetType getBudgetType() {
        return budgetType;
    }

    public void setBudgetType(BudgetType budgetType) {
        this.budgetType = budgetType;
    }

    /**
     * When the rollover job carried this period's unspent balances into the following month, or
     * {@code null} while it has not.
     *
     * <p>This is the signal the job reads instead of asking whether today is the first of the
     * month, which is what makes it both idempotent and catch-up safe (V23's header, gap P2-39).
     */
    public Instant getRolledOverAt() {
        return rolledOverAt;
    }

    /** Marks this period carried forward. Called only by the rollover job, once. */
    public void markRolledOver(Instant when) {
        this.rolledOverAt = when;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "BudgetPeriod{id=" + id + ", household=" + householdId + ", month=" + month + '}';
    }
}
