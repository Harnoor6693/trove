package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Transfer pairing through the database and the job
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-14).
 *
 * <p>{@code TransferPairingServiceTest} proves the rule; this proves the plumbing around it — the
 * household-scoped query, the saved column, the second run being a no-op, and the manual
 * mark/unlink through {@code PATCH}. The dates are always "today"-relative, because the job's own
 * window is relative to now and a fixed date would make the suite fail in a month.
 */
class TransferPairingIT extends LedgerSupport {

    @Autowired
    private TransferPairingJob job;

    private Session admin;
    private String chequing;
    private String creditCard;

    @BeforeEach
    void setUpTwoAccounts() {
        admin = loginBootstrapAdmin();
        chequing = createManualAccount(admin, "Chequing " + UUID.randomUUID());
        creditCard = createManualAccount(admin, "Card " + UUID.randomUUID());
    }

    @Test
    @DisplayName("The job links the two legs of one movement and a second run adds nothing")
    void bothLegsGetOneGroupAndTheJobIsIdempotent() {
        String today = todayForCaller(admin);
        String out = createManualTransaction(admin, chequing, "250.00", today, "DEBIT",
            "Card payment " + UUID.randomUUID()).path("id").asString();
        String in = createManualTransaction(admin, creditCard, "250.00", today, "CREDIT",
            "Payment received " + UUID.randomUUID()).path("id").asString();

        job.runOnce();

        String group = getTransaction(admin, out).path("transferGroupId").asString();
        assertThat(group).isNotBlank();
        assertThat(getTransaction(admin, in).path("transferGroupId").asString()).isEqualTo(group);

        // The job only ever loads legs whose transfer_group_id is null, so a second pass has
        // nothing to work with — which is what makes it safe to run hourly for ever.
        job.runOnce();
        assertThat(getTransaction(admin, out).path("transferGroupId").asString()).isEqualTo(group);
        assertThat(getTransaction(admin, in).path("transferGroupId").asString()).isEqualTo(group);
    }

    @Test
    @DisplayName("Legs of two different households are never paired")
    void householdsAreNeverCrossed() {
        String today = todayForCaller(admin);
        String amount = "1337.11";
        String ours = createManualTransaction(admin, chequing, amount, today, "DEBIT",
            "Cross-household probe " + UUID.randomUUID()).path("id").asString();

        UUID otherHousehold = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES ('Pairing Household B', "
                + "false) RETURNING id", UUID.class);
        UUID theirAccount = jdbcTemplate.queryForObject(
            "INSERT INTO connected_account (household_id, source_kind, display_name, "
                + "account_type, currency) VALUES (?, 'MANUAL', 'Theirs', 'OTHER', 'CAD') "
                + "RETURNING id", UUID.class, otherHousehold);
        UUID theirLeg = jdbcTemplate.queryForObject(
            "INSERT INTO transaction (household_id, account_id, amount, currency, type, date, "
                + "manual) VALUES (?, ?, ?::numeric, 'CAD', 'CREDIT', CURRENT_DATE, true) "
                + "RETURNING id", UUID.class, otherHousehold, theirAccount, amount);

        job.runOnce();

        assertThat(getTransaction(admin, ours).path("transferGroupId").isNull())
            .as("one household's ledger appearing inside another's aggregate is the worst outcome "
                + "this product has; the query is scoped and the rule refuses as well")
            .isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT transfer_group_id FROM transaction WHERE id = ?", UUID.class, theirLeg))
            .isNull();
    }

    @Test
    @DisplayName("A refund on the same account is not a transfer")
    void sameAccountOppositesAreNotPaired() {
        String today = todayForCaller(admin);
        String purchase = createManualTransaction(admin, chequing, "77.77", today, "DEBIT",
            "Refunded purchase " + UUID.randomUUID()).path("id").asString();
        String refund = createManualTransaction(admin, chequing, "77.77", today, "CREDIT",
            "The refund " + UUID.randomUUID()).path("id").asString();

        job.runOnce();

        assertThat(getTransaction(admin, purchase).path("transferGroupId").isNull()).isTrue();
        assertThat(getTransaction(admin, refund).path("transferGroupId").isNull()).isTrue();
    }

    @Test
    @DisplayName("Legs in different currencies are not paired — there is no exchange rate here")
    void differentCurrenciesAreNotPaired() {
        String today = todayForCaller(admin);
        String americanAccount = createAccount(admin, MANUAL, "US " + UUID.randomUUID(), "OTHER",
            "USD");
        String canadian = createManualTransaction(admin, chequing, "99.01", today, "DEBIT",
            "CAD leg " + UUID.randomUUID()).path("id").asString();
        String american = createManualTransaction(admin, americanAccount, "99.01", today, "CREDIT",
            "USD leg " + UUID.randomUUID()).path("id").asString();

        job.runOnce();

        assertThat(getTransaction(admin, canadian).path("transferGroupId").isNull()).isTrue();
        assertThat(getTransaction(admin, american).path("transferGroupId").isNull()).isTrue();
    }

    @Test
    @DisplayName("A member can mark a transfer by hand, and unlink it with an explicit null")
    void aMemberCanMarkAndUnlinkByHand() {
        String today = todayForCaller(admin);
        String left = createManualTransaction(admin, chequing, "12.01", today, "DEBIT",
            "Manual left " + UUID.randomUUID()).path("id").asString();
        String right = createManualTransaction(admin, creditCard, "43.21", today, "CREDIT",
            "Manual right " + UUID.randomUUID()).path("id").asString();
        String group = UUID.randomUUID().toString();
        String bearer = "Bearer " + admin.accessToken();

        assertThat(patchJson(appUrl("/v1/transactions/" + left),
            Json.object("transferGroupId", group), "Authorization", bearer).statusCode())
            .isEqualTo(200);
        assertThat(patchJson(appUrl("/v1/transactions/" + right),
            Json.object("transferGroupId", group), "Authorization", bearer).statusCode())
            .isEqualTo(200);
        assertThat(getTransaction(admin, left).path("transferGroupId").asString())
            .isEqualTo(group);

        assertThat(patchJson(appUrl("/v1/transactions/" + left),
            Json.object("transferGroupId", null), "Authorization", bearer).statusCode())
            .isEqualTo(200);
        assertThat(getTransaction(admin, left).path("transferGroupId").isNull())
            .as("an explicit null unlinks; a body that omits the field would have left it alone")
            .isTrue();
        assertThat(getTransaction(admin, right).path("transferGroupId").asString())
            .as("unlinking one leg does not touch the other")
            .isEqualTo(group);
    }

    @Test
    @DisplayName("A paired leg is excluded from the dashboard's spending and income")
    void pairedLegsAreExcludedFromAggregates() {
        String today = todayForCaller(admin);
        // Drain first. The suite shares one household, so an earlier class may have left legs the
        // job would pair on this run; taking the baseline after that pass is what keeps the
        // difference below attributable to the two legs this test creates.
        job.runOnce();
        JsonNode before = dashboard();
        createManualTransaction(admin, chequing, "500.00", today, "DEBIT",
            "Transfer out " + UUID.randomUUID());
        createManualTransaction(admin, creditCard, "500.00", today, "CREDIT",
            "Transfer in " + UUID.randomUUID());
        JsonNode unpaired = dashboard();

        assertThat(new java.math.BigDecimal(unpaired.path("spentToday").asString()))
            .as("until they are linked, the two legs look like ordinary spending and income")
            .isEqualByComparingTo(
                new java.math.BigDecimal(before.path("spentToday").asString()).add(
                    new java.math.BigDecimal("500.00")));

        job.runOnce();
        JsonNode paired = dashboard();

        assertThat(new java.math.BigDecimal(paired.path("spentToday").asString()))
            .as("paying a credit card from chequing is neither spending nor income (P2-14)")
            .isEqualByComparingTo(new java.math.BigDecimal(before.path("spentToday").asString()));
        assertThat(new java.math.BigDecimal(paired.path("incomeThisMonth").asString()))
            .isEqualByComparingTo(
                new java.math.BigDecimal(before.path("incomeThisMonth").asString()));
    }

    private JsonNode dashboard() {
        return Json.data(get(appUrl("/v1/dashboard/today"), admin.accessToken()).body());
    }
}
