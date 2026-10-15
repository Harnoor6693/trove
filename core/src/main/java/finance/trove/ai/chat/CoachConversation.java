package finance.trove.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One AI-coach conversation, owned by one member (schema V39;
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §4.11, §6).
 *
 * <p><strong>Per member, not per household.</strong> Every ledger entity in this application is
 * scoped by {@code household_id} because the owner's decision is one shared ledger. This one is
 * the deliberate opposite, like {@code AiSettings} and {@code TaxExportRun}: the figures a coach
 * answer discusses come from the shared ledger, but the conversation about them is one member's,
 * and a household mate reading another member's thread is the leak this step can most easily
 * ship. Every route that can name a conversation answers 404 — never 403 — for one that is not
 * the caller's.
 *
 * <p><strong>This row's id IS the {@code X-Session-Id} of the §4.11 wire contract.</strong> The
 * source carried a separate {@code session_id} column beside the user id; two identifiers for one
 * thing can disagree, and the source's could, because the column was added without a NOT NULL that
 * its entity nevertheless declared. There is one id here and the header carries it.
 *
 * <p>The messages are deliberately <strong>not</strong> mapped as a collection. A conversation is
 * appended to one turn at a time and read one window at a time, and an owning
 * {@code @OneToMany} would load every stored turn to add one. They are reached through
 * {@link CoachConversationRepository}'s queries, every one of which is scoped by this row's owner.
 */
@Entity
@Table(name = "coach_conversation")
public class CoachConversation {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CoachConversation() {
        // JPA
    }

    /** Starts a conversation for {@code userId}. The title stays null; see {@link #getTitle}. */
    public CoachConversation(UUID userId) {
        this.userId = userId;
    }

    /**
     * Both callbacks, because {@code updated_at} is NOT NULL and a freshly constructed row carries
     * no timestamp: without the insert half the first save would fail the constraint.
     */
    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    /**
     * A name a member may one day give this conversation, or null — which is what it always is
     * today. Nothing derives it from the messages: the obvious derivation, the first line of the
     * first question, is exactly the plaintext copy {@code content_encrypted} exists to avoid
     * (readiness gap row 44).
     */
    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Moves this conversation to the top of the member's history. */
    public void markUsed() {
        this.updatedAt = Instant.now();
    }
}
