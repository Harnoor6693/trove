package finance.trove.ai.chat;

import java.util.Locale;

/**
 * Who said one turn of a coach conversation — the {@code coach_message.role} CHECK, as an enum
 * (schema V39).
 *
 * <p>Three values, upper case, written once. The source had this as a free string that V3 wrote
 * lower case, V5 uppercased in place and V5 again re-declared the CHECK for; there is no
 * production data to migrate, so the final spelling is the only spelling here.
 */
public enum CoachRole {

    /**
     * The system prompt and the ledger context block. Server-built, stored so that a conversation
     * can be replayed exactly as it was sent, and never returned by any route: it is not part of
     * the member's conversation and a client rendering it would show them their own prompt.
     */
    SYSTEM,

    /** A question the member asked. */
    USER,

    /** An answer the model gave. Untrusted text: displayed, never interpreted. */
    ASSISTANT;

    /**
     * The OpenAI-compatible spelling, which is what {@code AiMessage.role} carries and what both
     * clients send.
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The role a request's {@code messages[].role} names, or null when it names none of them.
     *
     * <p>{@link #SYSTEM} is deliberately not reachable through this method: a client that could
     * name it would be writing the system prompt.
     */
    public static CoachRole fromWireName(String wireName) {
        if (wireName == null) {
            return null;
        }
        String normalised = wireName.trim().toLowerCase(Locale.ROOT);
        if (USER.wireName().equals(normalised)) {
            return USER;
        }
        return ASSISTANT.wireName().equals(normalised) ? ASSISTANT : null;
    }
}
