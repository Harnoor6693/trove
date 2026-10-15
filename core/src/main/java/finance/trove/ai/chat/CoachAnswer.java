package finance.trove.ai.chat;

import java.util.regex.Pattern;

/**
 * What is done to a model's answer before it is stored or shown
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 44).
 *
 * <p><strong>A coach answer is untrusted output that happens to be rendered to a human.</strong>
 * Nothing in it becomes an id, a category, a URL this server fetches, a tool call, a SQL fragment
 * or a command; this step wires no tool calling or function calling of any kind, and there is no
 * code path anywhere in it that parses an answer for meaning. The only two things done to it are
 * here, and they are the same two collapse step 17's categorisation tier applies to its own
 * answers.
 *
 * <ol>
 *   <li><strong>Control characters are stripped.</strong> {@code \t}, {@code \n} and {@code \r}
 *       survive because prose legitimately contains them; everything else in {@code Cc}, and every
 *       format character in {@code Cf}, does not. A zero-width joiner or a bidirectional override
 *       inside an answer is not prose — it is a way to make rendered text read differently from
 *       the text that was stored, which is a problem precisely because this text IS displayed.</li>
 *   <li><strong>The length is capped</strong> at {@link CoachProperties#maxAnswerCharacters}. A
 *       model that decides to write for ever, or an endpoint that echoes a whole prompt back, must
 *       not be able to fill a column with it.</li>
 * </ol>
 *
 * <p><strong>A truncated answer is still stored.</strong> The cap truncates rather than rejects,
 * unlike step 17's, and the difference is the difference between the two uses: there the answer's
 * only purpose is an exact slug lookup, so a tampered answer is worthless and refusing it outright
 * is strictly stronger; here the answer is what the member watched arrive on their screen, and
 * discarding it would leave their question in the history with nothing after it. A truncated
 * answer is honest; a missing one is a lie about what happened.
 */
public final class CoachAnswer {

    /**
     * Control characters and format characters. {@code \t}, {@code \n} and {@code \r} are excluded
     * because an answer is prose and prose has lines.
     */
    private static final Pattern CONTROL_CHARACTERS =
        Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]|\\p{Cf}");

    private CoachAnswer() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The stored and displayed form of {@code raw}.
     *
     * @param raw               whatever the endpoint sent; may be null or empty
     * @param maxCharacters     the cap from {@link CoachProperties#maxAnswerCharacters}
     * @return never null; an empty string when the endpoint sent nothing usable
     */
    public static String sanitise(String raw, int maxCharacters) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = CONTROL_CHARACTERS.matcher(raw).replaceAll("");
        return stripped.length() <= maxCharacters
            ? stripped : stripped.substring(0, maxCharacters);
    }

    /**
     * The same treatment for one streamed delta, so that what the member sees frame by frame is
     * what ends up stored. The cap is not applied per delta — it belongs to the assembled answer —
     * but the strip is, because a control character forwarded in a frame has already reached the
     * screen by the time the answer is assembled.
     */
    public static String sanitiseDelta(String raw) {
        return raw == null ? "" : CONTROL_CHARACTERS.matcher(raw).replaceAll("");
    }
}
