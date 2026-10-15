package finance.trove.networth;

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
 * One liability in a household's net-worth ledger — a debt or obligation that reduces net worth
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V28).
 *
 * <p>See {@link Asset}'s javadoc for the tenancy and currency rules, which are identical here:
 * {@code household_id} is the scope, {@code user_id} is created-by only, and {@link #getCurrency()}
 * is set once at construction with no column default (finding F1).
 *
 * <p>{@link #getInterestRate()} is a PERCENTAGE, not money — {@code "4.2500"} means 4.25%, never
 * an amount in {@link #getCurrency()}. It defaults to zero (zero-interest debt) when the creating
 * request omits it.
 */
@Entity
@Table(name = "liability")
public class Liability {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "liability_type", nullable = false, length = 20)
    private LiabilityType liabilityType;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "interest_rate", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Liability() {
        // JPA
    }

    public Liability(UUID householdId, UUID userId, String name, LiabilityType liabilityType,
                     BigDecimal balance, BigDecimal interestRate, CurrencyCode currency,
                     UUID accountId, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.name = name;
        this.liabilityType = liabilityType;
        this.balance = balance;
        this.interestRate = interestRate;
        this.currency = currency;
        this.accountId = accountId;
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

    /** Who added the liability. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LiabilityType getLiabilityType() {
        return liabilityType;
    }

    public void setLiabilityType(LiabilityType liabilityType) {
        this.liabilityType = liabilityType;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getInterestRate() {
        return interestRate;
    }

    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
