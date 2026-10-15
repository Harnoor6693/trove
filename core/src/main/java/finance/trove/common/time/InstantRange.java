package finance.trove.common.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A half-open instant interval, {@code [start, end)} — the shape every date-bounded query in
 * Trove takes.
 *
 * <p>Half-open is not a style choice. A day in {@code America/Toronto} is 23 hours long on the
 * spring-forward Sunday and 25 on the fall-back Sunday, and a month boundary lands at 04:00Z for
 * part of the year and 05:00Z for the rest. An inclusive end would need a "last representable
 * instant", and every such constant is either wrong by a nanosecond or double-counts the
 * transaction that lands exactly on midnight.
 *
 * @param start inclusive lower bound
 * @param end   exclusive upper bound; always after {@code start}
 */
public record InstantRange(Instant start, Instant end) {

    public InstantRange {
        Objects.requireNonNull(start, "start cannot be null");
        Objects.requireNonNull(end, "end cannot be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end (" + end + ") must be after start (" + start + ")");
        }
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }

    /** Wall-clock length; 23 h or 25 h for a day that crosses a DST transition. */
    public Duration duration() {
        return Duration.between(start, end);
    }
}
