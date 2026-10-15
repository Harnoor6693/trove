package finance.trove.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Computes "today", "this month" and every other date boundary <strong>in the user's own
 * timezone</strong> ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, gap P2-15).
 *
 * <p>This class exists because the source services call {@code LocalDate.now()} with no zone in
 * nine places — the dashboard, budgets, net worth, the snapshot job, rollover, sync and the coach
 * context builder — which resolves against the container clock. The container is UTC by policy
 * (§1.6), so a Vancouver user's 6 pm purchase lands on tomorrow's dashboard and their month rolls
 * over at 4 pm on the 31st. Every one of those call sites moves onto this bean.
 *
 * <p>The container clock stays UTC and every {@code @Scheduled} trigger keeps {@code zone="UTC"}
 * (§5.6). The trigger is a UTC cron; the <em>content</em> each job computes per recipient comes
 * from here. That split is why the weekly digest job runs once at 08:00 UTC and still sends each
 * member a week that ended at their own local midnight.
 *
 * <p>Ranges are half-open ({@link InstantRange}), which is what makes a 23-hour or 25-hour DST
 * day fall out correctly instead of needing a special case.
 */
@Component
public class UserClock {

    /**
     * How much clock drift Trove tolerates before a signature or a TOTP code is rejected
     * (gap P2-15: an RPi with no RTC boots in 1970 until NTP catches up, and every JWT then reads
     * as "not yet valid"). Collapse step 3's JWT parser and TOTP verifier both take their
     * tolerance from here so there is one number, not three.
     */
    public static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(60);

    private final UserTimeZoneProvider zones;
    private final Clock clock;

    @Autowired
    public UserClock(UserTimeZoneProvider zones) {
        this(zones, Clock.systemUTC());
    }

    /** Test seam: a fixed {@link Clock} makes DST assertions deterministic. */
    public UserClock(UserTimeZoneProvider zones, Clock clock) {
        this.zones = zones;
        this.clock = clock;
    }

    /** The zone this user's boundaries are computed in. */
    public ZoneId zoneOf(UUID userId) {
        ZoneId zone = zones.zoneOf(userId);
        return zone == null ? ZoneOffset.UTC : zone;
    }

    /** The current instant. Timezone-free by definition — an instant has no zone. */
    public Instant now() {
        return clock.instant();
    }

    /** Now, as the user would read it off their own wall clock. */
    public ZonedDateTime nowFor(UUID userId) {
        return now().atZone(zoneOf(userId));
    }

    /** The calendar date it is right now where this user is. */
    public LocalDate today(UUID userId) {
        return nowFor(userId).toLocalDate();
    }

    /** The month it is right now where this user is. */
    public YearMonth currentMonth(UUID userId) {
        return YearMonth.from(nowFor(userId));
    }

    /**
     * The first instant of {@code date} for this user.
     *
     * <p>{@code atStartOfDay(zone)} is used rather than {@code atTime(0,0)} because in zones
     * whose DST transition happens at midnight there is no 00:00 on the spring-forward day;
     * {@code atStartOfDay} returns the first instant that does exist.
     */
    public Instant startOfDay(UUID userId, LocalDate date) {
        return date.atStartOfDay(zoneOf(userId)).toInstant();
    }

    /** The first instant of the following day — the exclusive end of {@code date}. */
    public Instant endOfDayExclusive(UUID userId, LocalDate date) {
        return startOfDay(userId, date.plusDays(1));
    }

    /** {@code [start of date, start of the next day)} — 23, 24 or 25 hours long. */
    public InstantRange dayRange(UUID userId, LocalDate date) {
        return new InstantRange(startOfDay(userId, date), endOfDayExclusive(userId, date));
    }

    /** Today, as a half-open range, for this user. */
    public InstantRange todayRange(UUID userId) {
        return dayRange(userId, today(userId));
    }

    /**
     * {@code [first day of month 00:00, first day of the next month 00:00)} in this user's zone.
     * This is the range every budget period, envelope-spend aggregate and monthly report uses.
     */
    public InstantRange monthRange(UUID userId, YearMonth month) {
        ZoneId zone = zoneOf(userId);
        return new InstantRange(
            month.atDay(1).atStartOfDay(zone).toInstant(),
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    /** The month that contains {@code instant} for this user. */
    public YearMonth monthOf(UUID userId, Instant instant) {
        return YearMonth.from(instant.atZone(zoneOf(userId)));
    }

    /** The date that contains {@code instant} for this user. */
    public LocalDate dateOf(UUID userId, Instant instant) {
        return instant.atZone(zoneOf(userId)).toLocalDate();
    }

    /**
     * Whether {@code candidate} is within {@link #CLOCK_SKEW_TOLERANCE} of now — the check a
     * signature or a one-time code applies before rejecting a timestamp as out of window.
     */
    public boolean withinSkewTolerance(Instant candidate) {
        return Duration.between(candidate, now()).abs().compareTo(CLOCK_SKEW_TOLERANCE) <= 0;
    }
}
