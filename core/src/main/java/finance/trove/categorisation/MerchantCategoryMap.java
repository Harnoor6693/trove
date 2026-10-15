package finance.trove.categorisation;

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
 * A learned or declared merchant-to-category mapping, scoped to one household
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V15, §4.5, §6 D4).
 *
 * <p>Unlike {@link Category} this table has no system tier: {@link #getHouseholdId()} is never
 * null, because a merchant string only makes sense inside the household whose transaction
 * history it comes from. {@link #getNormalisedMerchant()} is always the output of
 * {@link MerchantNormaliser#normalise(String)} — never a raw, unnormalised string — so that
 * {@code (householdId, normalisedMerchant)} is a genuine lookup key: an EXACT row for
 * {@code "STARBUCKS"} and a PREFIX row for {@code "STAR"} are different keys and coexist; two
 * writes that would key on the identical normalised string cannot, which is what makes
 * {@link MerchantMappingWriter}'s upsert idempotent rather than merely best-effort.
 */
@Entity
@Table(name = "merchant_category_map")
public class MerchantCategoryMap {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "normalised_merchant", nullable = false, length = 255)
    private String normalisedMerchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    private MatchType matchType;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "confidence", nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private MappingSource source;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * Set by the application clock at construction time, not left to the column's
     * {@code DEFAULT now()} — an {@code insertable = false} column leaves this field {@code null}
     * on the very entity Hibernate just persisted, so a creation response would answer
     * {@code "createdAt": null} for a rule that unmistakably just exists (the same defect step 3b's
     * conformance check found on {@code HouseholdInvite}, fixed there the same way). The column
     * keeps its {@code DEFAULT now()} for {@link MerchantCategoryMapRepository#upsert}'s native
     * insert, which does not go through this constructor.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MerchantCategoryMap() {
        // JPA
    }

    public MerchantCategoryMap(UUID householdId, String normalisedMerchant, MatchType matchType,
                               UUID categoryId, BigDecimal confidence, MappingSource source,
                               int priority, UUID createdBy, Instant createdAt) {
        this.householdId = householdId;
        this.normalisedMerchant = normalisedMerchant;
        this.matchType = matchType;
        this.categoryId = categoryId;
        this.confidence = confidence;
        this.source = source;
        this.priority = priority;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
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

    public String getNormalisedMerchant() {
        return normalisedMerchant;
    }

    public void setNormalisedMerchant(String normalisedMerchant) {
        this.normalisedMerchant = normalisedMerchant;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(MatchType matchType) {
        this.matchType = matchType;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public MappingSource getSource() {
        return source;
    }

    public void setSource(MappingSource source) {
        this.source = source;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
