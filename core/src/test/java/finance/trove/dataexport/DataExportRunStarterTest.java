package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-per-day cap as a <em>decision</em> rather than an observation.
 *
 * <p>Every assertion here is about the thing that made the cap ineffective before it was fixed:
 * the count and the INSERT were two separate, uncommitted-in-between interactions with nothing
 * serialising them, so N simultaneous requests from one member all read zero and all started a
 * run on the shared export executor. Reinstating that defect — dropping the lock, moving the
 * count after the save, or losing the {@code REQUIRES_NEW} that makes the write commit on its own
 * — fails one of these tests.
 */
class DataExportRunStarterTest {

    private static final Instant MORNING_UTC = Instant.parse("2026-09-06T05:00:00Z");

    private final DataExportRunRepository runs = mock(DataExportRunRepository.class);
    private final HouseholdRepository households = mock(HouseholdRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private DataExportRunStarter starterIn(ZoneId zone) {
        UserTimeZoneProvider zones = id -> zone;
        return new DataExportRunStarter(runs, households,
            new DataExportProperties(null, null, null),
            new UserClock(zones, Clock.fixed(MORNING_UTC, ZoneOffset.UTC)));
    }

    private void householdExists() {
        when(households.findByIdForUpdate(householdId))
            .thenReturn(Optional.of(mock(Household.class)));
        when(runs.save(any(DataExportRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("The household row is locked BEFORE the runs are counted and before the insert")
    void theLockIsTakenFirst() {
        householdExists();
        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(0L);

        starterIn(ZoneId.of("America/Toronto")).start(userId, householdId, MORNING_UTC);

        InOrder order = inOrder(households, runs);
        // Without this ordering the cap is a check-then-act: two simultaneous POSTs from one
        // member both read zero and both start a run on the shared export executor.
        order.verify(households).findByIdForUpdate(householdId);
        order.verify(runs).countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any());
        order.verify(runs).save(any(DataExportRun.class));
    }

    @Test
    @DisplayName("The counting and the insert commit on their own, in REQUIRES_NEW")
    void theWriteCommitsOnItsOwn() throws NoSuchMethodException {
        Method start = DataExportRunStarter.class
            .getMethod("start", UUID.class, UUID.class, Instant.class);
        Transactional annotation = start.getAnnotation(Transactional.class);

        assertThat(annotation)
            .as("the caller is deliberately NOT transactional, so this write has to open and "
                + "commit its own transaction — and the lock has to be held for exactly that long")
            .isNotNull();
        assertThat(annotation.propagation())
            .as("REQUIRED would silently run with no transaction and no lock when the caller has "
                + "none, which is the defect wearing an annotation")
            .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("A second run the same day is refused and nothing is inserted")
    void theSecondRunOfTheDayIsRefused() {
        householdExists();
        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(1L);

        assertThatThrownBy(() ->
            starterIn(ZoneId.of("America/Toronto")).start(userId, householdId, MORNING_UTC))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already asked")
            .hasMessageContaining("2026-09-07T04:00:00Z");

        verify(runs, never()).save(any(DataExportRun.class));
    }

    @Test
    @DisplayName("The cap is counted on the caller's own local day, not the container's")
    void theCapIsCountedInTheCallersTimezone() {
        householdExists();
        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(0L);

        starterIn(ZoneId.of("America/Toronto")).start(userId, householdId, MORNING_UTC);
        verify(runs).countByUserIdAndCreatedAtGreaterThanEqual(userId,
            Instant.parse("2026-09-06T04:00:00Z"));

        starterIn(ZoneId.of("America/Vancouver")).start(userId, householdId, MORNING_UTC);
        verify(runs).countByUserIdAndCreatedAtGreaterThanEqual(userId,
            Instant.parse("2026-09-05T07:00:00Z"));
    }

    @Test
    @DisplayName("An unknown household is 404 and nothing is counted or written")
    void anUnknownHouseholdIsNotFound() {
        when(households.findByIdForUpdate(householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            starterIn(ZoneId.of("America/Toronto")).start(userId, householdId, MORNING_UTC))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(runs, never()).countByUserIdAndCreatedAtGreaterThanEqual(any(), any());
        verify(runs, never()).save(any(DataExportRun.class));
    }
}
