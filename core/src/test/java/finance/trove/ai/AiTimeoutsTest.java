package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.HttpClientProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cap that keeps a per-user timeout from becoming a per-user way to pin a request thread.
 */
class AiTimeoutsTest {

    private static final HttpClientProperties DEFAULTS = new HttpClientProperties(5_000, 30_000);

    @Test
    @DisplayName("a connect timeout above the install's limit is cut down to it")
    void connectIsCappedAtTheInstallLimit() {
        assertThat(AiTimeouts.connect(30_000, DEFAULTS)).isEqualTo(Duration.ofMillis(5_000));
        assertThat(AiTimeouts.connect(1_000, DEFAULTS))
            .as("a member asking for less than the limit gets what they asked for")
            .isEqualTo(Duration.ofMillis(1_000));
    }

    @Test
    @DisplayName("the read ceiling is four times the install's read timeout — §5.5's 120 s")
    void readIsCappedAtFourTimesTheInstallLimit() {
        assertThat(AiTimeouts.read(600_000, DEFAULTS))
            .as("the maximum the settings schema allows, capped to the AI ceiling")
            .isEqualTo(Duration.ofMillis(120_000));
        assertThat(AiTimeouts.read(90_000, DEFAULTS)).isEqualTo(Duration.ofMillis(90_000));
    }

    @Test
    @DisplayName("an operator who lowers TROVE_HTTP_READ_TIMEOUT_MS lowers the AI ceiling with it")
    void theCeilingFollowsTheInstallSetting() {
        HttpClientProperties strict = new HttpClientProperties(2_000, 5_000);

        assertThat(AiTimeouts.read(120_000, strict))
            .as("the ceiling is derived from the install's own setting, not hard-coded")
            .isEqualTo(Duration.ofMillis(20_000));
        assertThat(AiTimeouts.connect(5_000, strict)).isEqualTo(Duration.ofMillis(2_000));
    }

    @Test
    @DisplayName("the class is a utility and refuses to be instantiated")
    void cannotBeInstantiated() throws Exception {
        Constructor<AiTimeouts> constructor = AiTimeouts.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance)
            .isInstanceOf(InvocationTargetException.class)
            .cause()
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
