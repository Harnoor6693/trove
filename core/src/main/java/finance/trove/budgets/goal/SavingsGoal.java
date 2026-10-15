package finance.trove.budgets.goal;

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
import java.util.UUID;

/**
 * A target amount the household intends to reach, optionally by a date
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V25-row, §4.6).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope (§1.5): a savings goal is
 * household money under the shared-ledger decision, so every member sees it and may contribute.
 * {@code user_id} records who started it, for audit. The pre-collapse entity was per user, which
 * on a two-member install meant one partner could not see the emergency fund the other was
 * building.
 *
 * <p><strong>{@link #isAchieved()} is derived, never set from a request.</strong> The only way to
 * change it is {@link #recordProgress}, which recomputes it from the two amounts — so a goal
 * cannot be reported met while it is short, or left unmet after the contribution that completed
 * it. The source had a public setter and relied on every caller remembering to use it.
 *
 * <p><strong>{@link #getCurrency()} has no default and never changes</strong> (finding F1: the
 * source defaulted to the literal {@code "CAD"} in both the column and the entity).
 */
@Entity
@Table(name = "savings_goal")
public class SavingsGoal {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "target_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal targetAmount;

    @Column(name = "current_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentAmount;

    @Column(name = "deadline")
    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "is_achieved", nullable = false)
    private boolean achieved;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SavingsGoal() {
        // JPA
    }

    public SavingsGoal(UUID householdId, UUID userId, String name, BigDecimal targetAmount,
                       LocalDate deadline, CurrencyCode currency, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.name = name;
        this.targetAmount = targetAmount;
        this.currentAmount = currency.normalise(BigDecimal.ZERO);
        this.deadline = deadline;
        this.currency = currency;
        this.achieved = false;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Applies a full replace of the writable fields and re-derives {@link #isAchieved()}.
     *
     * <p>One method rather than four setters, so that "achieved" cannot be left disagreeing with
     * the amounts it is a fact about: there is no order of calls that produces an inconsistent
     * row, because there is only one call.
     */
    public void recordProgress(String name, BigDecimal targetAmount, BigDecimal currentAmount,
                               LocalDate deadline) {
        this.name = name;
        this.targetAmount = targetAmount;
        this.currentAmount = currentAmount;
        this.deadline = deadline;
        this.achieved = currentAmount.compareTo(targetAmount) >= 0;
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

    /** Who started the goal. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    /** {@code null} for an open-ended goal. */
    public LocalDate getDeadline() {
        return deadline;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    /** Derived from the two amounts on every write; see {@link #recordProgress}. */
    public boolean isAchieved() {
        return achieved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "SavingsGoal{id=" + id + ", household=" + householdId + '}';
    }
}
