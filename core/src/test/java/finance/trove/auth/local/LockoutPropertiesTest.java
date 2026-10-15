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
        assertThat(properties.hardThreshold()).isEqualTo(10);
        assertThat(properties.hardLock()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.ipThreshold())
            .as("P2-5: 20 failures per address per 10 minutes")
            .isEqualTo(20);
        assertThat(properties.ipWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("A hard threshold below the soft one is a configuration error")
    void refusesAnUnreachableHardLock() {
        assertThatThrownBy(() ->
            new LockoutProperties(10, null, null, null, 5, null, 0, null))
            .as("a hard threshold under the soft one means the hard lock fires first and the "
                + "backoff never runs, which is not what the operator asked for")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must not be below threshold");
    }
}
