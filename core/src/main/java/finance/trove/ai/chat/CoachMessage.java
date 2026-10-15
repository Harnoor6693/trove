package finance.trove.ai.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One turn of a coach conversation (schema V39).
 *
 * <p><strong>The content is ciphertext and there is nothing else.</strong>
 * {@code content_encrypted} holds {@code FieldEncryptionService} output with its {@code v1:}
 * prefix — readiness gap row 44's second half, handed to this step by name. There is no plaintext
 * column beside it, no preview, no derived title and no search index, because none of those can
 * exist over ciphertext and keeping a cleartext copy to make one work would undo the column. This
 * class exposes the stored value under the name it has, {@link #getContentEncrypted}, so that
 * nothing can mistake it for readable text; {@code CoachConversationService} is the only caller
 * that decrypts, and only for the row's owner.
 *
 * <p>A message is reached only through its conversation. There is no route that names a message
 * id, no repository over this entity, and therefore no finder that could return one turn without
 * naming the member it belongs to.
 */
@Entity
@Table(name = "coach_message")
public class CoachMessage {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Lazy, and never navigated by anything that reads a page of messages: the queries that return
     * these already know the conversation and select its id explicitly.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private CoachConversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 9)
    private CoachRole role;

    @Column(name = "content_encrypted", nullable = false, updatable = false)
    private String contentEncrypted;

    @Column(name = "model_used", updatable = false, length = 100)
    private String modelUsed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CoachMessage() {
        // JPA
    }

    /**
     * @param contentEncrypted {@code FieldEncryptionService} ciphertext, never plaintext
     * @param modelUsed        the model an {@link CoachRole#ASSISTANT} turn came from, or null
     */
    public CoachMessage(CoachConversation conversation, CoachRole role, String contentEncrypted,
                        String modelUsed) {
        this.conversation = conversation;
        this.role = role;
        this.contentEncrypted = contentEncrypted;
        this.modelUsed = modelUsed;
    }

    /**
     * Set here rather than left to the column default: the row is read back through the
     * persistence context in the same transaction that wrote it, and a database-side default would
     * leave this field null until a refresh nobody performs.
     */
    @PrePersist
    void stampCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public CoachConversation getConversation() {
        return conversation;
    }

    public CoachRole getRole() {
        return role;
    }

    /** The stored ciphertext. There is no accessor that returns decrypted content. */
    public String getContentEncrypted() {
        return contentEncrypted;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
