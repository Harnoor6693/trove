package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.BaseIT;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * V36's seeds, after Flyway has actually run.
 *
 * <p>The seed is written as {@code INSERT … SELECT c.id FROM category c WHERE c.household_id IS
 * NULL AND c.slug = …} rather than as the {@code WHERE name = <slug>} form that was V15's own
 * blocker — that shape yielded NULL into a NOT NULL FK and halted Flyway on every boot. Written
 * this way a typo produces NO ROW, which is silent; these assertions are what turn that silence
 * into a failing build.
 */
class TaxExportSeedIT extends BaseIT {

    /** V14 seeds this many system categories, and V36 maps every one of them twice. */
    private static final int SYSTEM_CATEGORIES = 51;

    @Test
    @DisplayName("Every system category has exactly one CA row and one US row")
    void everySystemCategoryIsMappedInBothJurisdictions() {
        List<Map<String, Object>> unmapped = jdbcTemplate.queryForList("""
            SELECT c.slug,
                   count(*) FILTER (WHERE m.jurisdiction = 'CA') AS ca,
                   count(*) FILTER (WHERE m.jurisdiction = 'US') AS us
            FROM category c
            LEFT JOIN tax_line_mapping m
                   ON m.category_id = c.id AND m.user_id IS NULL
            WHERE c.household_id IS NULL
            GROUP BY c.slug
            HAVING count(*) FILTER (WHERE m.jurisdiction = 'CA') <> 1
                OR count(*) FILTER (WHERE m.jurisdiction = 'US') <> 1
            """);

        assertThat(unmapped)
            .as("a category with no line is exported with EMPTY tax-line cells, which is the "
                + "right behaviour for a household's own category and the wrong one for a "
                + "system category the seed was supposed to cover — a slug typo in V36 produces "
                + "no row rather than an error, so this is where it surfaces")
            .isEmpty();
    }

    @Test
    @DisplayName("The seed is exactly twice the number of system categories")
    void theSeedCountIsExactlyTwicePerCategory() {
        Long seeds = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tax_line_mapping WHERE user_id IS NULL", Long.class);
        Long systemCategories = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category WHERE household_id IS NULL", Long.class);

        assertThat(systemCategories)
            .as("V14 seeds %d system categories and nothing in the API can create another",
                SYSTEM_CATEGORIES)
            .isGreaterThanOrEqualTo(SYSTEM_CATEGORIES);
        assertThat(seeds)
            .as("exactly two rows per system category, CA and US — the relation rather than a "
                + "literal, so a later migration that adds a system category must add its two "
                + "lines with it instead of breaking this")
            .isEqualTo(2L * systemCategories);
    }

    @Test
    @DisplayName("No seeded row points at a null category (the V15 blocker, as a regression)")
    void noSeededRowHasANullCategory() {
        // The column is NOT NULL, so this can only ever be zero — which is the point. V15's own
        // blocker was an INSERT that tried to put a NULL there and halted Flyway on every boot;
        // if that shape ever came back, the whole IT suite would fail to start, and this
        // assertion is the note explaining why.
        Long orphans = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tax_line_mapping WHERE category_id IS NULL", Long.class);

        assertThat(orphans).isZero();
    }

    @Test
    @DisplayName("Both jurisdictions are seeded, and only those two")
    void onlyTheTwoSupportedJurisdictionsAreSeeded() {
        List<String> jurisdictions = jdbcTemplate.queryForList(
            "SELECT DISTINCT jurisdiction FROM tax_line_mapping ORDER BY jurisdiction",
            String.class);

        assertThat(jurisdictions)
            .as("owner decision 3: Canada and the USA, and nothing else")
            .containsExactly("CA", "US");
    }

    @Test
    @DisplayName("Seeded rows carry a line code and a label and never a rate")
    void seededRowsAreLabelsOnly() {
        List<Map<String, Object>> suspicious = jdbcTemplate.queryForList("""
            SELECT line_code, line_label FROM tax_line_mapping
            WHERE user_id IS NULL
              AND (line_code = '' OR line_label = ''
                   OR lower(line_label) LIKE '%rate%'
                   OR lower(line_label) LIKE '%gst%'
                   OR lower(line_code) LIKE '%rate%')
            """);

        assertThat(suspicious)
            .as("§9 risk 15: line codes and labels are strings a human reads off a form. A rate, "
                + "a bracket or a percentage in this table would be the tax engine coming back")
            .isEmpty();
    }

    @Test
    @DisplayName("A business category is seeded onto a real form line in both jurisdictions")
    void aBusinessCategoryIsSeededOntoARealLine() {
        assertThat(seededCode("business.office", "CA"))
            .as("T2125 line 8810 is Office expenses")
            .isEqualTo("8810");
        assertThat(seededCode("business.office", "US"))
            .as("Schedule C line 18 is Office expense")
            .isEqualTo("SchC-18");
        assertThat(deductible("business.office", "CA")).isTrue();
    }

    @Test
    @DisplayName("A personal category is seeded NONE rather than onto a wrong line")
    void aPersonalCategoryIsSeededWithNoLine() {
        assertThat(seededCode("food.grocery", "CA"))
            .as("a wrong line code on a tax form is worse than a blank one, so groceries are not "
                + "seeded onto T2125 9270 'Other expenses' to fill the cell")
            .isEqualTo(TaxLineMapping.NO_LINE_CODE);
        assertThat(deductible("food.grocery", "US")).isFalse();
    }

    @Test
    @DisplayName("The unique constraint is NULLS NOT DISTINCT, so a second seed cannot exist")
    void theSeedIsUniqueAgainstItself() {
        UUID categoryId = jdbcTemplate.queryForObject(
            "SELECT id FROM category WHERE household_id IS NULL AND slug = 'business.office'",
            UUID.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO tax_line_mapping (jurisdiction, category_id, user_id, line_code, "
                + "line_label, deductible) VALUES ('CA', ?, NULL, '9999', 'A second seed', false)",
            categoryId))
            .as("under the plain-UNIQUE default every NULL is distinct from every other, so the "
                + "seed would not be unique against itself, ON CONFLICT could not target it, and "
                + "resolve() would have to choose between two rows both claiming to be global")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String seededCode(String slug, String jurisdiction) {
        return jdbcTemplate.queryForObject("""
            SELECT m.line_code FROM tax_line_mapping m
            JOIN category c ON c.id = m.category_id
            WHERE c.household_id IS NULL AND c.slug = ? AND m.jurisdiction = ?
              AND m.user_id IS NULL
            """, String.class, slug, jurisdiction);
    }

    private Boolean deductible(String slug, String jurisdiction) {
        return jdbcTemplate.queryForObject("""
            SELECT m.deductible FROM tax_line_mapping m
            JOIN category c ON c.id = m.category_id
            WHERE c.household_id IS NULL AND c.slug = ? AND m.jurisdiction = ?
              AND m.user_id IS NULL
            """, Boolean.class, slug, jurisdiction);
    }
}
