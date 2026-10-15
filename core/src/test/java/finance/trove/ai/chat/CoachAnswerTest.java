package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The only two things done to a model's answer ({@code docs/recovery/07-READINESS-GAPS.md} row 44).
 *
 * <p>Worth its own class because both are silent when they go wrong: a bidirectional override left
 * in an answer renders differently from the text that was stored, and a missing cap is invisible
 * until a model writes a megabyte into a column.
 */
class CoachAnswerTest {

    /** U+202E RIGHT-TO-LEFT OVERRIDE and U+202C POP: {@code Cf}, and a rendering trick. */
    private static final String BIDI_OVERRIDE = "\u202E";
    private static final String BIDI_POP = "\u202C";

    /** BEL: a {@code Cc} control character that is not tab, newline or carriage return. */
    private static final String BELL = "\u0007";

    @Test
    @DisplayName("control and format characters are removed; newlines and tabs survive")
    void controlCharactersAreStripped() {
        String hostile = "You spent " + BELL + BIDI_OVERRIDE + "42.00" + BIDI_POP
            + " on\tfuel.\nThat is more than last week.";

        String clean = CoachAnswer.sanitise(hostile, 1_000);

        assertThat(clean)
            .as("prose has lines and tabs; it does not have NULs, bells or bidi overrides")
            .isEqualTo("You spent 42.00 on\tfuel.\nThat is more than last week.");
    }

    @Test
    @DisplayName("a long answer is truncated rather than discarded")
    void theCapTruncates() {
        String tooLong = "x".repeat(50);

        assertThat(CoachAnswer.sanitise(tooLong, 10))
            .as("the member watched this arrive; discarding it would lose the answer to their "
                + "question entirely, and a truncated answer is honest where a missing one is not")
            .isEqualTo("x".repeat(10));
    }

    @Test
    @DisplayName("null and empty answers become the empty string, never null")
    void nothingIsNull() {
        assertThat(CoachAnswer.sanitise(null, 10)).isEmpty();
        assertThat(CoachAnswer.sanitise("", 10)).isEmpty();
        assertThat(CoachAnswer.sanitiseDelta(null)).isEmpty();
    }

    @Test
    @DisplayName("a delta is stripped but not capped — the cap belongs to the whole answer")
    void deltasAreStrippedNotCapped() {
        assertThat(CoachAnswer.sanitiseDelta("frag" + BELL + "ment")).isEqualTo("fragment");
        assertThat(CoachAnswer.sanitiseDelta("y".repeat(5_000))).hasSize(5_000);
    }

    @Test
    @DisplayName("an answer at exactly the cap is untouched")
    void theCapIsInclusive() {
        assertThat(CoachAnswer.sanitise("abcde", 5)).isEqualTo("abcde");
    }
}
