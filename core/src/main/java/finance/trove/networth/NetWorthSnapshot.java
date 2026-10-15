package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
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
 * A point-in-time net-worth snapshot for ONE household, ONE currency, ONE date
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §3 V26, §6 "Net worth").
 *
 * <p><strong>F2 fix — the reason this table exists in this shape.</strong> The source table was
 * unique on {@code (user_id, snapshot_date)} with a single blended {@code net_worth}: a household
 * holding CAD assets and a USD liability had its two currencies summed as though they were one
 * unit. This table is unique on {@code (household_id, snapshot_date, currency)} — a snapshot is
 * now ONE ROW PER CURRENCY PRESENT, written by {@link NetWorthSnapshotService#takeSnapshot}, which
 * is the only correct behaviour in a system with no FX rate (07-READINESS-GAPS.md row 40,
 * deferred): a CAD position and a USD position are two rows, never a converted sum.
 *
 * <p><strong>Tenancy.</strong> {@code household_id} is the scope (§1.5); {@code user_id} is
 * created-by only — the calling member for {@code POST /v1/networth/snapshot}, the household's own
 * administrator for the daily job, since a scheduled write has no caller to attribute it to.
 *
 * <p>{@link #getNetWorth()} is a database-GENERATED (STORED) column,
 * {@code total_assets - total_liabilities}, mapped {@code insertable = false, updatable = false}:
 * Postgres computes it, not this class, so it can never drift from the two figures it derives
 * from. {@code ddl-auto: validate} accepts this mapping because Hibernate's schema validation
 * checks the column's name, type and nullability, not its generation expression.
 */
@Entity
@Table(name = "net_worth_snapshot")
public class NetWorthSnapshot {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "snapshot_date", nullable = false, updatable = false)
    private LocalDate snapshotDate;

    @Column(name = "total_assets", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAssets;

    @Column(name = "total_liabilities", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalLiabilities;

    /** Database-generated; see the class javadoc. Never set from Java. */
    @Column(name = "net_worth", precision = 19, scale = 4, insertable = false, updatable = false)
    private BigDecimal netWorth;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NetWorthSnapshot() {
        // JPA
    }

    public NetWorthSnapshot(UUID householdId, UUID userId, LocalDate snapshotDate,
                            BigDecimal totalAssets, BigDecimal totalLiabilities,
                            CurrencyCode currency, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.snapshotDate = snapshotDate;
        this.totalAssets = totalAssets;
        this.totalLiabilities = totalLiabilities;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    /** Who triggered this snapshot. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public LocalDate getSnapshotDate() {
        return snapshotDate;
    }

    public BigDecimal getTotalAssets() {
        return totalAssets;
    }

    public void setTotalAssets(BigDecimal totalAssets) {
        this.totalAssets = totalAssets;
    }

    public BigDecimal getTotalLiabilities() {
        return totalLiabilities;
    }

    public void setTotalLiabilities(BigDecimal totalLiabilities) {
        this.totalLiabilities = totalLiabilities;
    }

    /**
     * The database-computed value. {@code null} on an entity that has never been reloaded after
     * an insert or update in this same persistence context — callers that need it right after a
     * write should compute {@code totalAssets - totalLiabilities} themselves rather than assume
     * this is populated (Hibernate only reads a generated column back on a later {@code SELECT}).
     */
    public BigDecimal getNetWorth() {
        return netWorth;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
