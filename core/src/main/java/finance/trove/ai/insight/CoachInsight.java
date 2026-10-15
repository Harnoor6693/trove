package finance.trove.ai.insight;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One weekly note the coach wrote about one member's week (schema V40; collapse step 13).
 *
 * <p>Per member, like {@link finance.trove.ai.chat.CoachConversation} and for the same reason: the
 * sentence names what somebody spent on, and no household mate — administrator included — reads
 * another's.
 *
 * <p><strong>{@code weekStart} is the Monday of the RECIPIENT's week</strong>, computed in their
 * own {@code users.timezone}, never the container's (readiness gap row 15). It is a {@code DATE}
 * rather than an instant because it is a calendar fact about somebody's week, and it doubles as the
 * idempotence key: {@code UNIQUE (user_id, week_start)} plus
 * {@code ON CONFLICT … DO NOTHING} is what makes a second run of the weekly job free rather than a
 * second copy in every member's inbox.
 *
 * <p>The content is ciphertext, like a conversation's; {@link InsightService} is the one place it
 * is decrypted, and only for its owner.
 *
 * <p>The entity is read-only from Java's point of view. Rows are written by a native
 * {@code INSERT … ON CONFLICT} in {@link CoachInsightRepository} — Postgres's conflict clause is
 * the whole point and JPA has no vocabulary for it — so there is no public constructor and no
 * setter here.
 */
@Entity
@Table(name = "coach_insight")
public class CoachInsight {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "week_start", nullable = false, updatable = false)
    private LocalDate weekStart;

    @Column(name = "content_encrypted", nullable = false, updatable = false)
    private String contentEncrypted;

    @Column(name = "delivered", nullable = false)
    private boolean delivered;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected CoachInsight() {
        // JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    /** The stored ciphertext. There is no accessor that returns decrypted content. */
    public String getContentEncrypted() {
        return contentEncrypted;
    }

    /**
     * Whether this note has been handed to the member anywhere beyond {@code GET /v1/insights}.
     * Reading that route does not set it — a GET stays safe — so this is the seam collapse step
     * 11's notification channel writes when it delivers one.
     */
    public boolean isDelivered() {
        return delivered;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
