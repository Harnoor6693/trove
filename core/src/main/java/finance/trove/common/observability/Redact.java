package finance.trove.common.observability;

/**
 * Static helpers for masking sensitive values before they are passed to log statements.
 *
 * <p>Use at call sites where you intentionally need to log a value derived from
 * user-supplied or otherwise sensitive data:
 * <pre>{@code
 *   log.info("Webhook to {} succeeded with token {}", url, Redact.token(secret));
 * }</pre>
 *
 * <p>This is the primary defense against secret leakage in logs. Encoder-level
 * regex masking ({@link RedactingConverter}) is a backstop for accidents.
 */
public final class Redact {

    private Redact() { }

    /** Returns the first four chars followed by "***", or "***" if shorter than four. */
    public static String token(String s) {
        if (s == null) return "<null>";
        if (s.isEmpty()) return "<empty>";
        if (s.length() < 4) return "***";
        return s.substring(0, 4) + "***";
    }

    /** Returns "x***@domain" preserving the first char of the local part and the full domain. */
    public static String email(String s) {
        if (s == null) return "<null>";
        if (s.isEmpty()) return "<empty>";
        int at = s.indexOf('@');
        if (at < 1 || at == s.length() - 1) return "***";
        return s.charAt(0) + "***" + s.substring(at);
    }

    /** Returns "***" + the last four digit characters; "***" if fewer than four digits. */
    public static String phone(String s) {
        if (s == null) return "<null>";
        if (s.isEmpty()) return "<empty>";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() < 4) return "***";
        return "***" + digits.substring(digits.length() - 4);
    }

    /** Returns "****-****-****-XXXX" preserving the last four digits; "***" if not 13–19 digits. */
    public static String cardNumber(String s) {
        if (s == null) return "<null>";
        if (s.isEmpty()) return "<empty>";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() < 13 || digits.length() > 19) return "***";
        return "****-****-****-" + digits.substring(digits.length() - 4);
    }
}
