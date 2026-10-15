package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.TransactionType;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The query builder of {@code GET /v1/transactions}: the sort allowlist, the paging bounds and the
 * composition of the {@code WHERE} clause ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>The point of the last group is not that the SQL is syntactically right —
 * {@code TransactionQueryIT} runs it against Postgres — but that <strong>no caller-supplied value
 * ever reaches the statement</strong>. Every assertion below checks for a named bind parameter and
 * the absence of the value itself, because the pre-collapse query took a sort field straight from
 * the query string.
 */
class TransactionQueryTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    // ------------------------------------------------------------------ the sort allowlist

    @Test
    @DisplayName("The four allowed sorts resolve; anything else is 400")
    void theSortAllowlistIsClosed() {
        assertThat(TransactionSort.from("date,asc")).isEqualTo(TransactionSort.DATE_ASC);
        assertThat(TransactionSort.from("date,desc")).isEqualTo(TransactionSort.DATE_DESC);
        assertThat(TransactionSort.from("amount,asc")).isEqualTo(TransactionSort.AMOUNT_ASC);
        assertThat(TransactionSort.from("amount,desc")).isEqualTo(TransactionSort.AMOUNT_DESC);

        assertThatThrownBy(() -> TransactionSort.from("merchantName,asc"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionSort.from("date"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionSort.from("date,asc; drop table transaction"))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> TransactionSort.from("(select 1)"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A missing sort is date,desc — the newest transactions first")
    void theDefaultSortIsNewestFirst() {
        assertThat(TransactionSort.from(null)).isEqualTo(TransactionSort.DATE_DESC);
        assertThat(TransactionSort.from("  ")).isEqualTo(TransactionSort.DATE_DESC);
        assertThat(TransactionSort.DEFAULT).isEqualTo(TransactionSort.DATE_DESC);
    }

    @Test
    @DisplayName("Case and stray spaces are tolerated; the vocabulary is not widened")
    void theParameterIsNormalisedNotInterpreted() {
        assertThat(TransactionSort.from("DATE, DESC")).isEqualTo(TransactionSort.DATE_DESC);
        assertThatThrownBy(() -> TransactionSort.from("desc,date"))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Every ORDER BY breaks ties on created_at, so paging is stable")
    void everySortHasATiebreaker() {
        for (TransactionSort sort : TransactionSort.values()) {
            assertThat(sort.orderByClause())
                .as("%s: two rows with the same date are the common case, and without a "
                    + "tiebreaker a client paging a ledger sees one row twice and another never",
                    sort)
                .contains("t.created_at");
        }
    }

    // ------------------------------------------------------------------ paging bounds

    @Test
    @DisplayName("limit defaults to 50 and page to 0")
    void theDefaultsAreStated() {
        TransactionQuery query = TransactionQuery.of(null, null, null, null, null, null, null,
            null, null);

        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.page()).isZero();
        assertThat(TransactionQuery.DEFAULT_LIMIT).isEqualTo(50);
        assertThat(TransactionQuery.MAX_LIMIT).isEqualTo(500);
    }

    @Test
    @DisplayName("A limit outside 1..500 is REJECTED, not clamped")
    void anOutOfRangeLimitIsRejected() {
        assertThat(TransactionQuery.of(null, 500, null, null, null, null, null, null, null)
            .limit()).isEqualTo(500);

        assertThatThrownBy(
            () -> TransactionQuery.of(null, 501, null, null, null, null, null, null, null))
            .as("a client that computed 501 asked for 501; quietly giving it 500 and a meta that "
                + "says 500 is a page boundary it did not choose and will get wrong next time")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("500");
        assertThatThrownBy(
            () -> TransactionQuery.of(null, 0, null, null, null, null, null, null, null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(
            () -> TransactionQuery.of(null, -1, null, null, null, null, null, null, null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A negative page is rejected")
    void aNegativePageIsRejected() {
        assertThatThrownBy(
            () -> TransactionQuery.of(-1, null, null, null, null, null, null, null, null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("The offset is computed in long arithmetic")
    void theOffsetCannotOverflow() {
        TransactionQuery deep =
            TransactionQuery.of(20_000_000, 500, null, null, null, null, null, null, null);

        assertThat(deep.offset())
            .as("20,000,000 * 500 overflows an int; a wrapped offset would silently answer with "
                + "the wrong page rather than with nothing")
            .isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("from after to is rejected rather than answering an empty page")
    void anInvertedDateWindowIsRejected() {
        assertThatThrownBy(() -> TransactionQuery.of(null, null, null, LocalDate.of(2026, 9, 4),
            LocalDate.of(2026, 9, 1), null, null, null, null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A blank q is the same as no q; an over-long one is rejected")
    void theSearchTermIsBounded() {
        assertThat(TransactionQuery.of(null, null, null, null, null, null, null, null, "   ")
            .hasSearch()).isFalse();
        assertThat(TransactionQuery.of(null, null, null, null, null, null, null, null, " coffee ")
            .q()).isEqualTo("coffee");
        assertThatThrownBy(() -> TransactionQuery.of(null, null, null, null, null, null, null,
            null, "x".repeat(TransactionQuery.MAX_Q_LENGTH + 1)))
            .isInstanceOf(ValidationException.class);
    }

    // ------------------------------------------------------------------ filter composition

    @Test
    @DisplayName("The household predicate is present and bound on every query, first")
    void theHouseholdFilterIsUnconditional() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = TransactionQueryService.whereClause(HOUSEHOLD, TransactionQuery.unfiltered(),
            parameters);

        assertThat(where).isEqualTo("WHERE t.household_id = :householdId");
        assertThat(parameters).containsExactly(Map.entry("householdId", HOUSEHOLD));
    }

    @Test
    @DisplayName("Each optional filter contributes one bound predicate, and only when present")
    void filtersComposeAndBind() {
        UUID accountId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 31);
        Map<String, Object> parameters = new LinkedHashMap<>();

        String where = TransactionQueryService.whereClause(HOUSEHOLD,
            TransactionQuery.of(0, 50, "amount,asc", from, to, TransactionType.REFUND, accountId,
                categoryId, "blue door"),
            parameters);

        assertThat(where)
            .contains("t.household_id = :householdId")
            .contains("t.date >= :fromDate")
            .contains("t.date <= :toDate")
            .contains("t.type = :type")
            .contains("t.account_id = :accountId")
            .contains("t.category_id = :categoryId")
            .contains("ILIKE :searchPattern");
        assertThat(parameters)
            .containsEntry("householdId", HOUSEHOLD)
            .containsEntry("fromDate", from)
            .containsEntry("toDate", to)
            .containsEntry("type", "REFUND")
            .containsEntry("accountId", accountId)
            .containsEntry("categoryId", categoryId)
            .containsEntry("searchPattern", "%blue door%");
        assertThat(where)
            .as("nothing a caller sent may appear in the statement itself")
            .doesNotContain(accountId.toString())
            .doesNotContain(categoryId.toString())
            .doesNotContain("blue door")
            .doesNotContain("2026-08-01");
    }

    @Test
    @DisplayName("The search runs over exactly the expression V19's trigram index was built on")
    void theSearchExpressionMatchesTheIndex() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = TransactionQueryService.whereClause(HOUSEHOLD,
            TransactionQuery.of(null, null, null, null, null, null, null, null, "coffee"),
            parameters);

        assertThat(where)
            .as("a single character different from V19's GIN index expression and Postgres stops "
                + "using the index and sequentially scans the whole ledger on every search")
            .contains("(coalesce(t.merchant_name, '') || ' ' || coalesce(t.description, '') || ' '"
                + " || coalesce(t.notes, '')) ILIKE :searchPattern");
    }

    @Test
    @DisplayName("A caller's own LIKE wildcards are data, not syntax")
    void wildcardsInTheSearchTermAreEscaped() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        TransactionQueryService.whereClause(HOUSEHOLD,
            TransactionQuery.of(null, null, null, null, null, null, null, null, "100%_off\\now"),
            parameters);

        assertThat(parameters.get("searchPattern"))
            .as("without escaping, q=%% matches every transaction the household has")
            .isEqualTo("%100\\%\\_off\\\\now%");
    }
}
