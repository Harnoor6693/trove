package finance.trove.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Readiness gap P2-4's policy: length only, plus an offline deny-list.
 *
 * <p>No composition rules on purpose. "One upper, one digit, one symbol" measurably pushes people
 * towards {@code Password1!}, which is on every stuffing list, while a twelve-character floor and
 * a deny-list buy real entropy. These tests assert that the deny-list is actually loaded, because
 * a silently empty one would let the policy pass everything while appearing to be on.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy =
        new PasswordPolicy(new PasswordProperties(12, 128, 16, 32, 1, 1024, 1));

    @Test
    @DisplayName("The bundled deny-list is the specified top-10k, not a token sample")
    void denyListIsLoaded() {
        assertThat(policy.denyListSize())
            .as("§1.5 specifies a bundled offline top-10k list; an earlier build shipped 314 "
                + "entries with the assertion calibrated to it, so the list looked loaded while "
                + "missing almost everything a stuffing run actually tries")
            .isGreaterThanOrEqualTo(10_000);
    }

    @ParameterizedTest(name = "\"{0}\" is on the list and long enough to reach it")
    @ValueSource(strings = {
        "administrator", "accounting123", "barcelona1234", "anthony123456", "apple12345678",
        "adm1n1strat0r", "correcthorsebatterystaple",
    })
    @DisplayName("The long entries — the only ones the length floor lets through — are present")
    void rejectsLongDenyListEntries(String candidate) {
        // Spot-checking the top of the list would prove nothing: the 12-character floor runs
        // BEFORE the deny-list, so "123456" is refused for length and never consults it. Only
        // the 264 entries of twelve characters or more are reachable, and those are what a user
        // told "twelve characters minimum" actually picks.
        assertThat(candidate.length())
            .as("a spot-check shorter than the floor would pass for the wrong reason")
            .isGreaterThanOrEqualTo(12);
        assertThatThrownBy(() -> policy.validate(candidate, "someone@trove.test"))
            .isInstanceOf(PasswordPolicyException.class)
            .hasMessageContaining("commonly used");
    }

    @ParameterizedTest(name = "\"{0}\" is refused as a common password")
    @ValueSource(strings = {
        "password1234", "123456789012", "qwertyuiop123", "letmeinnow123", "iloveyou1234",
        "PASSWORD1234", "  password1234  ",
    })
    @DisplayName("Deny-list matching is case- and whitespace-insensitive")
    void rejectsCommonPasswords(String candidate) {
        assertThatThrownBy(() -> policy.validate(candidate, "someone@trove.test"))
            .isInstanceOf(PasswordPolicyException.class)
            .hasMessageContaining("commonly used");
    }

    @Test
    @DisplayName("Shorter than twelve characters is refused, twelve is accepted")
    void enforcesTheLengthFloor() {
        assertThatThrownBy(() -> policy.validate("elevenchars", "a@b.test"))
            .isInstanceOf(PasswordPolicyException.class)
            .hasMessageContaining("at least 12");
        assertThatCode(() -> policy.validate("twelve-chars", "a@b.test"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Longer than the cap is refused, so a huge input cannot be a memory-hard DoS")
    void enforcesTheLengthCeiling() {
        assertThatThrownBy(() -> policy.validate("a".repeat(129), "a@b.test"))
            .isInstanceOf(PasswordPolicyException.class)
            .hasMessageContaining("at most 128");
    }

    @Test
    @DisplayName("A short sequence repeated is no harder to guess than the sequence")
    void rejectsRepeatedFragments() {
        // Deliberately not "abcabcabcabc" or "aaaaaaaaaaaa": both are on the deny-list, so they
        // would be refused a step earlier and this rule would look tested while never running.
        for (String candidate : new String[] {"xyxyxyxyxyxy", "qzmqzmqzmqzm", "wxwxwxwxwxwx"}) {
            assertThatThrownBy(() -> policy.validate(candidate, "a@b.test"))
                .as("%s", candidate)
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("repeated");
        }
    }

    @Test
    @DisplayName("A password containing the email's local part is refused")
    void rejectsTheEmailLocalPart() {
        assertThatThrownBy(() -> policy.validate("harnoor-is-here", "harnoor@trove.test"))
            .isInstanceOf(PasswordPolicyException.class)
            .hasMessageContaining("email address");
    }

    @Test
    @DisplayName("A local part shorter than four characters is not matched")
    void ignoresShortLocalParts() {
        assertThatCode(() -> policy.validate("abc-is-a-fine-passphrase", "abc@trove.test"))
            .as("refusing every password containing 'abc' because the address is abc@ would be "
                + "an unhelpful rule with no security value")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Null and empty are refused before anything else looks at them")
    void rejectsAbsentPasswords() {
        assertThatThrownBy(() -> policy.validate(null, "a@b.test"))
            .isInstanceOf(PasswordPolicyException.class);
        assertThatThrownBy(() -> policy.validate("", "a@b.test"))
            .isInstanceOf(PasswordPolicyException.class);
    }

    @Test
    @DisplayName("The refusal names the password field, so it renders under the right input")
    void carriesAFieldError() {
        PasswordPolicyException thrown = new PasswordPolicyException("too short");
        assertThat(thrown.getFieldErrors()).singleElement()
            .satisfies(error -> {
                assertThat(error.field()).isEqualTo("password");
                assertThat(error.message()).isEqualTo("too short");
            });
        assertThat(thrown.getSlug()).isEqualTo("password-policy");
    }

    @Test
    @DisplayName("A long passphrase with no symbols at all is accepted")
    void acceptsAPlainPassphrase() {
        assertThatCode(() -> policy.validate("correct horse battery staple", "a@b.test"))
            .doesNotThrowAnyException();
    }
}
