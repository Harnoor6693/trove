package finance.trove.ai.chat.dto;

import finance.trove.ai.chat.CoachRole;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored turn, as {@code GET /v1/chat/history} returns it.
 *
 * <p>{@code content} is decrypted here and only here: the column holds AES-GCM ciphertext, there is
 * no plaintext copy anywhere, and {@code CoachConversationService} is the one class that turns one
 * into the other — always for a member already proved to own the row.
 *
 * <p>{@link CoachRole#SYSTEM} never appears. The system turn is the server's own construction and a
 * client rendering it would be showing the member their own prompt.
 *
 * @param sessionId the conversation this turn belongs to, so a client can group a mixed page
 * @param model     the model behind an {@code ASSISTANT} turn; null on a {@code USER} turn
 */
public record CoachMessageResponse(UUID id, UUID sessionId, CoachRole role, String content,
                                   String model, Instant createdAt) {
}
