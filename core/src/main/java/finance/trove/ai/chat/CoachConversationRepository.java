package finance.trove.ai.chat;

import finance.trove.common.repository.UserOwnedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Conversations, and the messages inside them, always scoped to the member who owns them
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §9 risk 2).
 *
 * <p>{@link UserOwnedRepository} rather than {@code UserScopedRepository}: {@code coach_conversation}
 * is per member. A household administrator may act on a member's ledger rows; nobody — administrator
 * included — reads another member's coach thread, and the base's finders make that a compile-time
 * fact rather than a review question.
 *
 * <p><strong>There is no {@code CoachMessageRepository}, deliberately.</strong> {@code coach_message}
 * carries neither {@code user_id} nor {@code household_id} — its owner is its conversation's owner,
 * once, and a denormalised copy would be a second source of truth for ownership that could drift.
 * None of the three scoped bases fits an entity with no owning column, and
 * {@code ArchitectureTest#everyRepositoryIsScopedOrDeliberatelyExempt} fails the build on a
 * repository that extends the bare {@code Repository} marker instead. So the message queries live
 * here, on the repository of the table that <em>does</em> name an owner, and every one of them
 * takes {@code userId} and joins through {@code m.conversation}. A message cannot be reached
 * without naming the member it belongs to.
 *
 * <p>Writes of individual messages go through {@code CoachConversationService}'s
 * {@code EntityManager}, for the same reason a {@code @OneToMany} is not mapped on
 * {@link CoachConversation}: appending one turn must not load every stored turn.
 */
public interface CoachConversationRepository
    extends UserOwnedRepository<CoachConversation, UUID> {

    /**
     * One page of this member's turns across every conversation they have, most recently used
     * conversation first and chronological within each — the order {@code GET /v1/chat/history}
     * promises, so a client can group by {@code sessionId} without a second request.
     *
     * <p>{@link CoachRole#SYSTEM} turns are excluded here rather than filtered by the caller: the
     * system prompt and the ledger context block are the server's construction, and a page size
     * that counted them would be a page size the client cannot predict.
     */
    @Query(value = "select m from CoachMessage m "
        + "where m.conversation.userId = :userId and m.role <> :excludedRole "
        + "order by m.conversation.updatedAt desc, m.createdAt asc",
        countQuery = "select count(m) from CoachMessage m "
            + "where m.conversation.userId = :userId and m.role <> :excludedRole")
    Page<CoachMessage> pageMessagesOfUser(@Param("userId") UUID userId,
                                          @Param("excludedRole") CoachRole excludedRole,
                                          Pageable pageable);

    /**
     * One page of a single conversation's turns, oldest first.
     *
     * <p>The conversation id is a filter, not a lookup: a conversation that is not this member's
     * matches nothing, which is what lets the route answer 404 without a second ownership query
     * ever returning somebody else's row.
     */
    @Query(value = "select m from CoachMessage m "
        + "where m.conversation.userId = :userId and m.conversation.id = :conversationId "
        + "and m.role <> :excludedRole "
        + "order by m.createdAt asc",
        countQuery = "select count(m) from CoachMessage m "
            + "where m.conversation.userId = :userId and m.conversation.id = :conversationId "
            + "and m.role <> :excludedRole")
    Page<CoachMessage> pageMessagesOfConversation(@Param("userId") UUID userId,
                                                  @Param("conversationId") UUID conversationId,
                                                  @Param("excludedRole") CoachRole excludedRole,
                                                  Pageable pageable);

    /**
     * The most recent turns of one conversation, <strong>newest first</strong> — the history
     * window the prompt is built from. The caller reverses it; asking the database for the newest
     * N is what keeps a long conversation from being loaded to take its tail.
     */
    @Query("select m from CoachMessage m "
        + "where m.conversation.userId = :userId and m.conversation.id = :conversationId "
        + "and m.role <> :excludedRole "
        + "order by m.createdAt desc")
    List<CoachMessage> findRecentMessages(@Param("userId") UUID userId,
                                          @Param("conversationId") UUID conversationId,
                                          @Param("excludedRole") CoachRole excludedRole,
                                          Pageable window);

    /**
     * This member's latest turn in any conversation, for the suggestion keyword match. A list
     * rather than an {@code Optional} because the limit is the {@code Pageable}'s, and a member
     * with no history simply gets an empty one.
     */
    @Query("select m from CoachMessage m "
        + "where m.conversation.userId = :userId and m.role = :role "
        + "order by m.createdAt desc")
    List<CoachMessage> findLatestMessages(@Param("userId") UUID userId,
                                          @Param("role") CoachRole role,
                                          Pageable window);

    /**
     * Deletes every conversation this member has; the messages go with them through the
     * {@code ON DELETE CASCADE} on {@code coach_message.conversation_id}.
     *
     * @return conversations deleted; zero is a legitimate answer and still a 200
     */
    long deleteAllByUserId(UUID userId);
}
