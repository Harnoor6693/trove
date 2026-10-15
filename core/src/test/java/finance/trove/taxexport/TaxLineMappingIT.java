package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET} and {@code PUT /v1/tax-export/lines}, end to end against a real Postgres.
 *
 * <p>This is where the precedence rule is actually proved, because it is resolved in ONE SQL
 * statement ({@code DISTINCT ON … ORDER BY user_id NULLS LAST}) and a unit test against a mocked
 * repository would only assert the mock.
 */
class TaxLineMappingIT extends LedgerSupport {

    private static final String LINES = "/v1/tax-export/lines";

    private JsonNode lineFor(Session session, String query, String slug) {
        HttpResponse<String> response =
            get(appUrl(LINES + query), session.accessToken());
        assertThat(response.statusCode())
            .as("listing tax lines failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body())).stream()
            .filter(row -> slug.equals(row.path("categorySlug").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                slug + " is missing from the tax-line list; every visible category must be in it"));
    }

    private HttpResponse<String> putLine(Session session, String categoryId, String jurisdiction,
                                         String code, String label, boolean deductible) {
        return putJson(appUrl(LINES + "/" + categoryId),
            Json.object("jurisdiction", jurisdiction, "lineCode", code, "lineLabel", label,
                "deductible", deductible),
            "Authorization", "Bearer " + session.accessToken());
    }

    @Test
    @DisplayName("The list carries every visible category with its seeded line")
    void theListCarriesEverySeededLine() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-list");

        JsonNode office = lineFor(member, "", "business.office");

        assertThat(office.path("jurisdiction").asString())
            .as("the default is the caller's own users.country, which registration set to CA")
            .isEqualTo("CA");
        assertThat(office.path("lineCode").asString()).isEqualTo("8810");
        assertThat(office.path("lineLabel").asString()).contains("8810");
        assertThat(office.path("deductible").asBoolean()).isTrue();
        assertThat(office.path("overridden").asBoolean())
            .as("a seed row is not the member's own")
            .isFalse();
    }

    @Test
    @DisplayName("A member's override beats the seed for the same category and jurisdiction")
    void anOverrideBeatsTheSeed() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-override");
        String categoryId = systemCategoryId(member, "business.software");

        HttpResponse<String> written = putLine(member, categoryId, "CA", "9270",
            "T2125 line 9270 - Other expenses", false);
        assertThat(written.statusCode()).as("%s", written.body()).isEqualTo(200);
        assertThat(Json.data(written.body()).path("overridden").asBoolean()).isTrue();

        JsonNode resolved = lineFor(member, "", "business.software");
        assertThat(resolved.path("lineCode").asString()).isEqualTo("9270");
        assertThat(resolved.path("deductible").asBoolean()).isFalse();
        assertThat(resolved.path("overridden").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Writing the same category twice replaces the override, never duplicates it")
    void repeatingTheWriteReplacesTheRow() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-upsert");
        String categoryId = systemCategoryId(member, "business.general");

        assertThat(putLine(member, categoryId, "CA", "9270", "First", true).statusCode())
            .isEqualTo(200);
        assertThat(putLine(member, categoryId, "CA", "8521", "Second", false).statusCode())
            .as("ON CONFLICT on UNIQUE (jurisdiction, category_id, user_id) NULLS NOT DISTINCT")
            .isEqualTo(200);

        assertThat(lineFor(member, "", "business.general").path("lineCode").asString())
            .isEqualTo("8521");
        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tax_line_mapping WHERE user_id = ? AND category_id = ?",
            Long.class, member.userId(), UUID.fromString(categoryId));
        assertThat(rows).as("one row per member per category per jurisdiction").isEqualTo(1L);
    }

    @Test
    @DisplayName("Two concurrent writes of the same category both succeed and leave one row")
    void twoConcurrentWritesOfTheSameCategoryBothSucceed() throws Exception {
        // A read-then-write upsert is a check-then-act: both threads read "no row yet" in their
        // own transaction, both INSERT, and the loser dies on
        // UNIQUE (jurisdiction, category_id, user_id) NULLS NOT DISTINCT — which
        // GlobalExceptionHandler.handleDataIntegrity turns into a 409 this operation does not
        // declare, so BaseIT's contract validation would fail on it too. The write is one
        // INSERT ... ON CONFLICT DO UPDATE precisely so that the second writer updates instead.
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-race");
        String categoryId = systemCategoryId(member, "travel.flights");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = List.of(
                pool.submit(() -> putRace(ready, go, member, categoryId, "9200", "Travel")),
                pool.submit(() -> putRace(ready, go, member, categoryId, "9270", "Other")));

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                results.get(1).get(60, TimeUnit.SECONDS));

            assertThat(statuses)
                .as("neither writer may lose on the unique index: %s", statuses)
                .containsExactly(200, 200);
        } finally {
            pool.shutdownNow();
        }

        Long rows = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tax_line_mapping WHERE user_id = ? AND category_id = ?",
            Long.class, member.userId(), UUID.fromString(categoryId));
        assertThat(rows).as("one row per member per category per jurisdiction").isEqualTo(1L);
        assertThat(lineFor(member, "", "travel.flights").path("lineCode").asString())
            .as("whichever writer committed last is the one that stands")
            .isIn("9200", "9270");
    }

    /** One racer: warm the connection up, announce readiness, wait for the flag, then write. */
    private int putRace(CountDownLatch ready, CountDownLatch go, Session member, String categoryId,
                        String code, String label) throws Exception {
        get(appUrl("/v1/auth/me"), member.accessToken());
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        return putLine(member, categoryId, "CA", code, label, true).statusCode();
    }

    @Test
    @DisplayName("A CA override does not shadow the US seed for the same category")
    void theTwoJurisdictionsDoNotShadowEachOther() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-jurisdiction");
        String categoryId = systemCategoryId(member, "health.pharmacy");

        assertThat(putLine(member, categoryId, "CA", "33099", "My own CA label", true)
            .statusCode()).isEqualTo(200);

        JsonNode canadian = lineFor(member, "?jurisdiction=CA", "health.pharmacy");
        JsonNode american = lineFor(member, "?jurisdiction=US", "health.pharmacy");

        assertThat(canadian.path("lineLabel").asString()).isEqualTo("My own CA label");
        assertThat(american.path("lineCode").asString())
            .as("the jurisdiction is part of the key; a CA override must leave the US seed alone")
            .isEqualTo("SchA-1");
        assertThat(american.path("overridden").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A household mate's override is invisible: a filing is individual")
    void aHouseholdMatesOverrideIsInvisible() {
        Session admin = loginBootstrapAdmin();
        Session one = inviteAndRegister(admin, "tax-lines-mate-one");
        Session two = inviteAndRegister(admin, "tax-lines-mate-two");
        assertThat(one.householdId())
            .as("both members must be in the same household for this to mean anything")
            .isEqualTo(two.householdId());
        String categoryId = systemCategoryId(one, "transport.fuel");

        assertThat(putLine(one, categoryId, "CA", "9281", "Motor vehicle expenses", true)
            .statusCode()).isEqualTo(200);

        assertThat(lineFor(one, "", "transport.fuel").path("lineCode").asString())
            .isEqualTo("9281");
        JsonNode asSeenByTheOtherMember = lineFor(two, "", "transport.fuel");
        assertThat(asSeenByTheOtherMember.path("lineCode").asString())
            .as("§1.5/§6: the ledger is shared but the FILING is individual, so an override "
                + "belongs to the member who wrote it. This is the one place on the API where a "
                + "household mate is deliberately not shown a household mate's row")
            .isEqualTo(TaxLineMapping.NO_LINE_CODE);
        assertThat(asSeenByTheOtherMember.path("overridden").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A category the caller cannot see is 404, never 403")
    void aForeignCategoryIs404() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-foreign");

        HttpResponse<String> response = putLine(member, unknownId().toString(), "CA", "9270",
            "Other expenses", true);

        assertThat(response.statusCode())
            .as("a 403 would confirm the id is real. Body: %s", response.body())
            .isEqualTo(404);
        assertThat(Json.errorCode(response.body())).isEqualTo("not-found");
    }

    @Test
    @DisplayName("A category with no row is listed with empty line fields, not dropped")
    void anUnmappedCategoryIsStillListed() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-unmapped");
        HttpResponse<String> created = postJson(appUrl("/v1/categories"),
            Json.object("name", "Probe category " + UUID.randomUUID().toString().substring(0, 8),
                "parentId", null, "icon", null),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(201);
        String slug = Json.data(created.body()).path("slug").asString();

        JsonNode row = lineFor(member, "", slug);

        assertThat(row.path("lineCode").asString())
            .as("a household's own category has no seeded line, and the category must still be "
                + "listed — a transaction that vanished from a tax export because nobody wrote a "
                + "label for its category is the worst failure this step can have")
            .isEmpty();
        assertThat(row.path("lineLabel").asString()).isEmpty();
        assertThat(row.path("deductible").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("An unknown jurisdiction is 400, not a silent fallback")
    void anUnknownJurisdictionIsRejected() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "tax-lines-bad-jurisdiction");

        HttpResponse<String> response = withoutRequestValidation(
            "the contract's CountryCode enum is CA|US; this asks what the server answers for a "
                + "value no client can send",
            () -> get(appUrl(LINES + "?jurisdiction=GB"), member.accessToken()));

        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("Both routes require a session")
    void bothRoutesRequireASession() {
        assertThat(get(appUrl(LINES)).statusCode()).isEqualTo(401);
        assertThat(putJson(appUrl(LINES + "/" + unknownId()),
            Json.object("jurisdiction", "CA", "lineCode", "9270", "lineLabel", "Other",
                "deductible", true)).statusCode()).isEqualTo(401);
    }

}
