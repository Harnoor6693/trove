package finance.trove.notifications.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.notifications.send.NotificationContent;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The digest's window and its money — gap P2-15 and finding F1.
 *
 * <p>The source did neither: it computed "this week" from the container's clock and hardcoded
 * {@code "currency": "CAD"} into the template variables (WeeklyDigestScheduler.java:101). The
 * first is wrong for every member outside UTC; the second is wrong for every US household and
 * silently wrong for a household holding both.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyDigestBuilderTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final UUID TRAVEL = UUID.randomUUID();

    /** Monday 7 September 2026, 08:00 UTC — the instant the cron fires. */
    private static final Instant TRIGGER = Instant.parse("2026-09-07T08:00:00Z");

    @Mock
    private TransactionQueryService transactions;

    private final Map<UUID, ZoneId> zones = new HashMap<>();

    private WeeklyDigestBuilder builderAt(Instant instant) {
        UserTimeZoneProvider provider = zones::get;
        return new WeeklyDigestBuilder(transactions,
            new UserClock(provider, Clock.fixed(instant, ZoneOffset.UTC)));
    }

    private UUID memberIn(String zone) {
        UUID id = UUID.randomUUID();
        zones.put(id, ZoneId.of(zone));
        return id;
    }

    private static Transaction row(String amount, CurrencyCode currency, TransactionType type,
                                   UUID categoryId) {
        Transaction transaction = new Transaction(HOUSEHOLD, UUID.randomUUID(),
            UUID.randomUUID(), null, new BigDecimal(amount), currency, type, "row", "MERCHANT",
            LocalDate.of(2026, 9, 2), null, false, true,
            Instant.parse("2026-09-02T00:00:00Z"));
        transaction.setCategoryId(categoryId);
        return transaction;
    }

    @Test
    @DisplayName("the window is the last COMPLETE Monday-to-Sunday week, in the member's own zone")
    void theWindowIsLastWeek() {
        UUID toronto = memberIn("America/Toronto");

        LocalDate weekStart = builderAt(TRIGGER).weekStartFor(toronto);

        assertThat(weekStart)
            .as("complete, not current: a digest sent four hours into Monday about Monday would "
                + "be about nothing")
            .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("Vancouver and Toronto get different weeks from the same UTC instant")
    void twoZonesGetDifferentWeeks() {
        // The instant that matters is one where the two calendars disagree about the DAY, which
        // is what makes this gap P2-15 rather than a rounding detail. 05:00 UTC on 7 September is
        // 01:00 on Monday in Toronto (UTC-4) and 22:00 on Sunday in Vancouver (UTC-7).
        Instant straddlingMidnight = Instant.parse("2026-09-07T05:00:00Z");
        UUID vancouver = memberIn("America/Vancouver");
        UUID toronto = memberIn("America/Toronto");
        WeeklyDigestBuilder builder = builderAt(straddlingMidnight);

        LocalDate vancouverWeek = builder.weekStartFor(vancouver);
        LocalDate torontoWeek = builder.weekStartFor(toronto);

        assertThat(vancouver).isNotEqualTo(toronto);
        assertThat(vancouverWeek)
            .as("it is still Sunday 6 September in Vancouver, so the last complete week began "
                + "on 24 August")
            .isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(torontoWeek)
            .as("it is already Monday 7 September in Toronto, so their last complete week began "
                + "on 31 August")
            .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(vancouverWeek).isNotEqualTo(torontoWeek);

        // And at the real trigger instant they agree, which is the other half of the same fact:
        // the boundary is the recipient's own midnight, not a fixed offset from the cron.
        WeeklyDigestBuilder atTrigger = builderAt(TRIGGER);
        assertThat(atTrigger.weekStartFor(vancouver))
            .isEqualTo(atTrigger.weekStartFor(toronto))
            .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("the dedup key is the ISO week, in the week-based year")
    void theKeyIsTheIsoWeek() {
        assertThat(WeeklyDigestBuilder.isoWeekOf(LocalDate.of(2026, 8, 31))).isEqualTo("2026-W36");
        assertThat(WeeklyDigestBuilder.isoWeekOf(LocalDate.of(2026, 1, 5))).isEqualTo("2026-W02");
        // 28 December 2026 is a Monday in ISO week 53 of the WEEK-BASED year 2026, even though
        // the days it covers run into January 2027. Keying on the calendar year would collide.
        assertThat(WeeklyDigestBuilder.isoWeekOf(LocalDate.of(2026, 12, 28)))
            .isEqualTo("2026-W53");
    }

    @Test
    @DisplayName("a household holding two currencies is reported per currency, never blended")
    void twoCurrenciesAreTwoLines() {
        // There is no exchange rate anywhere in this system (owner decision 3), so a single
        // "total spent" for a household with a CAD account and a USD one would be a made-up
        // number. The source's hardcoded "CAD" made exactly that number.
        when(transactions.findSpendableInRange(eq(HOUSEHOLD), eq(LocalDate.of(2026, 8, 31)),
            eq(LocalDate.of(2026, 9, 6)))).thenReturn(List.of(
                row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES),
                row("10.00", CurrencyCode.CAD, TransactionType.REFUND, GROCERIES),
                row("500.00", CurrencyCode.CAD, TransactionType.CREDIT, null),
                row("25.00", CurrencyCode.USD, TransactionType.DEBIT, TRAVEL)));

        List<WeeklyDigestSummary> summaries = builderAt(TRIGGER)
            .summarise(HOUSEHOLD, CurrencyCode.CAD, LocalDate.of(2026, 8, 31));

        assertThat(summaries).hasSize(2);
        WeeklyDigestSummary cad = summaries.get(0);
        assertThat(cad.currency()).isEqualTo(CurrencyCode.CAD);
        assertThat(cad.spent())
            .as("a REFUND nets down the spend it reverses, exactly as every other aggregate does")
            .isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(cad.income()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(cad.net()).isEqualByComparingTo(new BigDecimal("470.00"));
        assertThat(cad.transactionCount()).isEqualTo(3);
        assertThat(cad.topCategoryId()).isEqualTo(GROCERIES);

        WeeklyDigestSummary usd = summaries.get(1);
        assertThat(usd.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(usd.spent()).isEqualByComparingTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("the recipient's own currency is listed first, whatever the ledger holds")
    void theRecipientsCurrencyLeads() {
        when(transactions.findSpendableInRange(eq(HOUSEHOLD), eq(LocalDate.of(2026, 8, 31)),
            eq(LocalDate.of(2026, 9, 6)))).thenReturn(List.of(
                row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES),
                row("25.00", CurrencyCode.USD, TransactionType.DEBIT, TRAVEL)));

        List<WeeklyDigestSummary> summaries = builderAt(TRIGGER)
            .summarise(HOUSEHOLD, CurrencyCode.USD, LocalDate.of(2026, 8, 31));

        assertThat(summaries).extracting(WeeklyDigestSummary::currency)
            .containsExactly(CurrencyCode.USD, CurrencyCode.CAD);
    }

    @Test
    @DisplayName("a week with nothing in it still produces a digest that says so")
    void anEmptyWeekStillReports() {
        // Sending nothing is indistinguishable from the job being broken, which is precisely how
        // the source's failure went unnoticed for as long as it did.
        when(transactions.findSpendableInRange(eq(HOUSEHOLD), eq(LocalDate.of(2026, 8, 31)),
            eq(LocalDate.of(2026, 9, 6)))).thenReturn(List.of());

        List<WeeklyDigestSummary> summaries = builderAt(TRIGGER)
            .summarise(HOUSEHOLD, CurrencyCode.CAD, LocalDate.of(2026, 8, 31));
        NotificationContent content = builderAt(TRIGGER)
            .contentFor(LocalDate.of(2026, 8, 31), CurrencyCode.CAD, summaries);

        assertThat(summaries).isEmpty();
        assertThat(content.body()).contains("No transactions were recorded");
        assertThat(content.dedupKey()).isEqualTo("2026-W36");
        assertThat(content.subject()).isEqualTo("Your week: 2026-08-31 to 2026-09-06");
    }

    @Test
    @DisplayName("the content names every currency and never the literal CAD")
    void theContentCarriesTheRealCurrencies() {
        List<WeeklyDigestSummary> summaries = List.of(
            new WeeklyDigestSummary(CurrencyCode.USD, new BigDecimal("25.00"),
                BigDecimal.ZERO, 1, TRAVEL, new BigDecimal("25.00")));

        NotificationContent content = builderAt(TRIGGER)
            .contentFor(LocalDate.of(2026, 8, 31), CurrencyCode.USD, summaries);

        assertThat(content.body()).contains("USD: spent 25.00").doesNotContain("CAD");
        assertThat(content.templateModel()).containsEntry("primaryCurrency", "USD");
        assertThat(content.templateName()).isEqualTo("weekly-digest");
        assertThat(content.actionUrl()).isEqualTo("/dashboard");
    }

    @Test
    @DisplayName("a fee or an interest charge is counted but is neither spending nor income")
    void feesAreCountedNotClassified() {
        when(transactions.findSpendableInRange(eq(HOUSEHOLD), eq(LocalDate.of(2026, 8, 31)),
            eq(LocalDate.of(2026, 9, 6)))).thenReturn(List.of(
                row("4.00", CurrencyCode.CAD, TransactionType.FEE, null),
                row("1.00", CurrencyCode.CAD, TransactionType.INTEREST, null)));

        List<WeeklyDigestSummary> summaries = builderAt(TRIGGER)
            .summarise(HOUSEHOLD, CurrencyCode.CAD, LocalDate.of(2026, 8, 31));

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).spent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summaries.get(0).income()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summaries.get(0).transactionCount()).isEqualTo(2);
        assertThat(summaries.get(0).topCategoryId()).isNull();
    }
}
