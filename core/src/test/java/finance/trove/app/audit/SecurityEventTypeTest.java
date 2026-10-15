package finance.trove.app.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The enum and the {@code CHECK} constraint of V9 must agree exactly.
 *
 * <p>This is the coupling the migration's own comment names, and it fails in the worst way if it
 * drifts: adding a constant here without adding it there produces an insert that violates the
 * check, inside {@code SecurityEventRecorder}, which swallows database errors so that an
 * unavailable audit table cannot turn a successful login into a 500. The event would simply stop
 * being recorded, silently, which is precisely what an append-only security log must never do.
 */
class SecurityEventTypeTest {

    private static final Pattern CHECK_LIST = Pattern.compile(
        "security_event_type_check\\s+CHECK\\s*\\(\\s*type\\s+IN\\s*\\((?<values>[^)]*)\\)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    private static Set<String> checkConstraintValues() throws IOException {
        String sql;
        try (InputStream in = SecurityEventTypeTest.class
            .getResourceAsStream("/db/migration/V9__security_event.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher list = CHECK_LIST.matcher(sql);
        assertThat(list.find())
            .as("V9 must still declare a CHECK on security_event.type")
            .isTrue();
        Matcher values = QUOTED.matcher(list.group("values"));
        Set<String> found = new LinkedHashSet<>();
        while (values.find()) {
            found.add(values.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("Every enum constant is permitted by the V9 CHECK constraint")
    void everyConstantIsAllowedByTheSchema() throws IOException {
        Set<String> allowed = checkConstraintValues();
        Set<String> declared = Arrays.stream(SecurityEventType.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertThat(declared)
            .as("a constant the database refuses would be dropped silently, because the recorder "
                + "swallows DataAccessException so an audit outage cannot fail a login")
            .isSubsetOf(allowed);
    }

    @Test
    @DisplayName("Every value the CHECK permits has an enum constant to write it")
    void everyAllowedValueHasAConstant() throws IOException {
        Set<String> declared = Arrays.stream(SecurityEventType.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(checkConstraintValues())
            .as("a permitted value with no constant is a column the schema reserves for an event "
                + "nothing can emit")
            .allSatisfy(value -> assertThat(declared).contains(value));
    }

    @Test
    @DisplayName("The events collapse step 3 actually writes are all present")
    void step3EventsExist() {
        assertThat(Arrays.stream(SecurityEventType.values()).map(Enum::name))
            .contains("LOGIN_OK", "LOGIN_FAIL", "LOCKOUT",
                "TOTP_ENROL", "TOTP_DISABLE",
                "PASSWORD_CHANGE", "PASSWORD_RESET",
                "SESSION_REVOKE", "REFRESH_REUSE_DETECTED",
                "INVITE_CREATED", "INVITE_ACCEPTED");
    }

    @Test
    @DisplayName("Names fit the VARCHAR(40) the column declares")
    void namesFitTheColumn() {
        assertThat(Arrays.stream(SecurityEventType.values()).map(Enum::name))
            .allSatisfy(name -> assertThat(name.length()).isLessThanOrEqualTo(40));
    }
}
