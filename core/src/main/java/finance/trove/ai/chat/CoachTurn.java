package finance.trove.ai.chat;

import finance.trove.ai.AiHandle;
import finance.trove.ai.dto.AiMessage;
import java.util.List;
import java.util.UUID;

/**
 * One prepared question: everything decided before the response is committed, so that nothing that
 * can fail cheaply fails after a stream has already opened (collapse step 13, §4.11).
 *
 * <p>Producing one of these has already: resolved the caller's own {@code users} row, checked that
 * their AI is on, checked that their privacy tier permits a payload built from the household
 * ledger, resolved or created the conversation, built the context block and written the member's
 * question to the database. What is left is the call itself, whose failures are the only ones a
 * streaming client sees in band.
 *
 * @param audience       whose ledger, whose currency, whose timezone, whose jurisdiction
 * @param conversationId the {@code X-Session-Id} of the answer, already on the response
 * @param handle         the caller's own AI, held for the length of this one operation
 * @param prompt         the exact message list the endpoint will be sent
 */
public record CoachTurn(CoachAudience audience, UUID conversationId, AiHandle handle,
                        List<AiMessage> prompt) {

    public CoachTurn {
        prompt = List.copyOf(prompt);
    }
}
