package finance.trove.common.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one place Trove asks "what time is it" for a value it is about to store.
 *
 * <p>Postgres {@code timestamptz} keeps microseconds; Linux JVMs read the clock in nanoseconds
 * (macOS reads microseconds, which is why this went unseen locally). A stamp kept at full
 * precision is therefore answered one way by the request that wrote it and another by every read
 * after it, so the API reported two different values for one event. Stamping at microsecond
 * precision from the start makes the in-memory value and the stored value the same value.
 */
public final class Instants {

    /** A UTC clock that never reports finer than the database stores. */
    public static final Clock CLOCK = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));

    private Instants() {
    }

    /** The current instant, truncated to microseconds. */
    public static Instant now() {
        return CLOCK.instant();
    }

    /** {@code instant} truncated to microseconds, for a clock that is not {@link #CLOCK}. */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
