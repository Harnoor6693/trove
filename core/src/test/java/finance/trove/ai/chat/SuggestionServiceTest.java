package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prompt starters: keyword-matched, template text, and never an AI call
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.10 KEEP).
 */
class SuggestionServiceTest {

    private static final UUID USER = UUID.randomUUID();

    private final CoachConversationService conversations = mock(CoachConversationService.class);

    @Test
    @DisplayName("a member with no history gets the default list")
    void noHistoryMeansDefaults() {
        when(conversations.latestQuestion(USER)).thenReturn(null);

        assertThat(service().suggestFor(USER)).containsExactly("default one", "default two");
    }

    @Test
    @DisplayName("a blank last question is treated as no history")
    void aBlankQuestionMeansDefaults() {
        when(conversations.latestQuestion(USER)).thenReturn("   ");

        assertThat(service().suggestFor(USER)).containsExactly("default one", "default two");
    }

    @Test
    @DisplayName("the first matching category wins, case-insensitively")
    void theFirstCategoryWins() {
        when(conversations.latestQuestion(USER))
            .thenReturn("Which ENVELOPE is over budget, and which subscription?");

        assertThat(service().suggestFor(USER))
            .as("budget is checked before subscriptions, and the match ignores case")
            .containsExactly("budget one");
    }

    @Test
    @DisplayName("a subscription question gets the subscription list")
    void subscriptionsMatch() {
        when(conversations.latestQuestion(USER)).thenReturn("what recurring charges do I have");

        assertThat(service().suggestFor(USER)).containsExactly("subscription one");
    }

    @Test
    @DisplayName("a tax question gets the tax list")
    void taxMatches() {
        when(conversations.latestQuestion(USER)).thenReturn("which deduction can I claim");

        assertThat(service().suggestFor(USER)).containsExactly("tax one");
    }

    @Test
    @DisplayName("no keyword matches, so the default list comes back")
    void noMatchMeansDefaults() {
        when(conversations.latestQuestion(USER)).thenReturn("how is the weather");

        assertThat(service().suggestFor(USER)).containsExactly("default one", "default two");
    }

    @Test
    @DisplayName("an empty configuration is an empty list, never a null or an exception")
    void anEmptyConfigurationIsEmpty() {
        when(conversations.latestQuestion(any())).thenReturn("anything");
        SuggestionService bare = new SuggestionService(conversations,
            new CoachPromptsProperties("prompt", "template", 20, null));

        assertThat(bare.suggestFor(USER)).isEmpty();
    }

    private SuggestionService service() {
        return new SuggestionService(conversations, new CoachPromptsProperties("prompt",
            "template", 20, new CoachPromptsProperties.Suggestions(
                List.of("default one", "default two"),
                List.of("budget", "envelope"), List.of("budget one"),
                List.of("subscription", "recurring"), List.of("subscription one"),
                List.of("tax", "deduction"), List.of("tax one"))));
    }
}
