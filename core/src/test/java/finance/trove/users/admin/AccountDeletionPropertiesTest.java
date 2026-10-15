package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gap row 16's seven days, and the value the record refuses to boot with. */
class AccountDeletionPropertiesTest {

    @Test
    @DisplayName("The default restore window is gap row 16's seven days")
    void theDefaultIsSevenDays() {
        assertThat(new AccountDeletionProperties(null).grace()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("A zero grace would let the next purge pass erase an account nobody could recall")
    void aZeroGraceIsRefused() {
        assertThatThrownBy(() -> new AccountDeletionProperties(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("deletion-grace");
    }
}
