package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.common.events.TransactionsIngestedEvent;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.TestBankSource;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wire one reaching wire five: an ingested batch that the listener categorises moves the envelope,
 * and moves it exactly once however many times the same batch is announced
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4 → D1; §9 risk 4).
 *
 * <h2>Why this is not a repeat of {@code EnvelopeSpendServiceIT}</h2>
 * That class proves the aggregate is a rewrite through the PATCH path and through
 * {@code EnvelopeIngestListener}. What is new here is the chain this step builds:
 * {@code TransactionsIngestedEvent} → {@code CategorisationListener} → {@code category_id} →
 * {@code TransactionCategorisedEvent} → {@code EnvelopeSpendListener} → {@code envelope.spent}.
 * The household rule that resolves the merchant is created <strong>after</strong> the rows are in
 * the ledger, so the insert pass cannot have used it: with this step's listener removed the rows
 * stay {@code Uncategorised}, the grocery envelope stays at zero, and every case below fails.
 *
 * <h2>Why {@code EnvelopeIngestListener} is still in the tree</h2>
 * Step 8 shipped two listeners and left step 16 free to drop whichever it superseded. It is kept,
 * and the last case here is the reason: an UPDATED row — a pending transaction that posted, or one
 * whose amount the source corrected — changes what an envelope should say without changing any
 * category, and {@code CategorisationListener} deliberately does not categorise updated ids (the
 * event record's own distinction). Dropping the ingest listener would lose that recomputation
 * entirely. The overlap on NEW rows costs nothing, because the aggregate is rewritten from the
 * ledger rather than incremented — which is the same property risk 4 is about.
 */
class BudgetSeamIT extends BudgetTestSupport {

    @Autowired
    private TestBankSource source;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Session member;
    private String accountId;
    private String periodId;
    private String groceries;
    private String envelopeId;

    @BeforeEach
    void setUp() {
        source.reset();
        member = freshHousehold("budget-seam");
        accountId = createSyncableAccount(member, "Seam bank " + UUID.randomUUID());
        periodId = createPeriodId(member, "2026-09-01");
        groceries = systemCategoryId(member, GROCERIES_SLUG);
        envelopeId = createEnvelope(member, periodId, groceries, "400.00", "CAD")
            .path("id").asString();
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    private static RawTransaction row(String providerId, String merchant, String amount,
                                      LocalDate date) {
        return RawTransaction.of(providerId, "external-1", new BigDecimal(amount), "CAD",
            "Seam row", merchant, date, Instant.now(), TransactionType.DEBIT, false);
    }

    private List<UUID> ingest(List<RawTransaction> rows) {
        source.returning(rows);
        HttpResponse<String> synced = postJson(appUrl("/v1/accounts/" + accountId + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
        List<UUID> ids = new ArrayList<>();
        for (RawTransaction raw : rows) {
            ids.add(jdbcTemplate.queryForObject(
                "SELECT id FROM transaction WHERE account_id = ? AND provider_transaction_id = ?",
                UUID.class, UUID.fromString(accountId), raw.providerTransactionId()));
        }
        return ids;
    }

    private void ruleFor(String merchant, String categoryId) {
        HttpResponse<String> created = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", merchant, "matchType", "EXACT", "categoryId", categoryId,
                "confidence", "0.95"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    private void announce(List<UUID> newIds, List<UUID> updatedIds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            events.publishEvent(new TransactionsIngestedEvent(member.householdId(),
                member.userId(), UUID.fromString(accountId), newIds, updatedIds)));
    }

    private void assertSpentEqualsTheSqlSum() {
        assertThat(storedSpent(envelopeId))
            .as("the stored figure must equal an independently written SQL aggregate over the "
                + "same rows — §9 risk 4's whole assertion")
            .isEqualByComparingTo(sqlSpend(member.householdId(), groceries, "2026-09-01", "CAD"));
    }

    @Test
    @DisplayName("the categorisation the listener writes moves the envelope to the SQL sum")
    void theIngestSeamMovesTheEnvelope() {
        String merchant = "BUDGET SEAM " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("bs-1", merchant, "40.00", LocalDate.of(2026, 9, 3)),
            row("bs-2", merchant, "60.00", LocalDate.of(2026, 9, 4)),
            row("bs-3", merchant, "25.50", LocalDate.of(2026, 9, 5))));

        assertThat(storedSpent(envelopeId))
            .as("no rule existed at insert time, so the rows are Uncategorised and this envelope "
                + "has nothing in it yet")
            .isEqualByComparingTo(BigDecimal.ZERO);

        ruleFor(merchant, groceries);
        announce(ids, List.of());

        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("125.5000"));
        assertSpentEqualsTheSqlSum();
        assertThat(readEnvelope(member, periodId, envelopeId).path("spent").asString())
            .isEqualTo("125.5000");
    }

    @Test
    @DisplayName("risk 4: announcing the same batch three times does not double the envelope")
    void reAnnouncingTheSameBatchCannotDoubleCount() {
        String merchant = "BUDGET SEAM REPEAT " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("bsr-1", merchant, "80.00", LocalDate.of(2026, 9, 6)),
            row("bsr-2", merchant, "20.00", LocalDate.of(2026, 9, 7))));
        ruleFor(merchant, groceries);

        announce(ids, List.of());
        BigDecimal afterFirst = storedSpent(envelopeId);
        announce(ids, List.of());
        announce(ids, List.of());

        assertThat(afterFirst).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(storedSpent(envelopeId))
            .as("an incrementing writer would read 300.0000 after three passes; a rewrite reads "
                + "the sum, every time")
            .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("re-running the whole sync leaves the envelope where it was")
    void aSecondSyncOfTheSameRowsChangesNothing() {
        String merchant = "BUDGET SEAM RESYNC " + UUID.randomUUID();
        ruleFor(merchant, groceries);
        List<RawTransaction> rows = List.of(
            row("bsy-1", merchant, "30.00", LocalDate.of(2026, 9, 8)),
            row("bsy-2", merchant, "45.00", LocalDate.of(2026, 9, 9)));

        ingest(rows);
        BigDecimal afterFirst = storedSpent(envelopeId);
        ingest(rows);
        ingest(rows);

        assertThat(afterFirst).isEqualByComparingTo(new BigDecimal("75.0000"));
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("75.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("a transfer leg the listener categorises is still invisible to the envelope")
    void transferLegsStayExcludedThroughTheNewChain() {
        String merchant = "BUDGET SEAM TRANSFER " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("bst-1", merchant, "50.00", LocalDate.of(2026, 9, 10)),
            row("bst-2", merchant, "500.00", LocalDate.of(2026, 9, 11))));
        jdbcTemplate.update("UPDATE transaction SET transfer_group_id = ? WHERE id = ?",
            UUID.randomUUID(), ids.get(1));

        ruleFor(merchant, groceries);
        announce(ids, List.of());

        assertThat(storedSpent(envelopeId))
            .as("gap P2-14: the exclusion is inherited from findSpendableInRange, so a new "
                + "listener funnelling into the same service cannot go around it")
            .isEqualByComparingTo(new BigDecimal("50.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("an UPDATED-only announcement still moves the envelope — why the step-8 ingest "
        + "listener is kept")
    void updatedRowsAreStillRecomputedByTheStepEightListener() {
        String merchant = "BUDGET SEAM UPDATED " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(row("bsu-1", merchant, "10.00",
            LocalDate.of(2026, 9, 12))));
        ruleFor(merchant, groceries);
        announce(ids, List.of());
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("10.0000"));

        // The source corrected the amount, exactly as a pending row posting would.
        jdbcTemplate.update("UPDATE transaction SET amount = ? WHERE id = ?",
            new BigDecimal("90.0000"), ids.get(0));
        announce(List.of(), ids);

        assertThat(storedSpent(envelopeId))
            .as("CategorisationListener does not categorise updated ids, so only "
                + "EnvelopeIngestListener can have recomputed this — which is precisely why "
                + "step 16 keeps it rather than superseding it")
            .isEqualByComparingTo(new BigDecimal("90.0000"));
        assertSpentEqualsTheSqlSum();
    }
}
