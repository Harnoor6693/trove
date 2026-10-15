package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.storage.LocalDiskStorage;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deleting a receipt takes its rows <strong>and its files</strong>
 * (collapse step 14 scope item 6; gap row 30).
 *
 * <p>The file assertions are the point. A row whose JPEG is still on the volume is unreachable
 * from every route and still consumes the household's quota — gap row 30's unbounded disk growth,
 * one receipt at a time — and no test that only reads the database would notice.
 */
class ReceiptDeletionIT extends AuthFlowSupport {

    @Autowired
    private LocalDiskStorage storage;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "receipt-delete");
    }

    private String bearer() {
        return "Bearer " + member.accessToken();
    }

    private UUID upload() {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(1_000, 800))),
            "Authorization", bearer());
        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);
        return UUID.fromString(Json.data(response.body()).path("id").asString());
    }

    private List<String> keysOf(UUID receiptId) {
        return jdbcTemplate.queryForList(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ?", String.class, receiptId);
    }

    @Test
    @DisplayName("the row, its image rows and the files on disk all go")
    void deleteRemovesRowsAndFiles() {
        UUID id = upload();
        List<String> keys = keysOf(id);
        assertThat(keys).hasSize(2);
        assertThat(keys).allSatisfy(key -> assertThat(storage.exists(key)).isTrue());

        HttpResponse<String> response =
            delete(appUrl("/v1/receipts/" + id), null, "Authorization", bearer());

        assertThat(response.statusCode())
            .as("delete failed: %s", response.body())
            .isEqualTo(200);
        assertThat(Json.parse(response.body()).path("success").asBoolean()).isTrue();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt_image WHERE receipt_id = ?", Integer.class, id))
            .isZero();
        for (String key : keys) {
            assertThat(storage.exists(key))
                .as("an orphaned file on the volume is gap row 30's unbounded disk growth: %s",
                    key)
                .isFalse();
        }
    }

    @Test
    @DisplayName("the line items go with the receipt")
    void deleteRemovesLineItems() {
        UUID id = upload();
        // The AI-off path writes no line items, so one is inserted directly: what is under test
        // here is the cascade, not the extraction that would have produced them.
        jdbcTemplate.update("INSERT INTO line_item (receipt_id, description, quantity, "
            + "unit_price, total_price) VALUES (?, 'Latte', 1, 5.75, 5.75)", id);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM line_item WHERE receipt_id = ?", Integer.class, id)).isOne();

        delete(appUrl("/v1/receipts/" + id), null, "Authorization", bearer());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM line_item WHERE receipt_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("deleting a linked receipt clears the transaction's receiptId but keeps the row")
    void deleteClearsTheOtherHalfOfTheLink() {
        UUID id = upload();
        UUID transactionId = createManualTransaction();

        HttpResponse<String> linked = exchange("PATCH", appUrl("/v1/receipts/" + id),
            Json.object("matchedTransactionId", transactionId.toString()),
            "Authorization", bearer());
        assertThat(linked.statusCode()).isEqualTo(200);

        delete(appUrl("/v1/receipts/" + id), null, "Authorization", bearer());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM transaction WHERE id = ?", Integer.class, transactionId))
            .as("deleting a receipt must not delete the transaction it was attached to")
            .isOne();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT receipt_id FROM transaction WHERE id = ?", UUID.class, transactionId))
            .isNull();
    }

    @Test
    @DisplayName("a deleted receipt is 404 on every route afterwards")
    void aDeletedReceiptIsGone() {
        UUID id = upload();
        delete(appUrl("/v1/receipts/" + id), null, "Authorization", bearer());

        for (String path : List.of("", "/status", "/download", "/thumbnail")) {
            assertThat(get(appUrl("/v1/receipts/" + id + path), member.accessToken()).statusCode())
                .as("GET /v1/receipts/{id}%s after deletion", path)
                .isEqualTo(404);
        }
        assertThat(delete(appUrl("/v1/receipts/" + id), null, "Authorization", bearer())
            .statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("an anonymous delete is 401 and removes nothing")
    void anonymousDeleteIs401() {
        UUID id = upload();

        assertThat(delete(appUrl("/v1/receipts/" + id), null).statusCode()).isEqualTo(401);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt WHERE id = ?", Integer.class, id)).isOne();
    }

    private UUID createManualTransaction() {
        HttpResponse<String> account = postJson(appUrl("/v1/accounts"),
            Json.object("displayName", "Wallet " + UUID.randomUUID(), "sourceKind", "MANUAL",
                "accountType", "OTHER", "currency", "CAD"),
            "Authorization", bearer());
        assertThat(account.statusCode())
            .as("creating a manual account failed: %s", account.body())
            .isEqualTo(201);

        HttpResponse<String> transaction = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", Json.data(account.body()).path("id").asString(),
                "amount", "12.34", "type", "DEBIT",
                "date", LocalDate.of(2026, 6, 5).toString(), "merchant", "Blue Bottle Coffee"),
            "Authorization", bearer());
        assertThat(transaction.statusCode())
            .as("creating a manual transaction failed: %s", transaction.body())
            .isEqualTo(201);
        return UUID.fromString(Json.data(transaction.body()).path("id").asString());
    }
}
