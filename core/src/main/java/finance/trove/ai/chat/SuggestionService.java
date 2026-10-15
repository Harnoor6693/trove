package finance.trove.ai.chat;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Prompt starters for the coach's empty state ({@code GET /v1/chat/suggestions};
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.10 KEEP).
 *
 * <p><strong>It costs no AI call.</strong> Every string is template text from
 * {@code prompts/coach.yml}, and the choice between the lists is a case-insensitive substring scan
 * of the member's most recent question. That is deliberate rather than a shortcut: this route is
 * fetched when a screen opens, before the member has done anything, and a screen that cannot
 * render until somebody's model has answered is a screen that hangs for whoever configured a slow
 * one — and bills them for the privilege. A member with AI switched off gets suggestions too,
 * because the list is what invites them to switch it on.
 *
 * <p>Only the single most recent turn is looked at. The classification is meant to be cheap and to
 * be wrong harmlessly: the worst outcome is five plausible questions instead of five better ones.
 *
 * <p>The scan reads decrypted content, which is why it goes through
 * {@link CoachConversationService} rather than touching the repository: that class is the only
 * place a stored turn is decrypted, and only ever for the member who owns it.
 */
@Service
public class SuggestionService {

    private final CoachConversationService conversations;
    private final CoachPromptsProperties prompts;

    public SuggestionService(CoachConversationService conversations,
                             CoachPromptsProperties prompts) {
        this.conversations = conversations;
        this.prompts = prompts;
    }

    /**
     * Suggestions for {@code userId}: the first keyword category their latest question matches, or
     * the default list.
     *
     * @return never null; empty only if the prompt file configures it so
     */
    public List<String> suggestFor(UUID userId) {
        CoachPromptsProperties.Suggestions lists = prompts.suggestions();
        String latest = conversations.latestQuestion(userId);
        if (latest == null || latest.isBlank()) {
            return lists.defaultSuggestions();
        }
        String haystack = latest.toLowerCase(Locale.ROOT);
        if (matches(haystack, lists.budgetKeywords())) {
            return lists.budgetSuggestions();
        }
        if (matches(haystack, lists.subscriptionKeywords())) {
            return lists.subscriptionSuggestions();
        }
        if (matches(haystack, lists.taxKeywords())) {
            return lists.taxSuggestions();
        }
        return lists.defaultSuggestions();
    }

    private static boolean matches(String haystack, List<String> keywords) {
        return keywords.stream()
            .map(keyword -> keyword.toLowerCase(Locale.ROOT))
            .anyMatch(haystack::contains);
    }
}
