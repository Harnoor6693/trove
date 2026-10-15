package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.exception.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a conversation id is read off the wire — the one place on this controller where "absent"
 * and "empty" must not mean the same thing.
 *
 * <p>{@code DELETE /v1/chat/history} with no {@code sessionId} deletes every conversation the
 * member has. Spring's own {@code StringToUUIDConverter} answers null for a blank source, so a
 * parameter bound straight to a {@code UUID} makes {@code ?sessionId=} — the ordinary shape of a
 * URL built from an unset client variable — indistinguishable from omitting it, and the member's
 * whole coach history goes. These four cases are that distinction.
 */
class ChatControllerTest {

    @Test
    @DisplayName("a present but empty sessionId is refused, never read as 'every conversation'")
    void anEmptyQueryParameterIsRejected() {
        assertThatThrownBy(() -> ChatController.sessionParameter(""))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> ChatController.sessionParameter("   "))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("an absent sessionId is the only thing that means every conversation")
    void anAbsentQueryParameterIsNull() {
        assertThat(ChatController.sessionParameter(null)).isNull();
    }

    @Test
    @DisplayName("a malformed sessionId is a 400 naming the parameter, not a 500")
    void aMalformedQueryParameterIsAValidationError() {
        assertThatThrownBy(() -> ChatController.sessionParameter("not-a-uuid"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("sessionId");
    }

    @Test
    @DisplayName("a well-formed sessionId is the conversation id, trimmed")
    void aWellFormedQueryParameterParses() {
        UUID conversation = UUID.randomUUID();
        assertThat(ChatController.sessionParameter(" " + conversation + " "))
            .isEqualTo(conversation);
    }

    @Test
    @DisplayName("an empty X-Session-Id starts a conversation; a malformed one is a 400")
    void theHeaderIsLenientAboutEmptyAndStrictAboutMalformed() {
        assertThat(ChatController.sessionHeader(null)).isNull();
        assertThat(ChatController.sessionHeader(""))
            .as("no session yet is exactly what an empty header says, and starting one destroys "
                + "nothing - unlike the query parameter, which deletes")
            .isNull();
        assertThatThrownBy(() -> ChatController.sessionHeader("not-a-uuid"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("X-Session-Id");
    }
}
