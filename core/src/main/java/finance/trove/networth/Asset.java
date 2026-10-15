package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
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
 * One asset in a household's net-worth ledger — a resource with positive monetary value that
 * contributes to net worth ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V27).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope, not {@link #getUserId()} —
 * one shared ledger per household (§1.5), so every member sees and may change every asset;
 * {@code user_id} records who added it, for audit. The pre-collapse entity was scoped by
 * {@code user_id} alone.
 *
 * <p><strong>{@link #getCurrency()} has no default</strong> (finding F1: the source defaulted
 * every asset to {@code "CAD"} in both the column and the entity). It is set once at construction
 * from the request or the creating member's own {@code users.currency}, and never changes — a
 * {@code value} already recorded in one currency cannot be silently reinterpreted as another
 * through an edit form.
 *
 * <p><strong>{@link #getAccountId()}</strong> optionally links this asset to a
 * {@code connected_account} in the same household; the FK is {@code ON DELETE SET NULL} (V27), so
 * disconnecting that account leaves this row as a plain manual entry rather than dangling or
 * disappearing.
 */
@Entity
@Table(name = "asset")
public class Asset {

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
    @Column(name = "asset_type", nullable = false, length = 20)
    private AssetType assetType;

    @Column(name = "value", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Asset() {
        // JPA
    }

    public Asset(UUID householdId, UUID userId, String name, AssetType assetType,
                BigDecimal value, CurrencyCode currency, UUID accountId, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.name = name;
        this.assetType = assetType;
        this.value = value;
        this.currency = currency;
        this.accountId = accountId;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    /** Who added the asset. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(AssetType assetType) {
        this.assetType = assetType;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
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
