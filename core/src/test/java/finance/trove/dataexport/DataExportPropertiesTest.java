package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The defaults gap row 16 names, and the values the record refuses to boot with. */
class DataExportPropertiesTest {

    @Test
    @DisplayName("The defaults are gap row 16's: a 24-hour link and one run a day")
    void theDefaultsAreTheOnesTheGapRowNames() {
        DataExportProperties properties = new DataExportProperties(null, null, null);

        assertThat(properties.downloadTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.runsPerDay()).isEqualTo(1);
        assertThat(properties.sweepBatchSize()).isPositive();
    }

    @Test
    @DisplayName("A zero link lifetime fails the context refresh rather than the first download")
    void aZeroTtlIsRefused() {
        assertThatThrownBy(() -> new DataExportProperties(Duration.ZERO, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("download-ttl");
    }

    @Test
    @DisplayName("A cap of zero would refuse every export on an install that thinks it is on")
    void aZeroCapIsRefused() {
        assertThatThrownBy(() -> new DataExportProperties(null, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs-per-day");
    }

    @Test
    @DisplayName("A sweep batch of zero would run for ever and delete nothing")
    void aZeroBatchIsRefused() {
        assertThatThrownBy(() -> new DataExportProperties(null, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sweep-batch-size");
    }
}
