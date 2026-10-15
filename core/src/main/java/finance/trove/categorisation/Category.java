package finance.trove.categorisation;

import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A named bucket used to classify transactions, e.g. "Groceries" or "Coffee Shops"
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V14, §4.5).
 *
 * <p><strong>{@code householdId == null} is what "system" means</strong> — there is
 * deliberately no separate stored {@code is_system} flag (see {@link #isSystem()}). A second,
 * independent boolean that could disagree with the tenancy column is exactly the shape of the
 * old service's G3 mass-assignment bug: a client-supplied {@code system} field the controller
 * had to remember to reset on every write. Making system-ness a read of the tenancy column
 * itself removes the class of bug rather than patching one call site.
 *
 * <p>System rows are seeded by V14 and are read-only through the API: {@code CategoryService}
 * refuses a rename or delete of one before any repository call. Household rows are fully
 * editable by any member of the owning household (owner decision: one shared ledger, §1.5) and
 * carry {@link #getCreatedBy()}, the id of the member who created them, for audit — not for
 * scoping; scoping is {@link #getHouseholdId()} alone.
 *
 * <p>{@link #getParentId()} is a self-reference kept from the old model
 * ({@code categorisation-service} {@code Category.java}), which had the column but never
 * populated it on any seeded row; V14 does the same. Cycle prevention lives in
 * {@code CategoryService}, not the database.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", updatable = false)
    private UUID householdId;

    @Column(name = "slug", nullable = false, length = 60, updatable = false)
    private String slug;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "icon", length = 50)
    private String icon;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /**
     * Set by the application clock at construction time, not left to the column's
     * {@code DEFAULT now()} — an {@code insertable = false} column leaves this field {@code null}
     * on the very entity Hibernate just persisted, so a creation response would answer
     * {@code "createdAt": null} for a category that unmistakably just exists (the same defect
     * step 3b's conformance check found on {@code HouseholdInvite}, fixed there the same way).
     * The column keeps its {@code DEFAULT now()} for any row inserted outside JPA (V14's seed).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected Category() {
        // JPA
    }

    /** A household's own category. System rows are inserted directly by V14, not through this. */
    public Category(UUID householdId, String slug, String name, String icon, UUID parentId,
                    UUID createdBy, Instant createdAt) {
        this.householdId = householdId;
        this.slug = slug;
        this.name = name;
        this.icon = icon;
        this.parentId = parentId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
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

    /** {@code true} for a system-seeded, read-only category; {@code false} for a household's own. */
    public boolean isSystem() {
        return householdId == null;
    }

    public String getSlug() {
        return slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
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
