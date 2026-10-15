package finance.trove.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LockoutPropertiesTest {

    @Test
    @DisplayName("Absent values take readiness gap P2-5's numbers")
    void defaults() {
        LockoutProperties properties =
            new LockoutProperties(0, null, null, null, 0, null, 0, null);

        assertThat(properties.threshold()).isEqualTo(5);
        assertThat(properties.window()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.baseBackoff()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.maxBackoff()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.accountThreshold())
            .as("OWASP ASVS v4 2.2.1: no more than 100 failed attempts an hour on one account")
            .isEqualTo(100);
        assertThat(properties.accountWindow()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.ipThreshold())
            .as("P2-5: 20 failures per address per 10 minutes")
            .isEqualTo(20);
        assertThat(properties.ipWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("An account ceiling below the per-address threshold is a configuration error")
    void refusesACeilingBelowTheThreshold() {
        assertThatThrownBy(() ->
            new LockoutProperties(10, null, null, null, 5, null, 0, null))
            .as("a ceiling under the per-address threshold throttles every unrecognised address "
                + "before any one of them could be, which is not what the operator asked for")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must not be below threshold");
    }
}
