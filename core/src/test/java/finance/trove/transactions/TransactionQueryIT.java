package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/transactions} against a real Postgres — paging, filters, the sort allowlist and
 * the {@code pg_trgm} search ({@code docs/recovery/07-READINESS-GAPS.md} P2-15).
 *
 * <p>Every assertion narrows to one account this test created, because the suite shares one
 * database and one household and an absolute row count would be a promise about every other test
 * in the run.
 */
class TransactionQueryIT extends LedgerSupport {

    private Session admin;
    private String accountId;
    private String otherAccountId;

    @BeforeEach
    void seedALedger() {
        admin = loginBootstrapAdmin();
        accountId = createManualAccount(admin, "Query " + UUID.randomUUID());
        otherAccountId = createManualAccount(admin, "Query other " + UUID.randomUUID());

        createManualTransaction(admin, accountId, "10.00", "2026-03-01", "DEBIT",
            "Blue Door Coffee");
        createManualTransaction(admin, accountId, "30.00", "2026-03-02", "DEBIT",
            "Riverside Grocery");
        createManualTransaction(admin, accountId, "20.00", "2026-03-03", "CREDIT",
            "Employer Payroll");
        createManualTransaction(admin, otherAccountId, "40.00", "2026-03-04", "DEBIT",
            "Somewhere Else");
    }

    @Test
    @DisplayName("The page carries its totals in meta, and page/limit are honoured")
    void pagingIsReportedInMeta() {
        HttpResponse<String> first = get(
            appUrl("/v1/transactions?accountId=" + accountId + "&limit=2&sort=date,asc"),
            admin.accessToken());

        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode meta = Json.parse(first.body()).path("meta");
        assertThat(meta.path("total").asInt()).isEqualTo(3);
        assertThat(meta.path("page").asInt()).isZero();
        assertThat(meta.path("limit").asInt()).isEqualTo(2);
        assertThat(Json.elements(Json.data(first.body()))).hasSize(2);

        List<String> secondPage =
            transactionIds(admin, "accountId=" + accountId + "&limit=2&page=1&sort=date,asc");
        assertThat(secondPage).hasSize(1);
    }

    @Test
    @DisplayName("An empty page still carries meta, so a client can render its controls")
    void anEmptyPageCarriesMeta() {
        HttpResponse<String> response = get(
            appUrl("/v1/transactions?accountId=" + accountId + "&from=1999-01-01&to=1999-12-31"),
            admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(response.body()))).isEmpty();
        assertThat(Json.parse(response.body()).path("meta").path("total").asInt()).isZero();
    }

    @Test
    @DisplayName("Every allowlisted sort orders the page; anything else is 400")
    void theSortAllowlistHolds() {
        assertThat(merchantsOf("accountId=" + accountId + "&sort=date,asc"))
            .containsExactly("Blue Door Coffee", "Riverside Grocery", "Employer Payroll");
        assertThat(merchantsOf("accountId=" + accountId + "&sort=date,desc"))
            .containsExactly("Employer Payroll", "Riverside Grocery", "Blue Door Coffee");
        assertThat(merchantsOf("accountId=" + accountId + "&sort=amount,asc"))
            .containsExactly("Blue Door Coffee", "Employer Payroll", "Riverside Grocery");
        assertThat(merchantsOf("accountId=" + accountId + "&sort=amount,desc"))
            .containsExactly("Riverside Grocery", "Employer Payroll", "Blue Door Coffee");

        HttpResponse<String> refused = withoutRequestValidation(
            "the contract declares sort as a four-value enum, so a client cannot send a column "
                + "name; this asserts the SERVER refuses one rather than passing it into an "
                + "ORDER BY, which is what the pre-collapse query did",
            () -> get(appUrl("/v1/transactions?sort=merchant_name,asc"), admin.accessToken()));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(refused.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("A limit above the stated maximum is refused, not clamped")
    void anOverLargeLimitIsRefused() {
        HttpResponse<String> refused = withoutRequestValidation(
            "the contract caps limit at 500, so a conforming client cannot send 501; this "
                + "asserts the server enforces the same ceiling rather than trusting the document",
            () -> get(appUrl("/v1/transactions?limit=501"), admin.accessToken()));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(refused.body())).isEqualTo("validation-error");
        assertThat(get(appUrl("/v1/transactions?limit=500"), admin.accessToken()).statusCode())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("Filters compose: date window, type, account and category all narrow the page")
    void filtersCompose() {
        assertThat(merchantsOf("accountId=" + accountId + "&from=2026-03-02&to=2026-03-02"))
            .containsExactly("Riverside Grocery");
        assertThat(merchantsOf("accountId=" + accountId + "&type=CREDIT"))
            .containsExactly("Employer Payroll");
        assertThat(merchantsOf("accountId=" + otherAccountId))
            .containsExactly("Somewhere Else");
        assertThat(merchantsOf("accountId=" + accountId + "&type=DEBIT&from=2026-03-01"
            + "&to=2026-03-01"))
            .containsExactly("Blue Door Coffee");

        String coffeeCategory = Json.data(
            get(appUrl("/v1/transactions?accountId=" + accountId + "&type=CREDIT"),
                admin.accessToken()).body())
            .path(0).path("categoryId").asString();
        assertThat(merchantsOf("accountId=" + accountId + "&categoryId=" + coffeeCategory))
            .isNotEmpty();
    }

    @Test
    @DisplayName("q searches merchant, description and notes through the trigram index")
    void theFreeTextSearchWorksOverAllThreeColumns() {
        String withNote = createManualTransaction(admin, accountId, "55.00", "2026-03-05", "DEBIT",
            "Anonymous Merchant").path("id").asString();
        assertThat(patchJson(appUrl("/v1/transactions/" + withNote),
            Json.object("notes", "Aurora Borealis Society dues"),
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);

        assertThat(merchantsOf("accountId=" + accountId + "&q=" + encoded("blue door")))
            .as("case-insensitive substring of the merchant")
            .containsExactly("Blue Door Coffee");
        assertThat(merchantsOf("accountId=" + accountId + "&q=GROCER"))
            .containsExactly("Riverside Grocery");
        assertThat(merchantsOf("accountId=" + accountId + "&q=Borealis"))
            .as("the same search reaches notes, which is what gap P2-15 asks for")
            .containsExactly("Anonymous Merchant");
        assertThat(merchantsOf("accountId=" + accountId + "&q=nothingmatchesthisatall"))
            .isEmpty();
    }

    @Test
    @DisplayName("A caller's own LIKE wildcards match nothing rather than everything")
    void wildcardsInTheSearchTermAreNotSyntax() {
        assertThat(transactionIds(admin, "accountId=" + accountId + "&q=" + encoded("%")))
            .as("without escaping this would return every transaction the household has")
            .isEmpty();
        assertThat(transactionIds(admin, "accountId=" + accountId + "&q=" + encoded("_")))
            .isEmpty();
    }

    @Test
    @DisplayName("An id from another household matches nothing rather than leaking a 403")
    void aForeignFilterValueSimplyMatchesNothing() {
        assertThat(transactionIds(admin, "accountId=" + unknownId()))
            .as("household_id is the first predicate, so an unknown account id cannot name a row")
            .isEmpty();
    }

    @Test
    @DisplayName("An inverted date window is 400, not an empty page")
    void anInvertedWindowIsRejected() {
        HttpResponse<String> response = get(
            appUrl("/v1/transactions?from=2026-03-31&to=2026-03-01"), admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("A non-numeric page is 400 with the parameter named")
    void anUnparseablePageIsRejected() {
        HttpResponse<String> response = withoutRequestValidation(
            "the contract types page as an integer, so a client cannot send 'abc'; this asserts "
                + "the server answers 400 rather than 500",
            () -> get(appUrl("/v1/transactions?page=abc"), admin.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    /** Percent-encodes one query-string value; a raw space or % is not a legal URL. */
    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private List<String> merchantsOf(String query) {
        return listTransactions(admin, query).stream()
            .map(row -> row.path("merchantName").asString())
            .toList();
    }
}
