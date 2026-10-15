package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link NetWorthDailySnapshotJob} over a real database ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §5 item 6): the lock is really taken, and a second run in the same window changes nothing.
 *
 * <p>Calling {@link NetWorthDailySnapshotJob#takeSnapshots()} directly rather than waiting for its
 * 23:30 UTC cron is deliberate ({@code SchedulingIT}'s own reasoning): the call still goes through
 * the ShedLock proxy, since the bean is autowired, so the lock is exercised exactly as the
 * scheduler would exercise it.
 *
 * <p>Uses a freshly created household ({@link NetWorthTestSupport#freshHousehold}) so the second
 * pass's totals can be asserted exactly — a household this job also touches through every other
 * IT's fixtures would make an exact total meaningless.
 */
class NetWorthSnapshotJobIT extends NetWorthTestSupport {

    @Autowired
    private NetWorthDailySnapshotJob job;

    private List<Map<String, Object>> lock(String name) {
        return jdbcTemplate.queryForList(
            "SELECT name, lock_until, locked_at, locked_by FROM shedlock WHERE name = ?", name);
    }

    @Test
    @DisplayName("Running the job really takes its ShedLock row")
    void theLockIsAcquired() {
        job.takeSnapshots();

        assertThat(lock(NetWorthDailySnapshotJob.LOCK_NAME))
            .as("without a lock a rolling restart runs the daily sweep twice")
            .hasSize(1)
            .allSatisfy(row -> {
                assertThat(row.get("locked_by")).isNotNull();
                assertThat(row.get("locked_at")).isNotNull();
                assertThat(row.get("lock_until")).isNotNull();
            });
    }

    @Test
    @DisplayName("A second run in the same window changes nothing: one row per currency, "
        + "the latest values, never a duplicate and never a conflict")
    void secondRunInTheSameWindowChangesNothing() {
        Session admin = freshHousehold("networth-job-idempotent");
        createAsset(admin, "Chequing", "CASH", "400.00", "CAD");

        job.runOnce();
        long firstCount = countRows(admin);
        assertGeneratedNetWorthAgrees(admin, "the INSERT");

        createAsset(admin, "Savings", "CASH", "100.00", "CAD");
        job.runOnce();
        long secondCount = countRows(admin);

        assertThat(firstCount).isEqualTo(1L);
        assertThat(secondCount)
            .as("the same row is updated, not a second one added")
            .isEqualTo(1L);
        BigDecimal totalAssets = jdbcTemplate.queryForObject(
            "SELECT total_assets FROM net_worth_snapshot WHERE household_id = ? "
                + "AND currency = 'CAD'", BigDecimal.class, admin.householdId());
        assertThat(totalAssets)
            .as("the second run's values win")
            .isEqualByComparingTo("500.00");
        assertGeneratedNetWorthAgrees(admin, "the subsequent UPDATE");
    }

    /**
     * Reads {@code net_worth_snapshot}'s own {@code total_assets}, {@code total_liabilities} and
     * GENERATED {@code net_worth} columns straight from Postgres — never through the entity or the
     * API, both of which fall back to a Java-side {@code totalAssets.subtract(totalLiabilities)}
     * recomputation because Hibernate does not re-fetch a GENERATED column in the same persistence
     * context — and asserts Postgres's own {@code STORED} expression actually recomputed
     * {@code total_assets - total_liabilities}, on both the initial INSERT and (called again
     * below) the subsequent UPDATE the re-run path performs.
     */
    private void assertGeneratedNetWorthAgrees(Session household, String afterWhat) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT total_assets, total_liabilities, net_worth FROM net_worth_snapshot "
                + "WHERE household_id = ? AND currency = 'CAD'", household.householdId());
        BigDecimal totalAssets = (BigDecimal) row.get("total_assets");
        BigDecimal totalLiabilities = (BigDecimal) row.get("total_liabilities");
        BigDecimal netWorth = (BigDecimal) row.get("net_worth");
        assertThat(netWorth)
            .as("Postgres's own GENERATED net_worth column after %s must equal "
                + "total_assets - total_liabilities, not just the API's Java-side fallback",
                afterWhat)
            .isEqualByComparingTo(totalAssets.subtract(totalLiabilities));
    }

    @Test
    @DisplayName("A household with neither assets nor liabilities gets no row, "
        + "not a zero row")
    void emptyHouseholdGetsNoRow() {
        Session admin = freshHousehold("networth-job-empty");

        job.runOnce();

        assertThat(countRows(admin)).isZero();
    }

    private long countRows(Session household) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM net_worth_snapshot WHERE household_id = ?", Long.class,
            household.householdId());
    }
}
