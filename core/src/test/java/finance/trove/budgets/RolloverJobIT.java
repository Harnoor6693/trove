package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.rollover.EnvelopeRolloverJob;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link EnvelopeRolloverJob} against a real database
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.6 item 6; {@code 07-READINESS-GAPS.md} P2-39).
 *
 * <p>Three things are asserted here that only a real database can show: that ShedLock genuinely
 * takes a row named {@code envelope-rollover}; that a second pass inside the same window changes
 * nothing; and that a month missed while the container was down is still outstanding rather than
 * lost. The arithmetic itself is covered by {@code RolloverServiceTest}.
 *
 * <p><strong>Months are relative to the caller's own "today"</strong>, never hard-coded. A period
 * for a fixed month would either be in the future (and never due) or arbitrarily far in the past
 * (and cascade), depending on when the suite happens to run; and the job's whole design is that it
 * decides from the data rather than from the date, so a test that pinned the clock would be
 * testing something else.
 */
class RolloverJobIT extends BudgetTestSupport {

    @Autowired
    private EnvelopeRolloverJob job;

    /** The month before the caller's current one — a month that has provably ended for them. */
    private YearMonth lastMonthFor(Session member) {
        return YearMonth.from(LocalDate.parse(todayForCaller(member))).minusMonths(1);
    }

    private BigDecimal carryInto(Session member, YearMonth month) {
        List<BigDecimal> rows = jdbcTemplate.queryForList(
            "SELECT e.rollover_amount FROM envelope e JOIN budget_period p ON p.id = e.period_id "
                + "WHERE p.household_id = ? AND p.month = ?",
            BigDecimal.class, member.householdId(), month.atDay(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int periodCount(Session member) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM budget_period WHERE household_id = ?", Integer.class,
            member.householdId());
    }

    @Test
    @DisplayName("a finished ROLLOVER month carries its unspent balance into the next one")
    void aFinishedMonthCarriesForward() {
        Session member = freshHousehold("rollover-carry");
        YearMonth lastMonth = lastMonthFor(member);
        String accountId = createManualAccount(member, "Chequing " + UUID.randomUUID());
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, lastMonth.atDay(1).toString(), "5000.00", "CAD",
            "ROLLOVER").path("id").asString();
        String envelopeId =
            createEnvelope(member, periodId, groceries, "400.00", "CAD").path("id").asString();

        // 250 of 400 spent last month, so 150 is unspent.
        String transactionId = createManualTransaction(member, accountId, "250.00",
            lastMonth.atDay(15).toString(), "DEBIT", "GROCER " + UUID.randomUUID())
            .path("id").asString();
        patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", groceries),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(storedSpent(envelopeId))
            .as("the carry is only meaningful because the spend writer exists — finding D1")
            .isEqualByComparingTo(new BigDecimal("250.0000"));

        job.runOnce();

        assertThat(carryInto(member, lastMonth.plusMonths(1)))
            .as("allocated 400 + carried 0 - spent 250")
            .isEqualByComparingTo(new BigDecimal("150.0000"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT allocated FROM envelope e JOIN budget_period p ON p.id = e.period_id "
                + "WHERE p.household_id = ? AND p.month = ?",
            BigDecimal.class, member.householdId(), lastMonth.plusMonths(1).atDay(1)))
            .as("the carried envelope budgets nothing of its own until a member allocates it")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolled_over_at FROM budget_period WHERE id = ?", Instant.class,
            UUID.fromString(periodId)))
            .as("the marker is what makes the daily cron idempotent and catch-up safe")
            .isNotNull();
    }

    @Test
    @DisplayName("a second pass in the same window changes nothing")
    void aSecondPassIsANoOp() {
        Session member = freshHousehold("rollover-idempotent");
        YearMonth lastMonth = lastMonthFor(member);
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, lastMonth.atDay(1).toString(), "5000.00", "CAD",
            "ROLLOVER").path("id").asString();
        createEnvelope(member, periodId, groceries, "300.00", "CAD");

        job.runOnce();
        BigDecimal afterFirst = carryInto(member, lastMonth.plusMonths(1));

        job.runOnce();
        job.runOnce();

        assertThat(afterFirst)
            .as("nothing was spent, so the whole allocation carries")
            .isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(carryInto(member, lastMonth.plusMonths(1)))
            .as("three passes, one carry. An adding writer would read 900 here, which is exactly "
                + "the compounding the pre-collapse job produced month on month")
            .isEqualByComparingTo(new BigDecimal("300.0000"));
        assertThat(periodCount(member))
            .as("and the current month, whose own month has not ended, was not carried on again")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("gap P2-39: a month missed while the container was down is still outstanding")
    void aMissedMonthIsPickedUpLater() {
        Session member = freshHousehold("rollover-catchup");
        // Four months back: the boundary is long past, and the source's cron — which fired once,
        // on the first, and derived its work from LocalDate.now() — would have lost this for ever
        // if the container had been down that minute. Here the marker is still null, so the work
        // is simply still outstanding whenever the job next runs.
        YearMonth missed = lastMonthFor(member).minusMonths(3);
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, missed.atDay(1).toString(), "5000.00", "CAD",
            "ROLLOVER").path("id").asString();
        createEnvelope(member, periodId, groceries, "200.00", "CAD");

        job.runOnce();

        assertThat(carryInto(member, missed.plusMonths(1)))
            .as("months old, and still carried — one pass advances each chain by one month, so a "
                + "household that was missed for four months is whole again after four daily runs "
                + "and loses nothing in the meantime")
            .isEqualByComparingTo(new BigDecimal("200.0000"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolled_over_at FROM budget_period WHERE id = ?", Instant.class,
            UUID.fromString(periodId)))
            .isNotNull();
    }

    @Test
    @DisplayName("a FLEXIBLE period is never carried, however long ago its month ended")
    void onlyRolloverPeriodsAreCarried() {
        Session member = freshHousehold("rollover-flexible");
        YearMonth lastMonth = lastMonthFor(member);
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, lastMonth.atDay(1).toString(), "5000.00", "CAD",
            "FLEXIBLE").path("id").asString();
        createEnvelope(member, periodId, groceries, "300.00", "CAD");

        job.runOnce();

        assertThat(periodCount(member))
            .as("carry-forward is what ROLLOVER means; FLEXIBLE and ZERO_BASED do not carry")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolled_over_at FROM budget_period WHERE id = ?", Instant.class,
            UUID.fromString(periodId)))
            .isNull();
    }

    @Test
    @DisplayName("a month that has not ended yet is left alone")
    void thisMonthIsNotCarriedYet() {
        Session member = freshHousehold("rollover-current");
        YearMonth thisMonth = YearMonth.from(LocalDate.parse(todayForCaller(member)));
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, thisMonth.atDay(1).toString(), "5000.00", "CAD",
            "ROLLOVER").path("id").asString();
        createEnvelope(member, periodId, groceries, "300.00", "CAD");

        job.runOnce();

        assertThat(periodCount(member))
            .as("carrying a month that is still being spent would take the household's remaining "
                + "budget away from it halfway through")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT rolled_over_at FROM budget_period WHERE id = ?", Instant.class,
            UUID.fromString(periodId)))
            .isNull();
    }

    @Test
    @DisplayName("the job really takes a ShedLock row named envelope-rollover")
    void theShedlockRowIsReallyTaken() {
        Session member = freshHousehold("rollover-lock");
        YearMonth lastMonth = lastMonthFor(member);
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriod(member, lastMonth.atDay(1).toString(), "5000.00", "CAD",
            "ROLLOVER").path("id").asString();
        createEnvelope(member, periodId, groceries, "100.00", "CAD");

        // The scheduled entry point, not runOnce(): the lock is on the annotated method, so
        // calling the inner one would prove nothing about it.
        job.rollEnvelopesForward();

        List<Map<String, Object>> lock = jdbcTemplate.queryForList(
            "SELECT name, locked_by FROM shedlock WHERE name = ?",
            EnvelopeRolloverJob.LOCK_NAME);
        assertThat(lock)
            .as("§5.6 item 6 fixes the name at envelope-rollover; ShedLock keys on the name alone, "
                + "so a name shared with another job would make this one silently never run. "
                + "ScheduledJobsTest catches a collision statically; this shows the row is "
                + "genuinely taken at runtime")
            .hasSize(1);
        assertThat(lock.get(0).get("name")).isEqualTo("envelope-rollover");
        assertThat(carryInto(member, lastMonth.plusMonths(1)))
            .as("and the pass under the lock did the work")
            .isEqualByComparingTo(new BigDecimal("100.0000"));
    }
}
