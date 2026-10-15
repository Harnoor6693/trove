package finance.trove.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
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
 * The inbox finally being filled by the ledger — collapse step 16 wire three
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3; §7 gap row 45; §9 risk 13).
 *
 * <h2>What this step actually contributed here, stated plainly</h2>
 * The three notification producers themselves are <strong>step 11's</strong>:
 * {@code NotificationEventListener} was already in the tree at this step's base, with its dedup
 * keys, its per-member threshold decision and its recorded "a successful sync notifies nobody".
 * Step 16 wrote no second listener for them, because writing one would have put two producers on
 * one event and broken the dedup reasoning that step recorded. What was missing was the FIRST half
 * of the chain: nothing published a categorised event for ingested rows, so no ingest could ever
 * move an envelope past a threshold and no budget alert could ever be raised by a sync. That is
 * what the cases below drive, and it is why the household rule is created after the rows land —
 * with {@code CategorisationListener} removed the rows stay uncategorised, the envelope stays at
 * zero, no crossing is published, and the budget cases fail.
 *
 * <p>Gap row 45 records that {@code in_app_notification} has had five read endpoints over a table
 * nothing filled. This is the test that stops that being true.
 */
class NotificationSeamIT extends BudgetTestSupport {

    /** {@code HouseholdAlertThresholds.DEFAULT_FRACTION} and the shipped preference: 90 %. */
    private static final String ALLOCATED = "100.00";

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
        member = freshHousehold("notif-seam");
        accountId = createSyncableAccount(member, "Seam bank " + UUID.randomUUID());
        periodId = createPeriodId(member, "2026-09-01");
        groceries = systemCategoryId(member, GROCERIES_SLUG);
        envelopeId = createEnvelope(member, periodId, groceries, ALLOCATED, "CAD")
            .path("id").asString();
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    // ------------------------------------------------------------------ fixtures

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

    private void announce(List<UUID> newIds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            events.publishEvent(new TransactionsIngestedEvent(member.householdId(),
                member.userId(), UUID.fromString(accountId), newIds, List.of())));
    }

    private int logRows(String type, String dedupKey) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND type = ? "
                + "AND dedup_key = ?", Integer.class, member.userId(), type, dedupKey);
    }

    private int inboxRows(String type) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM in_app_notification WHERE user_id = ? AND type = ?",
            Integer.class, member.userId(), type);
    }

    // ------------------------------------------------------------------ budget threshold

    @Test
    @DisplayName("a sync that pushes an envelope past the threshold writes exactly one inbox row")
    void aCrossingDrivenByIngestionNotifiesOnce() {
        String merchant = "NOTIF SEAM " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("ns-1", merchant, "60.00", LocalDate.of(2026, 9, 3)),
            row("ns-2", merchant, "35.00", LocalDate.of(2026, 9, 4))));

        assertThat(inboxRows("BUDGET_THRESHOLD"))
            .as("nothing has crossed yet: the rows are uncategorised and the envelope is at zero")
            .isZero();

        ruleFor(merchant, groceries);
        announce(ids);

        assertThat(storedSpent(envelopeId))
            .as("95 of 100 — past the shipped 90 % threshold")
            .isEqualByComparingTo(new BigDecimal("95.0000"));
        assertThat(inboxRows("BUDGET_THRESHOLD")).isEqualTo(1);
        assertThat(logRows("BUDGET_THRESHOLD", envelopeId + ":2026-09")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same envelope crossing again in the same period writes nothing more")
    void aSecondCrossingInTheSamePeriodIsSuppressed() {
        String merchant = "NOTIF SEAM REPEAT " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("nsr-1", merchant, "95.00", LocalDate.of(2026, 9, 5))));
        ruleFor(merchant, groceries);
        announce(ids);
        assertThat(inboxRows("BUDGET_THRESHOLD")).isEqualTo(1);

        // Drop the spend back under the threshold and then push it over again, so that
        // EnvelopeSpendService really publishes a SECOND crossing (previous below, recomputed at
        // or above). Without this the dedup index would never be exercised: one crossing is only
        // ever published once per direction.
        jdbcTemplate.update("UPDATE transaction SET amount = ? WHERE id = ?",
            new BigDecimal("10.0000"), ids.get(0));
        announce(ids);
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("10.0000"));
        jdbcTemplate.update("UPDATE transaction SET amount = ? WHERE id = ?",
            new BigDecimal("99.0000"), ids.get(0));
        announce(ids);
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("99.0000"));

        assertThat(inboxRows("BUDGET_THRESHOLD"))
            .as("one alert per envelope, per member, per budget period — enforced by V32's "
                + "partial unique index and not by a check-then-act the listener could lose")
            .isEqualTo(1);
        assertThat(logRows("BUDGET_THRESHOLD", envelopeId + ":2026-09")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ sync failure

    @Test
    @DisplayName("a failed sync writes one SYNC_FAILED row for the member who owns the connection")
    void aFailedSyncNotifiesOnce() {
        source.failingWith("The saved credential was rejected by the institution.");

        HttpResponse<String> response = postJson(appUrl("/v1/accounts/" + accountId + "/sync"),
            null, "Authorization", "Bearer " + member.accessToken());

        assertThat(Json.data(response.body()).path("status").asString()).isEqualTo("FAILED");
        assertThat(inboxRows("SYNC_FAILED")).isEqualTo(1);
        assertThat(inboxRows("BUDGET_THRESHOLD"))
            .as("a failure moves no money, so it crosses no budget")
            .isZero();
    }

    // ------------------------------------------------------------------ price change

    @Test
    @DisplayName("a price change writes one SUBSCRIPTION_PRICE_CHANGE row for the member it names")
    void aPriceChangeNotifiesOnce() {
        UUID subscriptionId = UUID.randomUUID();
        SubscriptionPriceChangedEvent event = new SubscriptionPriceChangedEvent(
            member.householdId(), member.userId(), subscriptionId, "A STREAMING SERVICE",
            Money.of("16.49", CurrencyCode.CAD), Money.of("20.99", CurrencyCode.CAD));

        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> events.publishEvent(event));

        assertThat(inboxRows("SUBSCRIPTION_PRICE_CHANGE")).isEqualTo(1);
        assertThat(logRows("SUBSCRIPTION_PRICE_CHANGE", subscriptionId + ":20.99")).isEqualTo(1);

        // The same scan seeing the same new price again is the same key, so it claims nothing.
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> events.publishEvent(event));

        assertThat(inboxRows("SUBSCRIPTION_PRICE_CHANGE")).isEqualTo(1);
    }
}
