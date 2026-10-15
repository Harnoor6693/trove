package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.BaseIT;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Standing guards over the shape of the schema itself
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risks 5 and 6).
 *
 * <p>These are not assertions about this step's five tables. They are assertions about
 * <strong>every</strong> table, made through {@code information_schema}, so that a later step
 * which adds a money column at the wrong precision, a currency column with a default, or a
 * tenancy column with no foreign key fails here rather than in production a year later:
 *
 * <ul>
 *   <li><strong>Money is {@code NUMERIC(19,4)}.</strong> A narrower scale cannot hold a value the
 *       application rounds to a currency's minor units; a wider one invites a third decimal that
 *       no statement can be reconciled against.</li>
 *   <li><strong>No currency column has a DEFAULT.</strong> This is finding F4 as a schema rule:
 *       the pre-collapse {@code transaction.currency} and {@code connected_account.currency} both
 *       defaulted to {@code 'CAD'}, so a row whose currency was never set became a Canadian
 *       dollar amount silently — a number that looks perfectly plausible and is wrong by an
 *       exchange rate.</li>
 *   <li><strong>Every {@code user_id} and {@code household_id} carries a foreign key.</strong> The
 *       pre-collapse tables had neither: {@code user_id UUID NOT NULL} with no reference at all,
 *       in seven services. A tenancy column that does not point at anything is a column that can
 *       hold an id belonging to nothing, which is how orphaned ledger rows outlive the household
 *       they were supposed to belong to.</li>
 * </ul>
 */
class SchemaDefaultsIT extends BaseIT {

    /**
     * Numeric columns that are deliberately not money, with the reason each is exempt.
     *
     * <p>An allowlist rather than a name heuristic, so adding a numeric column is a decision
     * somebody makes on purpose: a new one fails this test until it is either {@code NUMERIC(19,4)}
     * or named here.
     */
    private static final Map<String, String> NON_MONEY_NUMERICS = Map.of(
        "merchant_category_map.confidence",
        "a categorisation confidence in [0.000, 1.000] — NUMERIC(4,3), not an amount",
        "receipt.confidence",
        "a receipt pipeline confidence in [0.000, 1.000] — NUMERIC(4,3), matching what step 5 "
            + "already uses for merchant_category_map, not an amount (collapse step 14, V37). "
            + "The four money columns V37 adds — extracted_amount, gst_amount, pst_amount and "
            + "hst_amount — are NUMERIC(19,4) and need no entry here, and neither do line_item's "
            + "three, which is why this is the ONLY entry that step added");

    @Test
    @DisplayName("Every money column is numeric(19,4)")
    void moneyColumnsHaveTheRightPrecision() {
        List<Map<String, Object>> numerics = jdbcTemplate.queryForList(
            "SELECT table_name, column_name, numeric_precision, numeric_scale "
                + "FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND data_type = 'numeric' "
                + "ORDER BY table_name, column_name");

        assertThat(numerics)
            .as("the query must find something, or this test passes by finding nothing")
            .isNotEmpty();

        for (Map<String, Object> column : numerics) {
            String qualified = column.get("table_name") + "." + column.get("column_name");
            if (NON_MONEY_NUMERICS.containsKey(qualified)) {
                continue;
            }
            assertThat(List.of(column.get("numeric_precision"), column.get("numeric_scale")))
                .as("%s is a money column and must be NUMERIC(19,4) (§9 risk 6). If it is not "
                    + "money, add it to NON_MONEY_NUMERICS with its reason", qualified)
                .containsExactly(19, 4);
        }
    }

    @Test
    @DisplayName("No currency column carries a DEFAULT — F4 as a schema rule")
    void noCurrencyColumnHasADefault() {
        List<Map<String, Object>> currencies = jdbcTemplate.queryForList(
            "SELECT table_name, column_default FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND column_name LIKE '%currency%' "
                + "ORDER BY table_name");

        assertThat(currencies)
            .as("at least connected_account.currency and transaction.currency exist")
            .isNotEmpty();
        for (Map<String, Object> column : currencies) {
            assertThat(column.get("column_default"))
                .as("%s.currency must have no default: a row whose currency was never set would "
                    + "silently become that currency, and every amount on it would be wrong by an "
                    + "exchange rate (finding F4)", column.get("table_name"))
                .isNull();
        }
    }

    /**
     * Tenancy columns that deliberately carry no foreign key, with the reason each is exempt.
     *
     * <p>{@code audit_log}'s two are unconstrained on purpose (V11, §10 finding 15): an audit row
     * has to survive the deletion of the account it is evidence about, which is the one moment the
     * evidence matters most, and a constraint on the write path would refuse to record an action
     * whose actor cannot be resolved. Reconciliation belongs in a nightly integrity check.
     */
    private static final Map<String, String> UNCONSTRAINED_TENANCY_COLUMNS = Map.of(
        "audit_log.user_id", "V11: evidence must outlive the account it is about",
        "audit_log.household_id", "V11: as above");

    @Test
    @DisplayName("Every user_id and household_id column has a foreign key, or is allowlisted")
    void everyTenancyColumnIsAForeignKey() {
        Set<String> withForeignKeys = Set.copyOf(jdbcTemplate.queryForList(
            "SELECT kcu.table_name || '.' || kcu.column_name "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON kcu.constraint_name = tc.constraint_name "
                + " AND kcu.table_schema = tc.table_schema "
                + "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'",
            String.class));

        List<String> tenancyColumns = jdbcTemplate.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND column_name IN ('user_id', 'household_id') "
                + "ORDER BY 1", String.class);

        assertThat(tenancyColumns)
            .as("the query must find something, or this test passes by finding nothing")
            .isNotEmpty();
        assertThat(withForeignKeys)
            .as("every tenancy column must point at the table it claims to belong to; a "
                + "user_id with no reference can hold an id belonging to nothing. If a column is "
                + "deliberately unconstrained, add it to UNCONSTRAINED_TENANCY_COLUMNS with its "
                + "reason")
            .containsAll(tenancyColumns.stream()
                .filter(column -> !UNCONSTRAINED_TENANCY_COLUMNS.containsKey(column))
                .toList());
        assertThat(UNCONSTRAINED_TENANCY_COLUMNS.keySet())
            .as("an allowlist entry that names no real column is an exemption nobody can see")
            .allSatisfy(column -> assertThat(tenancyColumns).contains(column));
    }

    @Test
    @DisplayName("Every timestamp column is timestamptz — an instant without a zone is a guess")
    void everyTimestampCarriesAZone() {
        // flyway_schema_history is Flyway's own table, created by Flyway with Flyway's own DDL.
        // It is excluded because this rule is about the schema Trove writes, and the alternative
        // would be a test that can only be made to pass by patching somebody else's migration
        // bookkeeping.
        List<String> naive = jdbcTemplate.queryForList(
            "SELECT table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' "
                + "AND data_type = 'timestamp without time zone' ORDER BY 1", String.class);

        assertThat(naive)
            .as("a naive timestamp is read back in whatever zone the reader assumes, which is the "
                + "class of bug gap P2-15 is about")
            .isEmpty();
    }

    @Test
    @DisplayName("This step's five tables exist with their scoping column")
    void theStepSixTablesAreScoped() {
        for (String table : List.of("connected_account", "transaction", "sync_log",
            "category_override", "import_batch")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = ? AND column_name = 'household_id' "
                    + "AND is_nullable = 'NO'", Integer.class, table))
                .as("%s is a ledger-owned table, so household_id is NOT NULL and is the scope "
                    + "(§1.5) — user_id on these tables means created-by", table)
                .isEqualTo(1);
        }
    }
}
