package finance.trove.budgets.period;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.period.dto.BudgetPeriodRequest;
import finance.trove.budgets.period.dto.BudgetPeriodUpdateRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BudgetPeriodService} — month normalisation, the one-period-per-household rule, the
 * {@code ZERO_BASED} invariant, and the per-member month boundary.
 */
@ExtendWith(MockitoExtension.class)
class BudgetPeriodServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID PERIOD = UUID.randomUUID();
    private static final ZoneId VANCOUVER = ZoneId.of("America/Vancouver");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final UUID vancouverMember = UUID.randomUUID();
    private final UUID torontoMember = UUID.randomUUID();

    private final UserTimeZoneProvider zones = userId -> Map.of(
        vancouverMember, VANCOUVER,
        torontoMember, TORONTO).getOrDefault(userId, ZoneOffset.UTC);

    @Mock
    private BudgetPeriodRepository periods;

    @Mock
    private EnvelopeRepository envelopes;

    @Mock
    private UserRepository users;

    private BudgetPeriodService service(Instant now) {
        return new BudgetPeriodService(periods, envelopes, users,
            new UserClock(zones, Clock.fixed(now, ZoneOffset.UTC)));
    }

    private BudgetPeriodService service() {
        return service(Instant.parse("2026-09-15T12:00:00Z"));
    }

    private static BudgetPeriod period(YearMonth month, String totalIncome, BudgetType type) {
        return new BudgetPeriod(HOUSEHOLD, MEMBER, month, new BigDecimal(totalIncome),
            CurrencyCode.CAD, type, Instant.parse("2026-09-01T00:00:00Z"));
    }

    private void memberCurrencyIs(CurrencyCode currency) {
        when(users.findByIdAndHouseholdId(MEMBER, HOUSEHOLD)).thenReturn(Optional.of(
            new User(HOUSEHOLD, UserRole.ADMIN, "member@trove.test", "Member", Jurisdiction.CA,
                currency, ZoneOffset.UTC)));
    }

    @Nested
    @DisplayName("creating a period")
    class Creating {

        @Test
        @DisplayName("any day of the month is stored as the first of it")
        void theMonthIsNormalisedToDayOne() {
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.empty());
            when(periods.save(any(BudgetPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
            memberCurrencyIs(CurrencyCode.CAD);

            BudgetPeriod created = service().create(HOUSEHOLD, MEMBER, new BudgetPeriodRequest(
                LocalDate.of(2026, 9, 23), new BigDecimal("5000.00"), null, null));

            assertThat(created.getMonth())
                .as("the unique index on (household_id, month) is only meaningful because every "
                    + "row for September is stored as the same date")
                .isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(created.getBudgetType())
                .as("an omitted type is FLEXIBLE")
                .isEqualTo(BudgetType.FLEXIBLE);
        }

        @Test
        @DisplayName("an omitted currency comes from the creating member, never from a literal")
        void currencyDefaultsToTheCreatingMember() {
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.empty());
            when(periods.save(any(BudgetPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
            memberCurrencyIs(CurrencyCode.USD);

            BudgetPeriod created = service().create(HOUSEHOLD, MEMBER, new BudgetPeriodRequest(
                LocalDate.of(2026, 9, 1), new BigDecimal("5000.00"), null, null));

            assertThat(created.getCurrency())
                .as("finding F1: the source defaulted twelve executable sites to the CAD literal")
                .isEqualTo(CurrencyCode.USD);
        }

        @Test
        @DisplayName("a second period for a month the household already has is a conflict")
        void oneHouseholdHasOneJanuary() {
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.of(period(YearMonth.of(2026, 9), "5000.00",
                    BudgetType.FLEXIBLE)));

            assertThatThrownBy(() -> service().create(HOUSEHOLD, MEMBER, new BudgetPeriodRequest(
                LocalDate.of(2026, 9, 30), new BigDecimal("6000.00"), null, null)))
                .as("under one shared ledger two members must not each own a September; the "
                    + "source's UNIQUE (user_id, month) would have let both succeed")
                .isInstanceOf(ConflictException.class);
            verify(periods, never()).save(any());
        }

        @Test
        @DisplayName("a ZERO_BASED period can be opened before it has any envelope")
        void zeroBasedIsNotCheckedBeforeItsFirstEnvelope() {
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.empty());
            when(periods.save(any(BudgetPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            BudgetPeriod created = service().create(HOUSEHOLD, MEMBER, new BudgetPeriodRequest(
                LocalDate.of(2026, 9, 1), new BigDecimal("5000.00"), CurrencyCode.CAD,
                BudgetType.ZERO_BASED));

            assertThat(created.getBudgetType()).isEqualTo(BudgetType.ZERO_BASED);
        }
    }

    @Nested
    @DisplayName("the ZERO_BASED invariant")
    class ZeroBased {

        @Test
        @DisplayName("allocations that stop summing to the income are 400, not 409 and not 422")
        void aBrokenInvariantIsAValidationError() {
            BudgetPeriod zeroBased = period(YearMonth.of(2026, 9), "5000.00",
                BudgetType.ZERO_BASED);

            assertThatThrownBy(() -> BudgetPeriodService.requireZeroBasedInvariant(zeroBased,
                new BigDecimal("4800.00")))
                .as("the source answered 422, a status this contract has no slug for; of the two "
                    + "that exist, conflict would tell the client to reload and retry a body that "
                    + "can never succeed")
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("4800.00")
                .hasMessageContaining("5000.00");
        }

        @Test
        @DisplayName("exact equality passes; FLEXIBLE and ROLLOVER are never checked")
        void onlyZeroBasedIsConstrained() {
            BudgetPeriodService.requireZeroBasedInvariant(
                period(YearMonth.of(2026, 9), "5000.00", BudgetType.ZERO_BASED),
                new BigDecimal("5000.0000"));
            BudgetPeriodService.requireZeroBasedInvariant(
                period(YearMonth.of(2026, 9), "5000.00", BudgetType.FLEXIBLE),
                new BigDecimal("1.00"));
            BudgetPeriodService.requireZeroBasedInvariant(
                period(YearMonth.of(2026, 9), "5000.00", BudgetType.ROLLOVER),
                new BigDecimal("99999.00"));
        }

        @Test
        @DisplayName("changing the income under existing envelopes is refused")
        void theIncomeCannotDriftAwayFromTheAllocations() {
            BudgetPeriod zeroBased = period(YearMonth.of(2026, 9), "5000.00",
                BudgetType.ZERO_BASED);
            when(periods.findByIdAndHouseholdId(PERIOD, HOUSEHOLD))
                .thenReturn(Optional.of(zeroBased));
            when(envelopes.existsByPeriodId(PERIOD)).thenReturn(true);
            when(envelopes.sumAllocatedByPeriodId(PERIOD)).thenReturn(new BigDecimal("5000.0000"));

            assertThatThrownBy(() -> service().update(HOUSEHOLD, PERIOD,
                new BudgetPeriodUpdateRequest(new BigDecimal("6000.00"),
                    BudgetType.ZERO_BASED)))
                .as("the source validated envelope writes against totalIncome but let "
                    + "totalIncome itself move underneath them")
                .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("a period with no envelopes may still change its income")
        void anEmptyPeriodIsNotConstrained() {
            BudgetPeriod zeroBased = period(YearMonth.of(2026, 9), "5000.00",
                BudgetType.ZERO_BASED);
            when(periods.findByIdAndHouseholdId(PERIOD, HOUSEHOLD))
                .thenReturn(Optional.of(zeroBased));
            when(envelopes.existsByPeriodId(PERIOD)).thenReturn(false);
            when(periods.save(any(BudgetPeriod.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            BudgetPeriod updated = service().update(HOUSEHOLD, PERIOD,
                new BudgetPeriodUpdateRequest(new BigDecimal("6000.00"), BudgetType.ZERO_BASED));

            assertThat(updated.getTotalIncome()).isEqualByComparingTo(new BigDecimal("6000.00"));
        }
    }

    @Nested
    @DisplayName("GET /v1/budgets/current resolves the month where the CALLER is")
    class PerMemberMonthBoundary {

        /**
         * 2026-10-01T04:30Z. In Toronto (UTC-4 on that date) it is 00:30 on the 1st of October; in
         * Vancouver (UTC-7) it is still 21:30 on the 30th of September. One instant, two months.
         */
        private final Instant aroundTheBoundary = Instant.parse("2026-10-01T04:30:00Z");

        @Test
        @DisplayName("two members of one household see different months for a few hours")
        void vancouverAndTorontoDisagreeAcrossTheBoundary() {
            BudgetPeriod september = period(YearMonth.of(2026, 9), "5000.00",
                BudgetType.FLEXIBLE);
            BudgetPeriod october = period(YearMonth.of(2026, 10), "5000.00", BudgetType.FLEXIBLE);
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 9, 1)))
                .thenReturn(Optional.of(september));
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 10, 1)))
                .thenReturn(Optional.of(october));

            BudgetPeriodService service = service(aroundTheBoundary);

            assertThat(service.current(HOUSEHOLD, vancouverMember).getMonth())
                .as("still September on the west coast — the pre-collapse BudgetService.java:141 "
                    + "called LocalDate.now() with no zone against a UTC container, so this "
                    + "member's month rolled over at 17:00 on the 30th")
                .isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(service.current(HOUSEHOLD, torontoMember).getMonth())
                .as("already October in the east, from the very same instant")
                .isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        @DisplayName("a month with no period is 404, not an empty 200")
        void aMissingCurrentMonthIsNotFound() {
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 10, 1)))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                service(aroundTheBoundary).current(HOUSEHOLD, torontoMember))
                .as("§4.6 is explicit: orElseThrow(ResourceNotFoundException), so a client never "
                    + "has to tell 'no budget yet' from 'a budget with no envelopes' by "
                    + "inspecting the body")
                .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a DST spring-forward month still starts at that member's own midnight")
        void aDaylightSavingTransitionDoesNotMoveTheMonth() {
            // 2026-03-01T06:30Z: 22:30 on the 28th of February in Vancouver (still PST), 01:30 on
            // the 1st of March in Toronto. The transition itself lands on the 8th; what matters
            // here is that the boundary is the member's local midnight either way.
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 2, 1)))
                .thenReturn(Optional.of(period(YearMonth.of(2026, 2), "5000.00",
                    BudgetType.FLEXIBLE)));
            when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, LocalDate.of(2026, 3, 1)))
                .thenReturn(Optional.of(period(YearMonth.of(2026, 3), "5000.00",
                    BudgetType.FLEXIBLE)));

            BudgetPeriodService service = service(Instant.parse("2026-03-01T06:30:00Z"));

            assertThat(service.current(HOUSEHOLD, vancouverMember).getMonth())
                .isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(service.current(HOUSEHOLD, torontoMember).getMonth())
                .isEqualTo(LocalDate.of(2026, 3, 1));
        }
    }

    @Test
    @DisplayName("a period of another household is not found, never forbidden")
    void aForeignPeriodIsNotFound() {
        when(periods.findByIdAndHouseholdId(PERIOD, HOUSEHOLD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().get(HOUSEHOLD, PERIOD))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deleting a period that is not this household's is not found")
    void deletingAForeignPeriodIsNotFound() {
        when(periods.deleteByIdAndHouseholdId(PERIOD, HOUSEHOLD)).thenReturn(0L);

        assertThatThrownBy(() -> service().delete(HOUSEHOLD, PERIOD))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
