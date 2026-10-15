package finance.trove.transactions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A member's decision that one transaction belongs in one category, pinned so that no later
 * automatic categorisation can undo it ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V21, §6 D4).
 *
 * <p><strong>Why it is scoped to the household and not to the member who pinned it.</strong> The
 * pre-collapse table was {@code UNIQUE (user_id, transaction_id)}, which let two members of one
 * household hold contradictory pins on the same row of a ledger they share, with nothing to say
 * which one a budget aggregate should believe. The pin is a decision about the shared ledger, so
 * {@link #getHouseholdId()} is the scope and the uniqueness is on {@code transaction_id} alone;
 * {@link #getUserId()} records who made it, for audit.
 *
 * <p>The pin exists <em>alongside</em> {@code transaction.category_id}, not instead of it.
 * {@code PATCH /v1/transactions/{id}} writes both: the column, because that is what a budget
 * aggregate reads, and this row, because that is what survives the next re-sync (§6 D4 — writing
 * only the override table was the defect, and it would have meant a member's correction never
 * moved envelope spend at all).
 */
@Entity
@Table(name = "category_override")
public class CategoryOverride {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CategoryOverride() {
        // JPA
    }

    public CategoryOverride(UUID householdId, UUID userId, UUID transactionId, UUID categoryId,
                            Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.transactionId = transactionId;
        this.categoryId = categoryId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    /** Re-pinning replaces the choice rather than adding a second, contradictory row. */
    public void repin(UUID categoryId, UUID userId) {
        this.categoryId = categoryId;
        this.userId = userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
