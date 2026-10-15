package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both coach property records: their defaults, and the values that must fail the context refresh
 * rather than the first question ({@code ArchitectureTest#configurationPropertiesAreRecords}).
 *
 * <p>These carry their own defaults because nothing about the coach is added to
 * {@code application.yml} — collapse steps 11, 13, 14 and 16 run in parallel worktrees and no step
 * of this wave owns that file. That makes the constructor the only place a bad value can be caught,
 * which is what these cases hold.
 */
class CoachPropertiesTest {

    @Test
    @DisplayName("every runtime value has a default, so no yml entry is needed to boot")
    void everyValueDefaults() {
        CoachProperties properties = new CoachProperties(null, null);

        assertThat(properties.contextDeadline())
            .as("§6's five seconds for the whole context fan-out")
            .isEqualTo(CoachProperties.DEFAULT_CONTEXT_DEADLINE)
            .isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxAnswerCharacters())
            .isEqualTo(CoachProperties.DEFAULT_MAX_ANSWER_CHARACTERS);
    }

    @Test
    @DisplayName("a value that would break the feature fails the context refresh")
    void badValuesFailAtStartup() {
        assertThatThrownBy(() -> new CoachProperties(Duration.ZERO, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("context-deadline");
        assertThatThrownBy(() -> new CoachProperties(Duration.ofSeconds(-1), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachProperties(null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-answer-characters");
    }

    @Test
    @DisplayName("the prompts record defaults its window and its suggestion lists")
    void promptsDefault() {
        CoachPromptsProperties prompts =
            new CoachPromptsProperties("a prompt", "a template", null, null);

        assertThat(prompts.maxHistoryMessages())
            .isEqualTo(CoachPromptsProperties.DEFAULT_MAX_HISTORY_MESSAGES);
        assertThat(prompts.suggestions().defaultSuggestions()).isEmpty();
        assertThat(prompts.suggestions().taxKeywords()).isEmpty();
    }

    @Test
    @DisplayName("a missing prompt file fails loudly rather than sending a bare question")
    void aMissingPromptFileIsFatal() {
        assertThatThrownBy(() -> new CoachPromptsProperties(null, "template", 20, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prompts/coach.yml");
        assertThatThrownBy(() -> new CoachPromptsProperties("  ", "template", 20, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CoachPromptsProperties("prompt", null, 20, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("context_template");
        assertThatThrownBy(() -> new CoachPromptsProperties("prompt", "template", -1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max_history_messages");
    }

    @Test
    @DisplayName("the suggestion lists are defensively copied")
    void suggestionListsAreImmutable() {
        List<String> mutable = new java.util.ArrayList<>(List.of("one"));
        CoachPromptsProperties.Suggestions suggestions = new CoachPromptsProperties.Suggestions(
            mutable, null, null, null, null, null, null);
        mutable.add("two");

        assertThat(suggestions.defaultSuggestions()).containsExactly("one");
    }
}
