package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The net-worth CRUD and series routes ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.7,
 * §6 "Net worth").
 *
 * <p>Every household in this class is freshly created ({@link NetWorthTestSupport#freshHousehold})
 * rather than the suite-wide bootstrap admin, so a total or a series point can be asserted
 * EXACTLY rather than merely "contains at least" — the bootstrap household accumulates rows from
 * every other IT in the suite and this class never asserts an absolute row count against it.
 */
class NetWorthIT extends NetWorthTestSupport {

    @Test
    @DisplayName("An asset round-trips: create, appears in the list, updates, then disappears "
        + "from the list once deleted")
    void assetCrudRoundTrips() {
        Session admin = freshHousehold("networth-asset-crud");

        JsonNode created = createAsset(admin, "Chequing", "CASH", "500.00", "CAD");
        String assetId = created.path("id").asString();
        assertThat(created.path("currency").asString()).isEqualTo("CAD");
        assertThat(created.path("accountId").isNull()).isTrue();

        List<JsonNode> afterCreate =
            Json.elements(Json.data(get(appUrl("/v1/networth/assets"), admin.accessToken())
                .body()));
        assertThat(afterCreate).extracting(row -> row.path("id").asString()).contains(assetId);

        HttpResponse<String> updated = putJson(appUrl("/v1/networth/assets/" + assetId),
            Json.object("name", "Chequing (renamed)", "assetType", "CASH", "value", "600.00",
                "accountId", null),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(new BigDecimal(Json.data(updated.body()).path("value").asString()))
            .isEqualByComparingTo("600.00");
        assertThat(Json.data(updated.body()).path("currency").asString())
            .as("currency is never writable through an update")
            .isEqualTo("CAD");

        HttpResponse<String> deleted = delete(appUrl("/v1/networth/assets/" + assetId), null,
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(Json.parse(deleted.body()).has("data"))
            .as("EmptySuccess omits data entirely")
            .isFalse();

        List<JsonNode> afterDelete =
            Json.elements(Json.data(get(appUrl("/v1/networth/assets"), admin.accessToken())
                .body()));
        assertThat(afterDelete).extracting(row -> row.path("id").asString())
            .doesNotContain(assetId);
    }

    @Test
    @DisplayName("A liability round-trips the same way, and an omitted interestRate defaults "
        + "to zero")
    void liabilityCrudRoundTrips() {
        Session admin = freshHousehold("networth-liability-crud");

        JsonNode created = createLiability(admin, "Visa", "CREDIT_CARD", "1250.00", "CAD");
        String liabilityId = created.path("id").asString();
        assertThat(new BigDecimal(created.path("interestRate").asString()))
            .isEqualByComparingTo("0");

        HttpResponse<String> updated = putJson(appUrl("/v1/networth/liabilities/" + liabilityId),
            Json.object("name", "Visa", "liabilityType", "CREDIT_CARD", "balance", "900.00",
                "interestRate", "19.99", "accountId", null),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(new BigDecimal(Json.data(updated.body()).path("balance").asString()))
            .isEqualByComparingTo("900.00");
        assertThat(new BigDecimal(Json.data(updated.body()).path("interestRate").asString()))
            .isEqualByComparingTo("19.99");

        HttpResponse<String> deleted =
            delete(appUrl("/v1/networth/liabilities/" + liabilityId), null,
                "Authorization", "Bearer " + admin.accessToken());
        assertThat(deleted.statusCode()).isEqualTo(200);

        List<JsonNode> afterDelete = Json.elements(
            Json.data(get(appUrl("/v1/networth/liabilities"), admin.accessToken()).body()));
        assertThat(afterDelete).extracting(row -> row.path("id").asString())
            .doesNotContain(liabilityId);
    }

    @Test
    @DisplayName("GET /v1/networth/liabilities is genuinely new code (BUD-02) and answers "
        + "the household's liabilities")
    void listLiabilitiesIsServed() {
        Session admin = freshHousehold("networth-liabilities-list");
        JsonNode created = createLiability(admin, "Car loan", "CAR_LOAN", "8000.00", "CAD");

        HttpResponse<String> response =
            get(appUrl("/v1/networth/liabilities"), admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(response.body())))
            .extracting(row -> row.path("id").asString())
            .contains(created.path("id").asString());
    }

    @Test
    @DisplayName("An accountId naming another household's connected account is 400 "
        + "validation-error, never a silently stored dangling reference")
    void foreignAccountIdOnCreateIsRejected() {
        Session ownHousehold = freshHousehold("networth-own-household");
        Session otherHousehold = freshHousehold("networth-other-household");

        HttpResponse<String> account = postJson(appUrl("/v1/accounts"),
            Json.object("sourceKind", "MANUAL", "displayName", "Foreign account",
                "institution", null, "accountType", "OTHER", "currency", "CAD"),
            "Authorization", "Bearer " + otherHousehold.accessToken());
        assertThat(account.statusCode()).isEqualTo(201);
        String foreignAccountId = Json.data(account.body()).path("id").asString();

        // No conformance opt-out: the request body conforms to the schema (a syntactically valid
        // UUID) — "belongs to this household" is a service-layer rule, not something the OpenAPI
        // schema encodes, so this is an ordinary validated call.
        HttpResponse<String> response = postJson(appUrl("/v1/networth/assets"),
            Json.object("name", "Bad link", "assetType", "CASH", "value", "1.00",
                "currency", "CAD", "accountId", foreignAccountId),
            "Authorization", "Bearer " + ownHousehold.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An accountId naming a connected account in the SAME household links "
        + "successfully, and PUT with accountId: null unlinks it")
    void ownAccountIdLinksAndUnlinks() {
        Session admin = freshHousehold("networth-own-account-link");
        HttpResponse<String> account = postJson(appUrl("/v1/accounts"),
            Json.object("sourceKind", "MANUAL", "displayName", "Linked account",
                "institution", null, "accountType", "OTHER", "currency", "CAD"),
            "Authorization", "Bearer " + admin.accessToken());
        String accountId = Json.data(account.body()).path("id").asString();

        HttpResponse<String> created = postJson(appUrl("/v1/networth/assets"),
            Json.object("name", "Linked asset", "assetType", "CASH", "value", "1.00",
                "currency", "CAD", "accountId", accountId),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(created.statusCode()).isEqualTo(201);
        String assetId = Json.data(created.body()).path("id").asString();
        assertThat(Json.data(created.body()).path("accountId").asString()).isEqualTo(accountId);

        HttpResponse<String> unlinked = putJson(appUrl("/v1/networth/assets/" + assetId),
            Json.object("name", "Linked asset", "assetType", "CASH", "value", "1.00",
                "accountId", null),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(Json.data(unlinked.body()).path("accountId").isNull()).isTrue();
    }

    @Test
    @DisplayName("A fresh household with no snapshots gets an empty series and its own "
        + "primaryCurrency — GET never writes")
    void freshHouseholdHasAnEmptySeries() {
        Session admin = freshHousehold("networth-empty-series");

        JsonNode series = getSeries(admin, "ALL");

        assertThat(series.path("primaryCurrency").asString()).isEqualTo("CAD");
        assertThat(Json.elements(series.path("series"))).isEmpty();

        // GET must not have taken a snapshot as a side effect.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM net_worth_snapshot WHERE household_id = ?", Long.class,
            admin.householdId())).isZero();
    }

    @Test
    @DisplayName("An out-of-range value for range is 400, predicted by the OpenAPI document")
    void invalidRangeIsRejected() {
        Session admin = freshHousehold("networth-invalid-range");

        HttpResponse<String> response = withoutRequestValidation(
            "deliberately sending a range value outside the documented enum, to prove the "
                + "server refuses it rather than silently defaulting",
            () -> get(appUrl("/v1/networth/series?range=2Y"), admin.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("POST /v1/networth/snapshot writes a row per currency and GET /v1/networth/"
        + "series then reports it, with netWorth agreeing with assets minus liabilities")
    void snapshotThenSeriesAgree() {
        Session admin = freshHousehold("networth-snapshot-then-series");
        createAsset(admin, "Chequing", "CASH", "1000.00", "CAD");
        createLiability(admin, "Visa", "CREDIT_CARD", "250.00", "CAD");

        List<JsonNode> snapshotRows = Json.elements(takeSnapshot(admin));
        assertThat(snapshotRows).hasSize(1);
        JsonNode row = snapshotRows.get(0);
        assertThat(new BigDecimal(row.path("totalAssets").asString()))
            .isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(row.path("totalLiabilities").asString()))
            .isEqualByComparingTo("250.00");
        assertThat(new BigDecimal(row.path("netWorth").asString())).isEqualByComparingTo("750.00");
        assertThat(row.path("currency").asString()).isEqualTo("CAD");
        assertThat(row.path("createdBy").asString()).isEqualTo(admin.userId().toString());

        JsonNode series = getSeries(admin, "1Y");
        List<JsonNode> seriesEntries = Json.elements(series.path("series"));
        assertThat(seriesEntries).hasSize(1);
        List<JsonNode> points = Json.elements(seriesEntries.get(0).path("points"));
        assertThat(points).hasSize(1);
        assertThat(new BigDecimal(points.get(0).path("netWorth").asString()))
            .isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("Pressing the button twice on the same local date UPDATES the row, never "
        + "duplicates it or answers a conflict")
    void snapshotButtonIsIdempotentOnTheSameDate() {
        Session admin = freshHousehold("networth-snapshot-idempotent");
        createAsset(admin, "Chequing", "CASH", "100.00", "CAD");

        takeSnapshot(admin);
        createAsset(admin, "Savings", "CASH", "50.00", "CAD");
        JsonNode second = Json.elements(takeSnapshot(admin)).get(0);

        assertThat(new BigDecimal(second.path("totalAssets").asString()))
            .as("the SECOND run's values win")
            .isEqualByComparingTo("150.00");
        Long rowCount = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM net_worth_snapshot WHERE household_id = ? AND currency = 'CAD'",
            Long.class, admin.householdId());
        assertThat(rowCount)
            .as("one row per currency per date — the second press updated it, did not add one")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Every net-worth route requires a session")
    void routesRequireASession() {
        assertThat(get(appUrl("/v1/networth/assets")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/networth/liabilities")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/networth/series")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/networth/snapshot"), null).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/networth/assets"),
            Json.object("name", "x", "assetType", "CASH", "value", "1.00")).statusCode())
            .isEqualTo(401);
        assertThat(putJson(appUrl("/v1/networth/assets/" + UUID.randomUUID()),
            Json.object("name", "x", "assetType", "CASH", "value", "1.00")).statusCode())
            .isEqualTo(401);
        assertThat(delete(appUrl("/v1/networth/assets/" + UUID.randomUUID()), null)
            .statusCode()).isEqualTo(401);
    }
}
