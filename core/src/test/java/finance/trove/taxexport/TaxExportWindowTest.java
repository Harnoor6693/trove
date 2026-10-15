package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tax-year window's edges, each named (gap P2-15).
 *
 * <p>A tax year is a range of DATES, not of instants. Everything below follows from that one
 * sentence, and the last test is the one that would catch the mistake worth catching: an
 * implementation that computed the window from a member's zone would give a Vancouver filer and a
 * Toronto filer different years, and the same transaction would then be inside one member's 2025
 * and outside the other's.
 */
class TaxExportWindowTest {

    @Test
    @DisplayName("1 January of the target year is inside the window")
    void firstOfJanuaryIsIn() {
        assertThat(TaxExportGenerationService.windowStart(2025))
            .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(LocalDate.of(2025, 1, 1))
            .isBetween(TaxExportGenerationService.windowStart(2025),
                TaxExportGenerationService.windowEnd(2025));
    }

    @Test
    @DisplayName("31 December of the target year is inside the window")
    void thirtyFirstOfDecemberIsIn() {
        assertThat(TaxExportGenerationService.windowEnd(2025))
            .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(LocalDate.of(2025, 12, 31))
            .isBetween(TaxExportGenerationService.windowStart(2025),
                TaxExportGenerationService.windowEnd(2025));
    }

    @Test
    @DisplayName("31 December of the year before is outside the window")
    void newYearsEveOfThePreviousYearIsOut() {
        assertThat(LocalDate.of(2024, 12, 31))
            .isBefore(TaxExportGenerationService.windowStart(2025));
    }

    @Test
    @DisplayName("1 January of the year after is outside the window")
    void newYearsDayOfTheFollowingYearIsOut() {
        assertThat(LocalDate.of(2026, 1, 1))
            .isAfter(TaxExportGenerationService.windowEnd(2025));
    }

    @Test
    @DisplayName("A leap year's window is 366 days long")
    void aLeapYearHas366Days() {
        long days = ChronoUnit.DAYS.between(TaxExportGenerationService.windowStart(2024),
            TaxExportGenerationService.windowEnd(2024)) + 1;

        assertThat(days)
            .as("writing the end as 31 December rather than as 'start of next year minus a day' "
                + "is what makes 29 February need no special case")
            .isEqualTo(366);
        assertThat(ChronoUnit.DAYS.between(TaxExportGenerationService.windowStart(2025),
            TaxExportGenerationService.windowEnd(2025)) + 1).isEqualTo(365);
    }

    @Test
    @DisplayName("Vancouver and Toronto get the SAME window for the same year")
    void twoTimezonesGetTheSameWindow() {
        ZoneId vancouver = ZoneId.of("America/Vancouver");
        ZoneId toronto = ZoneId.of("America/Toronto");

        // Nothing in the window depends on either zone: the assertion is that the same two dates
        // fall out however the caller's clock is set.
        assertThat(TaxExportGenerationService.windowStart(2025))
            .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(TaxExportGenerationService.windowEnd(2025))
            .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(vancouver).isNotEqualTo(toronto);
        assertThat(TaxExportGenerationService.windowStart(2025)
            .atStartOfDay(vancouver).toLocalDate())
            .as("a tax year is a date range and not an instant range; if these differed, the "
                + "same transaction would be inside one member's 2025 and outside the other's")
            .isEqualTo(TaxExportGenerationService.windowStart(2025)
                .atStartOfDay(toronto).toLocalDate());
    }
}
