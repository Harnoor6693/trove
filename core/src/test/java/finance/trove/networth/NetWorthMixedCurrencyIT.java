package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * F2, over the real HTTP surface: a household holding a CAD asset and a USD liability gets TWO
 * snapshot rows and TWO series, never one blended number
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 "Net worth", §9 risk 5).
 *
 * <p>Scoped to net worth only — the broader {@code MixedCurrencyIT} risk 5 names (spanning
 * dashboard, digest, export and subscriptions) belongs to a later step and is explicitly NOT
 * built here.
 */
class NetWorthMixedCurrencyIT extends NetWorthTestSupport {

    @Test
    @DisplayName("A CAD asset and a USD liability produce two snapshot rows and two series, "
        + "and no field anywhere is a blended total")
    void mixedCurrencyHouseholdGetsTwoOfEverything() {
        Session admin = freshHousehold("networth-mixed-currency");
        createAsset(admin, "Chequing", "CASH", "1000.00", "CAD");
        createLiability(admin, "US student loan", "STUDENT_LOAN", "300.00", "USD");

        List<JsonNode> snapshotRows = Json.elements(takeSnapshot(admin));
        assertThat(snapshotRows).hasSize(2);

        Map<String, JsonNode> byCurrency = new java.util.HashMap<>();
        for (JsonNode row : snapshotRows) {
            byCurrency.put(row.path("currency").asString(), row);
        }
        assertThat(byCurrency).containsOnlyKeys("CAD", "USD");

        JsonNode cadRow = byCurrency.get("CAD");
        assertThat(new BigDecimal(cadRow.path("totalAssets").asString()))
            .isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(cadRow.path("totalLiabilities").asString()))
            .as("the USD liability must not leak into the CAD row")
            .isEqualByComparingTo("0");
        assertThat(new BigDecimal(cadRow.path("netWorth").asString()))
            .isEqualByComparingTo("1000.00");

        JsonNode usdRow = byCurrency.get("USD");
        assertThat(new BigDecimal(usdRow.path("totalAssets").asString()))
            .as("the CAD asset must not leak into the USD row")
            .isEqualByComparingTo("0");
        assertThat(new BigDecimal(usdRow.path("totalLiabilities").asString()))
            .isEqualByComparingTo("300.00");
        assertThat(new BigDecimal(usdRow.path("netWorth").asString()))
            .isEqualByComparingTo("-300.00");

        JsonNode series = getSeries(admin, "1Y");
        List<JsonNode> seriesEntries = Json.elements(series.path("series"));
        assertThat(seriesEntries)
            .as("two currencies present -> two series, never one blended series")
            .hasSize(2);
        for (JsonNode entry : seriesEntries) {
            assertThat(Json.elements(entry.path("points"))).hasSize(1);
        }

        Long distinctSnapshotDatesPerCurrency = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM net_worth_snapshot WHERE household_id = ?", Long.class,
            admin.householdId());
        assertThat(distinctSnapshotDatesPerCurrency)
            .as("exactly two rows in the database: one per currency, on the same date")
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("Re-taking the snapshot after adding a second CAD asset updates only the "
        + "CAD row; the USD row is untouched")
    void updatingOneCurrencyLeavesTheOtherAlone() {
        Session admin = freshHousehold("networth-mixed-currency-update");
        createAsset(admin, "Chequing", "CASH", "500.00", "CAD");
        createLiability(admin, "US student loan", "STUDENT_LOAN", "300.00", "USD");
        takeSnapshot(admin);

        createAsset(admin, "Savings", "CASH", "200.00", "CAD");
        List<JsonNode> secondPass = Json.elements(takeSnapshot(admin));

        Map<String, JsonNode> byCurrency = new java.util.HashMap<>();
        for (JsonNode row : secondPass) {
            byCurrency.put(row.path("currency").asString(), row);
        }
        assertThat(new BigDecimal(byCurrency.get("CAD").path("totalAssets").asString()))
            .isEqualByComparingTo("700.00");
        assertThat(new BigDecimal(byCurrency.get("USD").path("totalLiabilities").asString()))
            .as("the USD row is unaffected by a CAD-only change")
            .isEqualByComparingTo("300.00");
    }
}
