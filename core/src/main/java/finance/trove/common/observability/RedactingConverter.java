package finance.trove.common.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback conversion word {@code %redact} that masks accidental secret leaks in
 * the formatted log message. Defense-in-depth only — the primary defense is
 * {@link Redact} at the call site.
 *
 * <p>Patterns masked:
 * <ul>
 *   <li>{@code Bearer eyJ...} authorization headers → {@code Bearer ***}</li>
 *   <li>Standalone JWT-shaped tokens → {@code ***JWT***}</li>
 *   <li>13–19-digit card-number-shaped sequences (with optional dashes/spaces)
 *       → {@code ****-****-****-XXXX} keeping the last four digits</li>
 * </ul>
 */
public final class RedactingConverter extends ClassicConverter {

    private static final Pattern BEARER_JWT = Pattern.compile(
        "Bearer\\s+eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    private static final Pattern JWT = Pattern.compile(
        "eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
    private static final Pattern CARD = Pattern.compile(
        "(?<!\\d)(\\d[\\s\\-]?){12,18}\\d(?!\\d)");

    @Override
    public String convert(ILoggingEvent event) {
        String msg = event.getFormattedMessage();
        if (msg == null || msg.isEmpty()) return msg;

        msg = BEARER_JWT.matcher(msg).replaceAll("Bearer ***");
        msg = JWT.matcher(msg).replaceAll("***JWT***");
        msg = CARD.matcher(msg).replaceAll(match -> {
            String digits = match.group().replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19) return match.group();
            return "****-****-****-" + digits.substring(digits.length() - 4);
        });
        return msg;
    }
}
