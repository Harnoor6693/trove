package finance.trove.ai.chat;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The coach's prompt content, bound from {@code classpath:prompts/coach.yml}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.10; {@code CoachPromptsConfig} is what makes the
 * file a property source).
 *
 * <p><strong>One source, not two.</strong> The source declared
 * {@code trove.coach.max-history-messages} in {@code application.yml} and
 * {@code trove.coach.max_history_messages} in the prompts file — two independently loaded property
 * sources binding one field, with the winner decided by load order (inventory model-manager §12).
 * The prompts file is the one that survives, and nothing about the coach is added to
 * {@code application.yml}: collapse steps 11, 13, 14 and 16 run in parallel worktrees and no step
 * of this wave owns that file.
 *
 * <p>A record with defaults in its canonical constructor, which {@code ArchitectureTest} requires
 * and which means a missing or malformed value fails the context refresh rather than surfacing on
 * the first question somebody asks.
 *
 * @param systemPrompt        the SYSTEM turn every conversation opens with. Never returned to a
 *                            client — it is the server's construction, and
 *                            {@code GET /v1/chat/history} excludes SYSTEM turns
 * @param contextTemplate     the ledger context block, with {@code {{placeholder}}} slots
 *                            {@link CoachContextBuilder} fills
 * @param maxHistoryMessages  how many stored turns of the current conversation are replayed into
 *                            the prompt. It bounds the PROMPT, not the history: the history route
 *                            still returns everything
 * @param suggestions         the keyword-triggered prompt starters {@link SuggestionService} picks
 *                            from
 */
@ConfigurationProperties("trove.coach")
public record CoachPromptsProperties(String systemPrompt, String contextTemplate,
                                     Integer maxHistoryMessages, Suggestions suggestions) {

    /** The source's window, kept: twenty turns is a long conversation and a short prompt. */
    public static final int DEFAULT_MAX_HISTORY_MESSAGES = 20;

    public CoachPromptsProperties {
        maxHistoryMessages =
            maxHistoryMessages == null ? DEFAULT_MAX_HISTORY_MESSAGES : maxHistoryMessages;
        suggestions = suggestions == null ? Suggestions.empty() : suggestions;

        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new IllegalArgumentException(
                "trove.coach.system_prompt is missing: prompts/coach.yml is not on the classpath, "
                    + "or CoachPromptsConfig's @PropertySource no longer points at it. The coach "
                    + "would otherwise send a bare question with no instructions at all");
        }
        if (contextTemplate == null || contextTemplate.isBlank()) {
            throw new IllegalArgumentException(
                "trove.coach.context_template is missing; see prompts/coach.yml");
        }
        if (maxHistoryMessages < 0) {
            throw new IllegalArgumentException(
                "trove.coach.max_history_messages cannot be negative; zero means every question "
                    + "starts fresh. Got " + maxHistoryMessages);
        }
    }

    /**
     * Keyword-triggered suggestion lists. The first category whose keywords appear in the member's
     * most recent question wins; no match falls back to {@link #defaultSuggestions}.
     *
     * <p>Nested record rather than a class, for the same constructor-validation reason as the
     * outer one. Every list defaults to empty rather than null so that a partially filled file
     * cannot produce a {@code NullPointerException} inside a keyword scan.
     */
    public record Suggestions(List<String> defaultSuggestions, List<String> budgetKeywords,
                              List<String> budgetSuggestions, List<String> subscriptionKeywords,
                              List<String> subscriptionSuggestions, List<String> taxKeywords,
                              List<String> taxSuggestions) {

        public Suggestions {
            defaultSuggestions = copyOrEmpty(defaultSuggestions);
            budgetKeywords = copyOrEmpty(budgetKeywords);
            budgetSuggestions = copyOrEmpty(budgetSuggestions);
            subscriptionKeywords = copyOrEmpty(subscriptionKeywords);
            subscriptionSuggestions = copyOrEmpty(subscriptionSuggestions);
            taxKeywords = copyOrEmpty(taxKeywords);
            taxSuggestions = copyOrEmpty(taxSuggestions);
        }

        static Suggestions empty() {
            return new Suggestions(null, null, null, null, null, null, null);
        }

        private static List<String> copyOrEmpty(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }
}
