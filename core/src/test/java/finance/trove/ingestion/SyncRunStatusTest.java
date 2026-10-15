package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.events.SyncCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one place the column's vocabulary and the event's are reconciled
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V20, §6).
 */
class SyncRunStatusTest {

    @Test
    @DisplayName("FAILED maps to FAILURE — neither name is changed to suit the other")
    void theColumnAndTheEventAreMappedNotRenamed() {
        assertThat(SyncRunStatus.SUCCESS.toOutcome())
            .isEqualTo(SyncCompletedEvent.Outcome.SUCCESS);
        assertThat(SyncRunStatus.PARTIAL_FAILURE.toOutcome())
            .isEqualTo(SyncCompletedEvent.Outcome.PARTIAL_FAILURE);
        assertThat(SyncRunStatus.FAILED.toOutcome())
            .as("the event is a contract other collapse steps consume and the column is what an "
                + "operator reads in SQL; the mapping lives here so it cannot be done "
                + "inconsistently in two places")
            .isEqualTo(SyncCompletedEvent.Outcome.FAILURE);
    }

    @Test
    @DisplayName("A run that is still RUNNING has no outcome to publish")
    void aRunningRunHasNoOutcome() {
        assertThatThrownBy(SyncRunStatus.RUNNING::toOutcome)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("The four names are exactly V20's CHECK constraint")
    void theNamesMatchTheCheckConstraint() {
        assertThat(SyncRunStatus.values())
            .extracting(Enum::name)
            .containsExactly("RUNNING", "SUCCESS", "PARTIAL_FAILURE", "FAILED");
        assertThat("PARTIAL_FAILURE".length())
            .as("the pre-collapse column was VARCHAR(10) and the code wrote this fifteen-"
                + "character value into it; V20 is VARCHAR(20)")
            .isGreaterThan(10);
    }
}
