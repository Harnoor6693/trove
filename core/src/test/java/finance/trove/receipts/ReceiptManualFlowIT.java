package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.ReceiptVisionStub;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The AI-off path, end to end — §9 risk 12's other half.
 *
 * <p>A member with <strong>no {@code ai_settings} row at all</strong> uploads a receipt, gets
 * {@code COMPLETE} with {@code reviewRequired}, fills the fields in by hand, links a transaction,
 * and <strong>both halves of the link are set</strong>. This is not a fallback nobody meant: it is
 * the decided degradation (owner decision 2026-09-03) and the ordinary outcome on every install
 * that has not configured a vision model — which is every install by default.
 *
 * <p>The stub vision server is started and then asserted never to have been contacted. Without
 * that, "no AI was used" would be an inference from the absence of a row rather than a measurement
 * at the socket.
 */
class ReceiptManualFlowIT extends AuthFlowSupport {

    private ReceiptVisionStub stub;
    private Session member;

    @BeforeEach
    void signIn() {
        stub = ReceiptVisionStub.start().answering("{\"merchant\":\"SHOULD NOT BE ASKED\"}");
        member = inviteAndRegister(loginBootstrapAdmin(), "receipt-manual");
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private String bearer() {
        return "Bearer " + member.accessToken();
    }

    private UUID uploadReceipt() {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(900, 1_200))),
            "Authorization", bearer());
        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);
        return UUID.fromString(Json.data(response.body()).path("id").asString());
    }

    @Test
    @DisplayName("a member with no ai_settings row gets COMPLETE + reviewRequired, and no call")
    void aMemberWithNoAiSettingsGetsTheManualPath() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_settings WHERE user_id = ?", Integer.class, member.userId()))
            .as("this test's whole premise is a member with no AI configured at all")
            .isZero();

        UUID id = uploadReceipt();

        JsonNode receipt = Json.data(get(appUrl("/v1/receipts/" + id), member.accessToken())
            .body());
        assertThat(receipt.path("status").asString()).isEqualTo("COMPLETE");
        assertThat(receipt.path("reviewRequired").asBoolean()).isTrue();
        assertThat(receipt.path("extractedMerchant").isNull()).isTrue();
        assertThat(receipt.path("extractedAmount").isNull()).isTrue();
        assertThat(receipt.path("confidence").isNull()).isTrue();
        assertThat(receipt.path("errorMessage").isNull())
            .as("REVIEW_REQUIRED is not an error and carries no error message")
            .isTrue();
        assertThat(receipt.path("thumbnailUrl").asString())
            .isEqualTo("/api/v1/receipts/" + id + "/thumbnail");

        assertThat(stub.requestCount())
            .as("no vision endpoint may be contacted for a member who has not configured one — "
                + "measured at the socket, not inferred from a missing row")
            .isZero();
    }

    @Test
    @DisplayName("PATCH writes the manual fields and leaves absent ones alone")
    void patchWritesTheManualFields() {
        UUID id = uploadReceipt();

        HttpResponse<String> patched = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("extractedMerchant", "Blue Bottle Coffee",
                "extractedAmount", "12.34",
                "extractedCurrency", "CAD",
                "extractedDate", "2026-06-05"),
            "Authorization", bearer());

        assertThat(patched.statusCode())
            .as("patch failed: %s", patched.body())
            .isEqualTo(200);
        JsonNode data = Json.data(patched.body());
        assertThat(data.path("extractedMerchant").asString()).isEqualTo("Blue Bottle Coffee");
        assertThat(new BigDecimal(data.path("extractedAmount").asString()))
            .isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(data.path("extractedCurrency").asString()).isEqualTo("CAD");
        assertThat(data.path("extractedDate").asString()).isEqualTo("2026-06-05");

        // A second patch naming one field leaves the others alone.
        HttpResponse<String> second = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("extractedMerchant", "Corner Store"), "Authorization", bearer());
        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode after = Json.data(second.body());
        assertThat(after.path("extractedMerchant").asString()).isEqualTo("Corner Store");
        assertThat(after.path("extractedDate").asString()).isEqualTo("2026-06-05");
    }

    @Test
    @DisplayName("a patch that changes nothing is 400 rather than a silent no-op")
    void anEmptyPatchIs400() {
        UUID id = uploadReceipt();

        HttpResponse<String> response = exchange("PATCH", appUrl("/v1/receipts/" + id), "{}",
            "Authorization", bearer());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("linking a transaction writes BOTH halves of the link in one transaction")
    void linkingWritesBothHalves() {
        UUID id = uploadReceipt();
        UUID transactionId = createManualTransaction();

        HttpResponse<String> response = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("matchedTransactionId", transactionId.toString()),
            "Authorization", bearer());

        assertThat(response.statusCode())
            .as("link failed: %s", response.body())
            .isEqualTo(200);
        assertThat(Json.data(response.body()).path("matchedTransactionId").asString())
            .isEqualTo(transactionId.toString());

        // Both columns, read straight out of Postgres. One service method writes either of them
        // and it writes both in one transaction; two half-written links is the failure the design
        // is shaped to prevent, so the assertion is on the database and not on the response.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_transaction_id FROM receipt WHERE id = ?", UUID.class, id))
            .isEqualTo(transactionId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT receipt_id FROM transaction WHERE id = ?", UUID.class, transactionId))
            .isEqualTo(id);
    }

    @Test
    @DisplayName("moving a link clears the previous partner, so no half-link is left behind")
    void movingALinkClearsThePreviousPartner() {
        UUID id = uploadReceipt();
        UUID first = createManualTransaction();
        UUID second = createManualTransaction();

        exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("matchedTransactionId", first.toString()), "Authorization", bearer());
        exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("matchedTransactionId", second.toString()), "Authorization", bearer());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT receipt_id FROM transaction WHERE id = ?", UUID.class, first))
            .as("the transaction the receipt used to point at must not still point back at it")
            .isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT receipt_id FROM transaction WHERE id = ?", UUID.class, second))
            .isEqualTo(id);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_transaction_id FROM receipt WHERE id = ?", UUID.class, id))
            .isEqualTo(second);
    }

    @Test
    @DisplayName("linking a transaction of another household is 404, and writes neither half")
    void linkingAForeignTransactionIs404() {
        UUID id = uploadReceipt();

        HttpResponse<String> response = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("matchedTransactionId", UUID.randomUUID().toString()),
            "Authorization", bearer());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(response.body())).isEqualTo("not-found");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT matched_transaction_id FROM receipt WHERE id = ?", UUID.class, id)).isNull();
    }

    @Test
    @DisplayName("accepting a review clears the flag; rejecting one FAILS the row but keeps the file")
    void reviewRecordsTheVerdict() {
        UUID accepted = uploadReceipt();
        HttpResponse<String> yes = exchange("PATCH",
            appUrl("/v1/receipts/" + accepted + "/review"), Json.object("accepted", true),
            "Authorization", bearer());
        assertThat(yes.statusCode()).isEqualTo(200);
        assertThat(Json.data(yes.body()).path("reviewRequired").asBoolean()).isFalse();
        assertThat(Json.data(yes.body()).path("status").asString()).isEqualTo("COMPLETE");

        UUID rejected = uploadReceipt();
        HttpResponse<String> no = exchange("PATCH",
            appUrl("/v1/receipts/" + rejected + "/review"), Json.object("accepted", false),
            "Authorization", bearer());
        assertThat(no.statusCode()).isEqualTo(200);
        assertThat(Json.data(no.body()).path("status").asString()).isEqualTo("FAILED");

        // The image is the evidence and the extraction was only ever a convenience: a rejected
        // review must not take the file with it.
        assertThat(get(appUrl("/v1/receipts/" + rejected + "/download"), member.accessToken())
            .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a review body with no 'accepted' is 400 rather than defaulting to a rejection")
    void aReviewWithNoVerdictIs400() {
        UUID id = uploadReceipt();

        // `accepted` is required by the contract, so the request validator refuses an empty body
        // before the server sees it. The opt-out is request-only. What this proves is that the
        // SERVER refuses it too — a primitive boolean would have defaulted a missing field to
        // false and FAILED a receipt the member never rejected, which is why the DTO boxes it.
        HttpResponse<String> response = withoutRequestValidation(
            "an empty review body violates the contract's own required:[accepted]; no generated "
                + "client can send it, and the point is that the server does not default it",
            () -> exchange("PATCH", appUrl("/v1/receipts/" + id + "/review"), "{}",
                "Authorization", bearer()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("both PATCHes are 409 conflict while the pipeline is still running, and change nothing")
    void aReceiptStillInThePipelineRefusesEveryMutation() {
        UUID id = uploadReceipt();
        // The AI-off path lands on COMPLETE immediately, so the only way to observe a receipt
        // mid-pipeline is to put it back into one — the same JDBC technique ReceiptOrphanSweepIT
        // uses to make a receipt look stuck. This is the state a member reaches by polling
        // /status on an install that DOES have a vision model and patching before it finishes.
        jdbcTemplate.update("UPDATE receipt SET status = 'PARSING', review_required = true, "
            + "extracted_merchant = NULL WHERE id = ?", id);

        HttpResponse<String> patch = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("extractedMerchant", "Written while the pipeline was running"),
            "Authorization", bearer());
        assertThat(patch.statusCode())
            .as("the contract declares 409 on this operation for exactly this state")
            .isEqualTo(409);
        assertThat(Json.errorCode(patch.body())).isEqualTo("conflict");

        HttpResponse<String> review = exchange("PATCH",
            appUrl("/v1/receipts/" + id + "/review"), Json.object("accepted", true),
            "Authorization", bearer());
        assertThat(review.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(review.body())).isEqualTo("conflict");

        // Refused means refused: neither mutation may have been applied on its way to the 409, and
        // the receipt is still where the pipeline left it.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT extracted_merchant FROM receipt WHERE id = ?", String.class, id)).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT review_required FROM receipt WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM receipt WHERE id = ?", String.class, id)).isEqualTo("PARSING");

        // Left terminal so the hourly sweep has nothing to re-enqueue after this class.
        jdbcTemplate.update("UPDATE receipt SET status = 'COMPLETE' WHERE id = ?", id);
    }

    /** A manual transaction on a MANUAL account, which is the only kind that accepts one. */
    private UUID createManualTransaction() {
        HttpResponse<String> account = postJson(appUrl("/v1/accounts"),
            Json.object("displayName", "Wallet " + UUID.randomUUID(), "sourceKind", "MANUAL",
                "accountType", "OTHER", "currency", "CAD"),
            "Authorization", bearer());
        assertThat(account.statusCode())
            .as("creating a manual account failed: %s", account.body())
            .isEqualTo(201);
        String accountId = Json.data(account.body()).path("id").asString();

        HttpResponse<String> transaction = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", "12.34", "type", "DEBIT",
                "date", LocalDate.of(2026, 6, 5).toString(), "merchant", "Blue Bottle Coffee"),
            "Authorization", bearer());
        assertThat(transaction.statusCode())
            .as("creating a manual transaction failed: %s", transaction.body())
            .isEqualTo(201);
        return UUID.fromString(Json.data(transaction.body()).path("id").asString());
    }
}
