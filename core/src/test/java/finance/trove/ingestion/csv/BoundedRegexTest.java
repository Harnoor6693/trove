package finance.trove.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The header-guess step budget — {@code plugins.md} §8.2's ReDoS note, done in a way that actually
 * stops the regex.
 */
class BoundedRegexTest {

    /** The textbook catastrophic backtracker. */
    private static final Pattern EVIL = Pattern.compile("(a+)+b");

    @Test
    @DisplayName("an ordinary header still matches")
    void ordinaryHeadersStillMatch() {
        Pattern date = Pattern.compile("(transaction\\s+)?date");

        assertThat(BoundedRegex.matches(date, "transaction date")).isTrue();
        assertThat(BoundedRegex.matches(date, "balance")).isFalse();
        assertThat(BoundedRegex.matches(date, null)).isFalse();
    }

    @Test
    @DisplayName("a crafted header cannot pin the thread: the match is abandoned, not awaited")
    void aCraftedHeaderIsAbandonedQuickly() {
        String crafted = "a".repeat(40) + "!";

        long startedAt = System.nanoTime();
        boolean matched = BoundedRegex.matches(EVIL, crafted);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(matched)
            .as("an exhausted budget is a non-match: the column is simply unrecognised and the "
                + "member maps it by hand")
            .isFalse();
        assertThat(elapsed)
            .as("the pre-collapse ReDoSGuard only unblocked the CALLER — its own javadoc admits "
                + "Matcher.find() ignores the interrupt, so the runaway match kept a pool thread "
                + "for its natural duration, which for this input is longer than a human life")
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the budget is spent per match, so one bad header does not starve the next")
    void theBudgetIsPerMatch() {
        Pattern date = Pattern.compile("date");

        BoundedRegex.matches(EVIL, "a".repeat(40) + "!");

        assertThat(BoundedRegex.matches(date, "date")).isTrue();
    }
}
