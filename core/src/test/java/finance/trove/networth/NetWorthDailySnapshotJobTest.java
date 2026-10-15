package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.transactions.TransactionFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link NetWorthDailySnapshotJob} — enumerates households, calls the same
 * {@link NetWorthSnapshotService} the button calls with no triggering member, and does not let
 * one household's failure stop the pass over the rest.
 */
@ExtendWith(MockitoExtension.class)
class NetWorthDailySnapshotJobTest {

    @Mock
    private HouseholdRepository households;

    @Mock
    private NetWorthSnapshotService snapshotService;

    private NetWorthDailySnapshotJob job() {
        return new NetWorthDailySnapshotJob(households, snapshotService);
    }

    private static Household household(UUID id) {
        return TransactionFixtures.withId(new Household("H", false), id);
    }

    @Test
    @DisplayName("The lock name is fixed by plan §5 item 6")
    void lockNameIsFixed() {
        assertThat(NetWorthDailySnapshotJob.LOCK_NAME).isEqualTo("networth-daily-snapshot");
    }

    @Test
    @DisplayName("Every household is passed to takeSnapshot with no triggering member (null)")
    void everyHouseholdIsProcessedWithNoTriggeringMember() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(households.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(household(first), household(second)));

        int processed = job().runOnce();

        assertThat(processed).isEqualTo(2);
        verify(snapshotService).takeSnapshot(eq(first), isNull());
        verify(snapshotService).takeSnapshot(eq(second), isNull());
    }

    @Test
    @DisplayName("One household's failure does not stop the pass over the rest")
    void oneHouseholdsFailureDoesNotStopTheRest() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(households.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(household(failing), household(healthy)));
        when(snapshotService.takeSnapshot(eq(failing), isNull()))
            .thenThrow(new RuntimeException("boom"));

        int processed = job().runOnce();

        assertThat(processed)
            .as("only the healthy household counts; the failing one is logged, not fatal")
            .isEqualTo(1);
        verify(snapshotService).takeSnapshot(eq(healthy), isNull());
    }

    @Test
    @DisplayName("No households means no calls and a zero count")
    void noHouseholdsMeansNoCalls() {
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());

        assertThat(job().runOnce()).isZero();
    }
}
