package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImportLimits}' defaults are the numbers §1.6 names, and an absurd configured value falls
 * back to them rather than switching a ceiling off.
 */
class ImportLimitsTest {

    @Test
    @DisplayName("the documented defaults are what an unset or absurd value resolves to")
    void absurdValuesFallBackToTheDocumentedDefaults() {
        ImportLimits zeroed = new ImportLimits(0, 0, 0, null, 0, 0, 0, null);

        assertThat(zeroed.maxFileBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(zeroed.maxRows()).isEqualTo(50_000);
        assertThat(zeroed.maxFieldChars()).isEqualTo(1_000);
        assertThat(zeroed.parseBudget()).isEqualTo(Duration.ofSeconds(60));
        assertThat(zeroed.previewRows()).isPositive();
        assertThat(zeroed.previewSampleValues()).isPositive();
        assertThat(zeroed.householdUploadQuotaBytes())
            .isEqualTo(ImportLimits.DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES);
        assertThat(zeroed.previewRetention())
            .as("an unset retention must not mean 'keep every abandoned upload for ever'")
            .isEqualTo(ImportLimits.DEFAULT_PREVIEW_RETENTION);

        assertThat(new ImportLimits(-1, -1, -1, Duration.ZERO, -1, -1, -1, Duration.ZERO)
            .parseBudget())
            .as("a zero budget would mean no parse could ever finish, not an unlimited one")
            .isEqualTo(Duration.ofSeconds(60));
        assertThat(ImportLimits.defaults()).isEqualTo(zeroed);
    }

    @Test
    @DisplayName("a configured value is honoured")
    void configuredValuesAreHonoured() {
        ImportLimits configured = new ImportLimits(1, 2, 3, Duration.ofSeconds(4), 5, 6, 7,
            Duration.ofDays(8));

        assertThat(configured.maxFileBytes()).isEqualTo(1);
        assertThat(configured.maxRows()).isEqualTo(2);
        assertThat(configured.maxFieldChars()).isEqualTo(3);
        assertThat(configured.parseBudget()).isEqualTo(Duration.ofSeconds(4));
        assertThat(configured.previewRows()).isEqualTo(5);
        assertThat(configured.previewSampleValues()).isEqualTo(6);
        assertThat(configured.householdUploadQuotaBytes()).isEqualTo(7);
        assertThat(configured.previewRetention()).isEqualTo(Duration.ofDays(8));
    }
}
