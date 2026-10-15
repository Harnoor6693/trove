package finance.trove.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Per-user date boundaries (gap P2-15). The DST cases are the point: a day is not 24 hours and a
 * month does not start at the same UTC instant all year, and every "today"/"this month" query in
 * the ledger depends on getting that right.
 */
class UserClockTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private final UUID torontoUser = UUID.randomUUID();
    private final UUID losAngelesUser = UUID.randomUUID();

    private final UserTimeZoneProvider zones = userId -> Map.of(
        torontoUser, TORONTO,
        losAngelesUser, LOS_ANGELES).getOrDefault(userId, ZoneOffset.UTC);

    @Nested
    @DisplayName("today and this month depend on where the user is")
    class PerUserBoundaries {

        /** 2026-01-01T03:00Z: still 2025-12-31 in Toronto, and in Los Angeles. */
        private final UserClock clock = new UserClock(zones,
            Clock.fixed(Instant.parse("2026-01-01T03:00:00Z"), ZoneOffset.UTC));

        @Test
        @DisplayName("the same instant is a different date, month and year per user")
        void twoUsersDisagreeAboutTodayFromOneInstant() {
            assertThat(clock.today(torontoUser)).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(clock.today(losAngelesUser)).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(clock.today(UUID.randomUUID())).isEqualTo(LocalDate.of(2026, 1, 1));

            assertThat(clock.currentMonth(torontoUser)).isEqualTo(YearMonth.of(2025, 12));
            assertThat(clock.currentMonth(UUID.randomUUID())).isEqualTo(YearMonth.of(2026, 1));
        }

        @Test
        @DisplayName("a 6pm Vancouver purchase belongs to that day, not to the UTC tomorrow")
        void anEveningTransactionStaysOnItsOwnDay() {
            Instant sixPmPacific = Instant.parse("2026-01-01T02:00:00Z");

            assertThat(clock.dateOf(losAngelesUser, sixPmPacific))
                .isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(clock.monthOf(losAngelesUser, sixPmPacific))
                .isEqualTo(YearMonth.of(2025, 12));
            assertThat(clock.dateOf(UUID.randomUUID(), sixPmPacific))
                .isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        void nowIsZoneFreeButNowForIsNot() {
            assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-01T03:00:00Z"));
            assertThat(clock.nowFor(torontoUser).getZone()).isEqualTo(TORONTO);
            assertThat(clock.nowFor(torontoUser).getHour()).isEqualTo(22);
        }

        @Test
        @DisplayName("todayRange brackets the user's own day")
        void todayRangeUsesTheUsersDay() {
            InstantRange range = clock.todayRange(torontoUser);

            assertThat(range.contains(clock.now())).isTrue();
            assertThat(range.start()).isEqualTo(Instant.parse("2025-12-31T05:00:00Z"));
            assertThat(range.end()).isEqualTo(Instant.parse("2026-01-01T05:00:00Z"));
        }

        @Test
        void aNullZoneFromAProviderDegradesToUtcRatherThanThrowing() {
            UserClock nullZoned = new UserClock(userId -> null,
                Clock.fixed(Instant.parse("2026-01-01T03:00:00Z"), ZoneOffset.UTC));

            assertThat(nullZoned.zoneOf(torontoUser)).isEqualTo(ZoneOffset.UTC);
            assertThat(nullZoned.today(torontoUser)).isEqualTo(LocalDate.of(2026, 1, 1));
        }
    }

    @Nested
    @DisplayName("DST transitions")
    class DaylightSaving {

        private final UserClock clock = new UserClock(zones,
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));

        @Test
        @DisplayName("America/Toronto: the spring-forward Sunday is 23 hours long")
        void torontoSpringForwardDayIsShort() {
            InstantRange day = clock.dayRange(torontoUser, LocalDate.of(2026, 3, 8));

            assertThat(day.start()).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
            assertThat(day.end()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
            assertThat(day.duration()).isEqualTo(Duration.ofHours(23));
        }

        @Test
        @DisplayName("America/Toronto: the fall-back Sunday is 25 hours long")
        void torontoFallBackDayIsLong() {
            InstantRange day = clock.dayRange(torontoUser, LocalDate.of(2026, 11, 1));

            assertThat(day.start()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
            assertThat(day.end()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
            assertThat(day.duration()).isEqualTo(Duration.ofHours(25));
        }

        @Test
        @DisplayName("America/Los_Angeles transitions on the same dates, three hours later")
        void losAngelesTransitionsTooAtItsOwnOffset() {
            assertThat(clock.dayRange(losAngelesUser, LocalDate.of(2026, 3, 8)).duration())
                .isEqualTo(Duration.ofHours(23));
            assertThat(clock.dayRange(losAngelesUser, LocalDate.of(2026, 11, 1)).duration())
                .isEqualTo(Duration.ofHours(25));
            assertThat(clock.startOfDay(losAngelesUser, LocalDate.of(2026, 3, 8)))
                .isEqualTo(Instant.parse("2026-03-08T08:00:00Z"));
        }

        @Test
        @DisplayName("a month that contains a transition still starts and ends at local midnight")
        void monthRangeAbsorbsTheTransition() {
            InstantRange march = clock.monthRange(torontoUser, YearMonth.of(2026, 3));

            assertThat(march.start()).isEqualTo(Instant.parse("2026-03-01T05:00:00Z"));
            assertThat(march.end()).isEqualTo(Instant.parse("2026-04-01T04:00:00Z"));
            assertThat(march.duration()).isEqualTo(Duration.ofHours(31 * 24 - 1));

            InstantRange november = clock.monthRange(torontoUser, YearMonth.of(2026, 11));
            assertThat(november.start()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
            assertThat(november.end()).isEqualTo(Instant.parse("2026-12-01T05:00:00Z"));
            assertThat(november.duration()).isEqualTo(Duration.ofHours(30 * 24 + 1));
        }

        @Test
        @DisplayName("a December-to-January month range crosses the year correctly")
        void monthRangeCrossesTheYearBoundary() {
            InstantRange december = clock.monthRange(losAngelesUser, YearMonth.of(2026, 12));

            assertThat(december.start()).isEqualTo(Instant.parse("2026-12-01T08:00:00Z"));
            assertThat(december.end()).isEqualTo(Instant.parse("2027-01-01T08:00:00Z"));
        }

        @Test
        void endOfDayIsTheStartOfTheNext() {
            LocalDate date = LocalDate.of(2026, 11, 1);

            assertThat(clock.endOfDayExclusive(torontoUser, date))
                .isEqualTo(clock.startOfDay(torontoUser, date.plusDays(1)));
        }
    }

    @Nested
    @DisplayName("clock-skew tolerance")
    class Skew {

        private final UserClock clock = new UserClock(zones,
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC));

        @Test
        @DisplayName("60 seconds either way is tolerated; 61 is not (gap P2-15's no-RTC boot)")
        void toleratesSixtySecondsEitherWay() {
            assertThat(UserClock.CLOCK_SKEW_TOLERANCE).isEqualTo(Duration.ofSeconds(60));

            assertThat(clock.withinSkewTolerance(clock.now())).isTrue();
            assertThat(clock.withinSkewTolerance(clock.now().plusSeconds(60))).isTrue();
            assertThat(clock.withinSkewTolerance(clock.now().minusSeconds(60))).isTrue();
            assertThat(clock.withinSkewTolerance(clock.now().plusSeconds(61))).isFalse();
            assertThat(clock.withinSkewTolerance(clock.now().minusSeconds(61))).isFalse();
        }
    }

    @Nested
    @DisplayName("the default provider")
    class DefaultProvider {

        @Test
        void usesTheConfiguredZoneForEveryUser() {
            DefaultUserTimeZoneProvider provider =
                new DefaultUserTimeZoneProvider(new TimeProperties("America/Toronto"));

            assertThat(provider.zoneOf(UUID.randomUUID())).isEqualTo(TORONTO);
            assertThat(provider.zoneOf(null)).isEqualTo(TORONTO);
        }

        @Test
        @DisplayName("an unset TROVE_DEFAULT_TIMEZONE is UTC, not the container's local zone")
        void defaultsToUtc() {
            assertThat(new TimeProperties(null).defaultTimezone()).isEqualTo("UTC");
            assertThat(new TimeProperties("  ").defaultTimezone()).isEqualTo("UTC");
            assertThat(new DefaultUserTimeZoneProvider(new TimeProperties(null)).zoneOf(null))
                .isEqualTo(ZoneId.of("UTC"));
        }

        @Test
        @DisplayName("a typo in the zone id fails the boot rather than silently becoming UTC")
        void anInvalidZoneFailsFast() {
            assertThatThrownBy(() ->
                new DefaultUserTimeZoneProvider(new TimeProperties("America/Torotno")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TROVE_DEFAULT_TIMEZONE")
                .hasMessageContaining("America/Torotno");
        }

        @Test
        @DisplayName("the system-clock constructor is the one the container actually uses")
        void systemClockConstructorTracksRealTime() {
            UserClock systemClock =
                new UserClock(new DefaultUserTimeZoneProvider(new TimeProperties("UTC")));

            assertThat(systemClock.now())
                .isBetween(Instant.now().minusSeconds(10), Instant.now().plusSeconds(10));
            assertThat(systemClock.today(null)).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        }
    }

    @Nested
    @DisplayName("InstantRange")
    class Ranges {

        @Test
        void isHalfOpen() {
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            Instant end = Instant.parse("2026-01-02T00:00:00Z");
            InstantRange range = new InstantRange(start, end);

            assertThat(range.contains(start)).isTrue();
            assertThat(range.contains(end)).isFalse();
            assertThat(range.contains(end.minusNanos(1))).isTrue();
            assertThat(range.contains(start.minusNanos(1))).isFalse();
        }

        @Test
        void refusesAnEmptyOrInvertedRange() {
            Instant instant = Instant.parse("2026-01-01T00:00:00Z");

            assertThatThrownBy(() -> new InstantRange(instant, instant))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InstantRange(instant, instant.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InstantRange(null, instant))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new InstantRange(instant, null))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
