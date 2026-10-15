package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reading and enriching one transaction ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>The {@code PATCH} assertions are the ones that matter most here: presence is part of the
 * contract, so a field the body does not mention must be left alone and a field present with
 * {@code null} must be cleared. Nothing else in this API makes that distinction, and getting it
 * wrong makes "unlink this transfer" impossible to express.
 */
class TransactionIT extends LedgerSupport {

    @Test
    @DisplayName("GET by id returns the whole row; an unknown id is 404")
    void oneTransactionIsReadable() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Blue Door Coffee").path("id").asString();

        JsonNode row = getTransaction(admin, id);

        assertThat(row.path("id").asString()).isEqualTo(id);
        assertThat(row.path("householdId").asString()).isEqualTo(admin.householdId().toString());
        assertThat(row.path("merchantName").asString()).isEqualTo("Blue Door Coffee");
        assertThat(row.path("date").asString()).isEqualTo("2026-09-04");
        assertThat(get(appUrl("/v1/transactions/" + unknownId()), admin.accessToken())
            .statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("An absent field is left alone; a field present with null is cleared")
    void patchDistinguishesAbsentFromNull() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Blue Door Coffee").path("id").asString();
        String bearer = "Bearer " + admin.accessToken();

        assertThat(patchJson(appUrl("/v1/transactions/" + id),
            Json.object("notes", "Reimbursable", "tags", java.util.List.of("travel", "work")),
            "Authorization", bearer).statusCode()).isEqualTo(200);

        JsonNode afterFirst = getTransaction(admin, id);
        assertThat(afterFirst.path("notes").asString()).isEqualTo("Reimbursable");
        assertThat(Json.elements(afterFirst.path("tags")))
            .extracting(node -> node.asString()).containsExactly("travel", "work");
        assertThat(afterFirst.path("merchantName").asString())
            .as("the body did not mention merchantName")
            .isEqualTo("Blue Door Coffee");

        assertThat(patchJson(appUrl("/v1/transactions/" + id), Json.object("notes", null),
            "Authorization", bearer).statusCode()).isEqualTo(200);

        JsonNode afterSecond = getTransaction(admin, id);
        assertThat(afterSecond.path("notes").isNull())
            .as("an explicit null clears; that is what makes 'unlink this transfer' expressible")
            .isTrue();
        assertThat(Json.elements(afterSecond.path("tags")))
            .as("tags were not mentioned in the second body")
            .hasSize(2);
    }

    @Test
    @DisplayName("An empty patch body is a valid no-op")
    void anEmptyPatchIsANoOp() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        JsonNode before = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Blue Door Coffee");

        HttpResponse<String> response = patchJson(
            appUrl("/v1/transactions/" + before.path("id").asString()), "{}",
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("merchantName").asString())
            .isEqualTo("Blue Door Coffee");
    }

    @Test
    @DisplayName("The immutable half of a transaction is not writable through PATCH")
    void amountDateAndAccountAreNotPatchable() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Blue Door Coffee").path("id").asString();

        // No opt-out: request schemas in this document are deliberately OPEN, because the server
        // runs with FAIL_ON_UNKNOWN_PROPERTIES disabled and a closed one would describe a stricter
        // server than the one that ships. So this body conforms, and what is under test is that
        // the server ignores every field of it.
        HttpResponse<String> response = patchJson(appUrl("/v1/transactions/" + id),
            Json.object("amount", "999999.00", "date", "1999-01-01", "accountId",
                unknownId().toString(), "manual", false),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode after = Json.data(response.body());
        assertThat(after.path("amount").asString()).startsWith("4.75");
        assertThat(after.path("date").asString()).isEqualTo("2026-09-04");
        assertThat(after.path("accountId").asString()).isEqualTo(accountId);
        assertThat(after.path("manual").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A patch field of the wrong type is 400, not a 500")
    void aMistypedPatchFieldIsRejected() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Coffee").path("id").asString();

        HttpResponse<String> response = withoutRequestValidation(
            "the contract types tags as an array of strings, so a client cannot send a number; "
                + "this asserts the server's own parser refuses it with a 400 rather than a 500",
            () -> patchJson(appUrl("/v1/transactions/" + id), "{\"tags\": 7}",
                "Authorization", "Bearer " + admin.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("Unparseable JSON is malformed-request, and quotes nothing back")
    void unparseableJsonIsMalformed() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Coffee").path("id").asString();

        HttpResponse<String> response = withoutRequestValidation(
            "deliberately malformed JSON, which no client sends and the contract cannot describe",
            () -> patchJson(appUrl("/v1/transactions/" + id), "{\"notes\": ",
                "Authorization", "Bearer " + admin.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("malformed-request");
    }

    @Test
    @DisplayName("Two members of one household see and may change the same transaction")
    void theLedgerIsSharedWithinTheHousehold() {
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "tx-mate");
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String id = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Coffee").path("id").asString();

        assertThat(getTransaction(mate, id).path("id").asString())
            .as("one shared ledger per household — a per-user scope would hide a partner's rows")
            .isEqualTo(id);
        assertThat(patchJson(appUrl("/v1/transactions/" + id),
            Json.object("notes", "A mate's note"),
            "Authorization", "Bearer " + mate.accessToken()).statusCode()).isEqualTo(200);
        assertThat(getTransaction(admin, id).path("notes").asString()).isEqualTo("A mate's note");
    }
}
