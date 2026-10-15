package finance.trove.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup for every collapse step-6 integration test: accounts, and transactions on them.
 *
 * <p>Like {@link AuthFlowSupport}, it seeds nothing directly. Every fixture is built by calling the
 * real endpoint, so a fixture that stops working is a test that fails rather than a test that
 * quietly keeps passing against a schema the API can no longer produce. The one exception is
 * {@code AccessControlIT}'s second household, which has no endpoint by design.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class LedgerSupport extends AuthFlowSupport {

    /** The kind of account hand-entered transactions are allowed on (gap P2-13). */
    public static final String MANUAL = "MANUAL";

    /** The one kind {@code TestBankSource} serves, and therefore the only syncable one. */
    public static final String SIMPLEFIN = "SIMPLEFIN";

    /** Creates an account and returns its id. */
    protected String createAccount(Session session, String sourceKind, String displayName,
                                   String accountType, String currency) {
        HttpResponse<String> response = postJson(appUrl("/v1/accounts"),
            Json.object("sourceKind", sourceKind, "displayName", displayName,
                "institution", null, "accountType", accountType, "currency", currency),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("creating a %s account failed: %s", sourceKind, response.body())
            .isEqualTo(201);
        return Json.data(response.body()).path("id").asString();
    }

    /** A credential-less manual account, which is what a hand-entered transaction needs. */
    protected String createManualAccount(Session session, String displayName) {
        return createAccount(session, MANUAL, displayName, "OTHER", "CAD");
    }

    /** A syncable account. Only {@code TestBankSource} can serve it, and only under test. */
    protected String createSyncableAccount(Session session, String displayName) {
        return createAccount(session, SIMPLEFIN, displayName, "CHECKING", "CAD");
    }

    /** Records a transaction by hand and returns the created row. */
    protected JsonNode createManualTransaction(Session session, String accountId, String amount,
                                               String date, String type, String merchant) {
        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", amount, "date", date, "type", type,
                "merchant", merchant, "categoryId", null, "notes", null),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("recording a manual transaction failed: %s", response.body())
            .isEqualTo(201);
        return Json.data(response.body());
    }

    /** A PATCH, which {@link BaseIT} has no named helper for. */
    protected HttpResponse<String> patchJson(String url, String json, String... headers) {
        return exchange("PATCH", url, json, headers);
    }

    /** One page of the caller's ledger. {@code query} is appended verbatim, without a leading ?. */
    protected List<JsonNode> listTransactions(Session session, String query) {
        HttpResponse<String> response = get(
            appUrl("/v1/transactions" + (query.isEmpty() ? "" : "?" + query)),
            session.accessToken());
        assertThat(response.statusCode())
            .as("listing transactions failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** The ids of one page of the caller's ledger, in the order the server returned them. */
    protected List<String> transactionIds(Session session, String query) {
        return listTransactions(session, query).stream()
            .map(row -> row.path("id").asString())
            .toList();
    }

    /** One transaction by id, as the caller sees it. */
    protected JsonNode getTransaction(Session session, String transactionId) {
        HttpResponse<String> response =
            get(appUrl("/v1/transactions/" + transactionId), session.accessToken());
        assertThat(response.statusCode())
            .as("reading transaction %s failed: %s", transactionId, response.body())
            .isEqualTo(200);
        return Json.data(response.body());
    }

    /** The id of a system category, resolved by its stable slug rather than by its name. */
    protected String systemCategoryId(Session session, String slug) {
        return Json.elements(Json.data(get(appUrl("/v1/categories"), session.accessToken()).body()))
            .stream()
            .filter(category -> slug.equals(category.path("slug").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(slug + " is missing from the V14 seed"))
            .path("id").asString();
    }

    /**
     * The caller's own "today", as the server computes it.
     *
     * <p>Not {@code LocalDate.now()}: that is the test JVM's default zone, and the server resolves
     * every boundary in the member's {@code users.timezone} (gap P2-15). The two straddle midnight
     * for several hours a day, so a test that used the JVM's date would fail in the evening and
     * pass in the morning — the exact class of bug this step exists to remove.
     */
    protected String todayForCaller(Session session) {
        return Json.data(get(appUrl("/v1/dashboard/today"), session.accessToken()).body())
            .path("date").asString();
    }

    /** A UUID that is syntactically valid and belongs to nothing. */
    protected static UUID unknownId() {
        return UUID.randomUUID();
    }
}
