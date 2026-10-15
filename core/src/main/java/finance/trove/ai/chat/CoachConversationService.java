package finance.trove.ai.chat;

import finance.trove.ai.chat.dto.CoachMessageResponse;
import finance.trove.ai.dto.AiMessage;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that reads or writes a coach conversation, and the only place a stored turn is
 * decrypted (collapse step 13; readiness gap row 44).
 *
 * <h2>Ownership is a parameter of every method, and 404 is the refusal</h2>
 * Every method takes the caller's own {@code userId} from
 * {@code @AuthenticationPrincipal TroveUserDetails} — never from a body, a path or a header — and
 * every query it makes carries it. A conversation that belongs to somebody else, a household mate
 * included, matches nothing and becomes a {@link ResourceNotFoundException}: these rows are per
 * member and not part of the shared ledger, so "not yours" and "does not exist" have to be the
 * same answer or the route becomes an oracle for whether an id is real.
 *
 * <h2>Decryption happens here and nowhere else</h2>
 * The content column is ciphertext; nothing above this class sees it, and no repository projection
 * or native query returns plaintext. That is what keeps the encryption honest: a single place that
 * decrypts, always for a member who has already been proved to own the row.
 *
 * <h2>Short, explicit transactions — including from a callback thread</h2>
 * {@link #appendUserMessage} and {@link #appendAssistantMessage} are
 * {@link Propagation#REQUIRES_NEW}. The second of the two is called from the AI client's
 * {@code onDone} callback, on a thread that is streaming a response and holds no ambient
 * transaction of its own; relying on an ambient one would mean the answer's fate depended on
 * whatever the caller happened to have open, which for a stream is nothing at all. A new
 * transaction that opens, inserts one row and commits is what makes "the question survives even if
 * the connection drops" true, and it is why the user turn is written before the call rather than
 * with the answer afterwards.
 */
@Service
public class CoachConversationService {

    private final CoachConversationRepository conversations;
    private final FieldEncryptionService encryption;
    private final EntityManager entityManager;

    public CoachConversationService(CoachConversationRepository conversations,
                                    FieldEncryptionService encryption,
                                    EntityManager entityManager) {
        this.conversations = conversations;
        this.encryption = encryption;
        this.entityManager = entityManager;
    }

    /**
     * The conversation named by {@code sessionId}, or a new one when it is null.
     *
     * @throws ResourceNotFoundException when {@code sessionId} is not this member's
     */
    @Transactional
    public CoachConversation startOrResume(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return conversations.save(new CoachConversation(userId));
        }
        CoachConversation resumed = conversations.findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No coach conversation with that session id"));
        resumed.markUsed();
        return conversations.save(resumed);
    }

    /** Writes the member's question, before the endpoint is called. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendUserMessage(UUID userId, UUID conversationId, String content) {
        append(userId, conversationId, CoachRole.USER, content, null);
    }

    /**
     * Writes the model's answer, from the stream's {@code onDone} or from the non-streaming
     * return.
     *
     * <p>A blank answer is not written: an endpoint that sent nothing leaves the question standing
     * on its own, which is what happened, rather than an empty turn that reads as an answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendAssistantMessage(UUID userId, UUID conversationId, String content,
                                       String model) {
        if (content == null || content.isBlank()) {
            return;
        }
        append(userId, conversationId, CoachRole.ASSISTANT, content, model);
    }

    /** Writes the system turn — the prompt and the ledger context block — as it was sent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendSystemMessage(UUID userId, UUID conversationId, String content) {
        append(userId, conversationId, CoachRole.SYSTEM, content, null);
    }

    /**
     * The stored turns replayed into the next prompt, oldest first, at most {@code window} of
     * them. SYSTEM turns are excluded because the caller builds a fresh one from live figures:
     * replaying last week's balances as though they were today's is worse than not replaying them.
     */
    @Transactional(readOnly = true)
    public List<AiMessage> historyWindow(UUID userId, UUID conversationId, int window) {
        if (window <= 0) {
            return List.of();
        }
        List<CoachMessage> newestFirst = conversations.findRecentMessages(
            userId, conversationId, CoachRole.SYSTEM, PageRequest.of(0, window));
        List<AiMessage> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            CoachMessage message = newestFirst.get(i);
            oldestFirst.add(new AiMessage(message.getRole().wireName(), decrypt(message)));
        }
        return List.copyOf(oldestFirst);
    }

    /**
     * One page of this member's turns. {@code sessionId} narrows it to one conversation, and an id
     * that is not theirs is a 404 rather than an empty page.
     */
    @Transactional(readOnly = true)
    public PagedResult<CoachMessageResponse> page(UUID userId, UUID sessionId, int page,
                                                  int limit) {
        PageRequest pageRequest = PageRequest.of(page, limit);
        Page<CoachMessage> found;
        if (sessionId == null) {
            found = conversations.pageMessagesOfUser(userId, CoachRole.SYSTEM, pageRequest);
        } else {
            requireOwned(userId, sessionId);
            found = conversations.pageMessagesOfConversation(
                userId, sessionId, CoachRole.SYSTEM, pageRequest);
        }
        List<CoachMessageResponse> content = found.getContent().stream()
            .map(message -> new CoachMessageResponse(message.getId(),
                message.getConversation().getId(), message.getRole(), decrypt(message),
                message.getModelUsed(), message.getCreatedAt()))
            .toList();
        return new PagedResult<>(content, found.getTotalElements(), page, limit);
    }

    /**
     * The member's most recent question, decrypted, for the suggestion keyword match — or null
     * when they have never asked one.
     */
    @Transactional(readOnly = true)
    public String latestQuestion(UUID userId) {
        List<CoachMessage> latest =
            conversations.findLatestMessages(userId, CoachRole.USER, PageRequest.of(0, 1));
        return latest.isEmpty() ? null : decrypt(latest.get(0));
    }

    /**
     * Deletes one conversation, or every one of this member's when {@code sessionId} is null.
     *
     * <p>Clearing everything is idempotent and never 404s: the alternative turns the route into a
     * way of asking whether somebody has used the coach. Clearing one names an id, so an id that
     * is not the caller's is a 404 like every other by-id refusal here.
     *
     * @return conversations deleted
     */
    @Transactional
    public long clear(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return conversations.deleteAllByUserId(userId);
        }
        long deleted = conversations.deleteByIdAndUserId(sessionId, userId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("No coach conversation with that session id");
        }
        return deleted;
    }

    private void requireOwned(UUID userId, UUID sessionId) {
        if (!conversations.existsByIdAndUserId(sessionId, userId)) {
            throw new ResourceNotFoundException("No coach conversation with that session id");
        }
    }

    /**
     * Inserts one turn through the {@link EntityManager} rather than through a repository.
     *
     * <p>There is no {@code CoachMessageRepository} — {@code coach_message} names no owner of its
     * own, so none of the three scoped bases fits it and ArchUnit refuses the bare marker (see
     * {@link CoachConversationRepository}). Appending through a mapped {@code @OneToMany} on the
     * conversation was the other option and is worse: adding one turn would initialise the whole
     * collection, so every question in a long conversation would load every turn of it twice.
     * {@code getReference} gives the foreign key without a SELECT, and ownership has already been
     * proved by the caller's own scoped lookup.
     */
    private void append(UUID userId, UUID conversationId, CoachRole role, String content,
                        String model) {
        requireOwned(userId, conversationId);
        CoachConversation conversation =
            entityManager.getReference(CoachConversation.class, conversationId);
        entityManager.persist(
            new CoachMessage(conversation, role, encryption.encrypt(content), model));
    }

    private String decrypt(CoachMessage message) {
        return encryption.decrypt(message.getContentEncrypted());
    }
}
