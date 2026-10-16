package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.app.support.OpenApiConformance;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Linking both legs of a transfer in ONE request: {@code PATCH /v1/transactions/{id}} with
 * {@code {"transferPartnerId": "<other row>"}} (gap G6).
 *
 * <p>Before this, the clients linked a pair with two PATCHes carrying the same
 * {@code transferGroupId}. When the second one failed the first row stayed grouped alone: it
 * vanished from every spend figure while its partner still counted, and the pairing job never
 * repairs that, because it only loads legs whose {@code transfer_group_id} is null. Here both rows
 * are written in one database transaction, so they commit together or not at all — every refusal
 * below checks that NEITHER row moved.
 */
class TransferPartnerLinkIT extends LedgerSupport {

    private Session admin;
    private String bearer;
    private String chequing;
    private String creditCard;

    @BeforeEach
    void setUpTwoAccounts() {
        admin = loginBootstrapAdmin();
        bearer = "Bearer " + admin.accessToken();
        chequing = createManualAccount(admin, "Chequing " + UUID.randomUUID());
        creditCard = createManualAccount(admin, "Card " + UUID.randomUUID());
    }

    private String leg(String account, String amount, String type) {
        return createManualTransaction(admin, account, amount, todayForCaller(admin), type,
            "Leg " + UUID.randomUUID()).path("id").asString();
    }

    private HttpResponse<String> link(String id, String partner) {
        return patchJson(appUrl("/v1/transactions/" + id),
            Json.object("transferPartnerId", partner), "Authorization", bearer);
    }

    private JsonNode group(String id) {
        return getTransaction(admin, id).path("transferGroupId");
    }

    @Test
    @DisplayName("One PATCH links both legs under one group id the server minted")
    void onePatchLinksBothLegs() {
        String out = leg(chequing, "123.45", "DEBIT");
        String in = leg(creditCard, "123.45", "CREDIT");

        HttpResponse<String> response = link(out, in);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        String minted = Json.data(response.body()).path("transferGroupId").asString();
        assertThat(minted).as("the response is this row, already linked").isNotBlank();
        assertThat(group(out).asString()).isEqualTo(minted);
        assertThat(group(in).asString())
            .as("the partner is written in the same request, not by a second one")
            .isEqualTo(minted);
    }

    @Test
    @DisplayName("A partner in another household is 404 and neither row changes")
    void aPartnerOfAnotherHouseholdIsNotFound() {
        String ours = leg(chequing, "55.55", "DEBIT");
        UUID otherHousehold = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES ('Link Household B', false) "
                + "RETURNING id", UUID.class);
        UUID theirAccount = jdbcTemplate.queryForObject(
            "INSERT INTO connected_account (household_id, source_kind, display_name, "
                + "account_type, currency) VALUES (?, 'MANUAL', 'Theirs', 'OTHER', 'CAD') "
                + "RETURNING id", UUID.class, otherHousehold);
        UUID theirs = jdbcTemplate.queryForObject(
            "INSERT INTO transaction (household_id, account_id, amount, currency, type, date, "
                + "manual) VALUES (?, ?, 55.55, 'CAD', 'CREDIT', CURRENT_DATE, true) "
                + "RETURNING id", UUID.class, otherHousehold, theirAccount);

        HttpResponse<String> response = link(ours, theirs.toString());

        assertThat(response.statusCode())
            .as("another household's row does not exist for this caller: 404, never 403")
            .isEqualTo(404);
        assertThat(group(ours).isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT transfer_group_id FROM transaction WHERE id = ?", UUID.class, theirs))
            .as("a link request must never write a row of another household")
            .isNull();
    }

    @Test
    @DisplayName("A partner that is already a transfer leg is 409 and neither row changes")
    void anAlreadyGroupedPartnerIsAConflict() {
        String out = leg(chequing, "66.10", "DEBIT");
        String in = leg(creditCard, "66.10", "CREDIT");
        String third = leg(creditCard, "66.10", "CREDIT");
        assertThat(link(third, out).statusCode()).isEqualTo(200);
        String existing = group(out).asString();

        HttpResponse<String> response = link(in, out);

        assertThat(response.statusCode())
            .as("joining an existing pair would make a three-leg group: %s", response.body())
            .isEqualTo(409);
        assertThat(group(in).isNull()).isTrue();
        assertThat(group(out).asString()).isEqualTo(existing);
        assertThat(group(third).asString()).isEqualTo(existing);
    }

    @Test
    @DisplayName("An already linked row is 409 and its would-be partner is untouched")
    void anAlreadyGroupedRowIsAConflict() {
        String out = leg(chequing, "67.20", "DEBIT");
        String in = leg(creditCard, "67.20", "CREDIT");
        String spare = leg(creditCard, "67.20", "CREDIT");
        assertThat(link(out, in).statusCode()).isEqualTo(200);
        String existing = group(out).asString();

        HttpResponse<String> response = link(out, spare);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(group(spare).isNull())
            .as("the partner write is rolled back with the refusal, not left half-done")
            .isTrue();
        assertThat(group(out).asString()).isEqualTo(existing);
        assertThat(group(in).asString()).isEqualTo(existing);
    }

    @Test
    @DisplayName("A refused link rolls back the other fields of the same body too")
    void aRefusedLinkWritesNothingElse() {
        String out = leg(chequing, "68.30", "DEBIT");
        String grouped = leg(creditCard, "68.30", "CREDIT");
        String pair = leg(chequing, "68.30", "DEBIT");
        assertThat(link(pair, grouped).statusCode()).isEqualTo(200);

        HttpResponse<String> response = patchJson(appUrl("/v1/transactions/" + out),
            Json.object("notes", "should not stick", "transferPartnerId", grouped),
            "Authorization", bearer);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(getTransaction(admin, out).path("notes").isNull())
            .as("one PATCH is one transaction: a refusal writes no part of it")
            .isTrue();
    }

    @Test
    @DisplayName("Linking a row to itself is 400 and the row is unchanged")
    void aRowCannotBeItsOwnPartner() {
        String out = leg(chequing, "69.40", "DEBIT");

        HttpResponse<String> response = link(out, out);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(group(out).isNull()).isTrue();
    }

    @Test
    @DisplayName("transferPartnerId with transferGroupId is 400 and neither row changes")
    void bothIdsAreRefused() {
        String out = leg(chequing, "70.50", "DEBIT");
        String in = leg(creditCard, "70.50", "CREDIT");

        HttpResponse<String> response = patchJson(appUrl("/v1/transactions/" + out),
            Json.object("transferPartnerId", in, "transferGroupId", UUID.randomUUID().toString()),
            "Authorization", bearer);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(group(out).isNull()).isTrue();
        assertThat(group(in).isNull()).isTrue();
    }

    @Test
    @DisplayName("transferPartnerId: null is 400 — unlinking is transferGroupId: null")
    void aNullPartnerIsRefused() {
        String out = leg(chequing, "71.60", "DEBIT");

        HttpResponse<String> response = OpenApiConformance.withoutRequestValidation(
            "transferPartnerId is not nullable; this proves what the server answers",
            () -> patchJson(appUrl("/v1/transactions/" + out), "{\"transferPartnerId\":null}",
                "Authorization", bearer));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(group(out).isNull()).isTrue();
    }

    @Test
    @DisplayName("A partner id that names no row is 404 and this row is unchanged")
    void anUnknownPartnerIsNotFound() {
        String out = leg(chequing, "72.70", "DEBIT");

        HttpResponse<String> response = link(out, unknownId().toString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(group(out).isNull()).isTrue();
    }
}
