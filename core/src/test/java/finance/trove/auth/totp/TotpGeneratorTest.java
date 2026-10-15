package finance.trove.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 6238 conformance, against the specification's own test vectors.
 *
 * <p>This matters more than most correctness tests: a TOTP implementation that is subtly wrong
 * still produces plausible six-digit numbers, still verifies its own output, and still passes any
 * round-trip test written against itself. It only fails against a real authenticator app, at which
 * point the user has already enrolled and is locked out of their finance server. The published
 * vectors are the only check that catches that before a user does.
 *
 * <p>The vectors use the ASCII secret {@code 12345678901234567890}, whose base32 form is
 * {@code GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ}, with SHA-1 and a 30-second step. RFC 6238 publishes
 * eight-digit codes; the last six of each are what a six-digit configuration produces.
 */
class TotpGeneratorTest {

    /** RFC 6238's published test secret, not a credential. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"; // gitleaks:allow
    private static final int PERIOD = 30;

    private final TotpGenerator generator = new TotpGenerator();

    @ParameterizedTest(name = "T={0} produces {1}")
    @CsvSource({
        "59,           287082",
        "1111111109,   081804",
        "1111111111,   050471",
        "1234567890,   005924",
        "2000000000,   279037",
        "20000000000,  353130",
    })
    @DisplayName("RFC 6238 §5 test vectors (SHA-1, 30 s, six digits)")
    void matchesTheRfcVectors(long epochSeconds, String expected) {
        long step = generator.timeStep(Instant.ofEpochSecond(epochSeconds), PERIOD);
        assertThat(generator.codeAt(RFC_SECRET, step, 6)).isEqualTo(expected);
    }

    @Test
    @DisplayName("timeStep is the floor of seconds/period")
    void timeStepFloors() {
        assertThat(generator.timeStep(Instant.ofEpochSecond(0), PERIOD)).isZero();
        assertThat(generator.timeStep(Instant.ofEpochSecond(29), PERIOD)).isZero();
        assertThat(generator.timeStep(Instant.ofEpochSecond(30), PERIOD)).isEqualTo(1);
        assertThat(generator.timeStep(Instant.ofEpochSecond(59), PERIOD)).isEqualTo(1);
    }

    @Test
    @DisplayName("Codes are zero-padded to the configured width")
    void codesAreZeroPadded() {
        String secret = generator.generateSecret();
        for (long step = 0; step < 500; step++) {
            assertThat(generator.codeAt(secret, step, 6))
                .as("step %s", step)
                .hasSize(6)
                .matches("\\d{6}");
        }
    }

    @Test
    @DisplayName("A generated secret is valid base32 and round-trips through decode")
    void generatedSecretsAreUsable() {
        String secret = generator.generateSecret();
        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(TotpGenerator.base32Encode(TotpGenerator.base32Decode(secret)))
            .isEqualTo(secret);
    }

    @Test
    @DisplayName("Decoding ignores padding, spaces and case, because people retype secrets")
    void decodingIsForgiving() {
        byte[] canonical = TotpGenerator.base32Decode(RFC_SECRET);
        assertThat(TotpGenerator.base32Decode(RFC_SECRET.toLowerCase(java.util.Locale.ROOT)))
            .isEqualTo(canonical);
        assertThat(TotpGenerator.base32Decode("GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ"))
            .isEqualTo(canonical);
        assertThat(TotpGenerator.base32Decode(RFC_SECRET + "======")).isEqualTo(canonical);
    }

    @Test
    @DisplayName("Verification accepts the current step and one step either side, and no more")
    void driftWindowIsExactlyOneStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = generator.timeStep(now, PERIOD);

        for (long offset : new long[] {-1, 0, 1}) {
            String code = generator.codeAt(RFC_SECRET, current + offset, 6);
            assertThat(generator.verify(RFC_SECRET, code, now, PERIOD, 6, 1, -1))
                .as("offset %s should be accepted", offset)
                .isEqualTo(current + offset);
        }
        for (long offset : new long[] {-2, 2}) {
            String code = generator.codeAt(RFC_SECRET, current + offset, 6);
            assertThat(generator.verify(RFC_SECRET, code, now, PERIOD, 6, 1, -1))
                .as("offset %s widens the replay window and must be refused", offset)
                .isNegative();
        }
    }

    @Test
    @DisplayName("A step already used is refused — the replay guard of gap P2-6")
    void refusesAReplayedStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = generator.timeStep(now, PERIOD);
        String code = generator.codeAt(RFC_SECRET, current, 6);

        assertThat(generator.verify(RFC_SECRET, code, now, PERIOD, 6, 1, -1)).isEqualTo(current);
        assertThat(generator.verify(RFC_SECRET, code, now, PERIOD, 6, 1, current))
            .as("the same six digits inside one window must be accepted exactly once, or a code "
                + "shoulder-surfed or captured from a proxy can be resubmitted while still valid")
            .isNegative();
    }

    @Test
    @DisplayName("An earlier step than the last used one is also refused")
    void refusesAnEarlierStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = generator.timeStep(now, PERIOD);
        String previous = generator.codeAt(RFC_SECRET, current - 1, 6);

        assertThat(generator.verify(RFC_SECRET, previous, now, PERIOD, 6, 1, current))
            .isNegative();
    }

    @Test
    @DisplayName("A wrong, malformed or empty code is refused rather than throwing")
    void refusesRubbish() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        for (String candidate : new String[] {"000000", "abcdef", "", "   ", "1234567890"}) {
            assertThat(generator.verify(RFC_SECRET, candidate, now, PERIOD, 6, 1, -1))
                .as("%s", candidate)
                .isNegative();
        }
    }

    @Test
    @DisplayName("The otpauth URI carries issuer, account and every parameter an app needs")
    void otpauthUri() {
        String uri = generator.otpauthUri("Trove", "member@trove.test", RFC_SECRET, 6, 30);

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("Trove");
        assertThat(uri).contains("member%40trove.test");
        assertThat(uri).contains("secret=" + RFC_SECRET);
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
        assertThat(uri)
            .as("an app that assumes SHA-256 because the URI did not say produces codes that "
                + "never verify, and the user has no way to tell why")
            .contains("algorithm=SHA1");
    }
}
