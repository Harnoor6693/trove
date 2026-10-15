package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.ImportSupport;
import finance.trove.app.support.Json;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ceilings an upload is held to ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6; gaps P2-11,
 * P2-12).
 *
 * <p>This is also the operation that finally makes 413 {@code upload-too-large} REACHABLE. Before
 * collapse step 7 the slug was defined and handled and no route in the API could emit it, which
 * the contract said in as many words; it now says the opposite, and this is what holds it to that.
 */
class ImportLimitsIT extends ImportSupport {

    private Session admin;
    private String accountId;

    @BeforeEach
    void setUpAnAccount() {
        admin = loginBootstrapAdmin();
        accountId = createAccount(admin, CSV, "Limits account " + UUID.randomUUID(), "CHECKING",
            "CAD");
    }

    @Test
    @DisplayName("An upload over the import ceiling is 413 upload-too-large and stores nothing")
    void anOverSizeUploadIsRefused() {
        byte[] oversize = oversizeCsv();

        var response = upload(admin, oversize, "huge.csv", "text/csv", accountId);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(Json.errorCode(response.body())).isEqualTo("upload-too-large");
        assertThat(Long.parseLong(String.valueOf(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM import_batch WHERE account_id = ?::uuid", Long.class,
            accountId))))
            .as("the size is checked against the part's DECLARED size before a byte is read, so "
                + "nothing is stored and no batch row is written")
            .isZero();
    }

    @Test
    @DisplayName("A content type this endpoint does not accept is refused")
    void aWrongContentTypeIsRefused() {
        var response = upload(admin, "Date,Description,Amount\n2026-09-01,A,-1.00\n"
            .getBytes(StandardCharsets.UTF_8), "statement.png", "image/png", accountId);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .contains("image/png");
    }

    @Test
    @DisplayName("A zip wrapper is refused by name, not handed to a parser")
    void aZipWrapperIsRefused() {
        byte[] zip = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00};

        var response = upload(admin, zip, "statement.zip", "application/octet-stream", accountId);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .contains("compressed archive");
    }

    @Test
    @DisplayName("An empty part is a 400, not a zero-row import")
    void anEmptyPartIsRefused() {
        var response = upload(admin, new byte[0], "empty.csv", "text/csv", accountId);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    /**
     * Just over {@code trove.import.max-file-bytes} and well under the container's multipart cap,
     * so the refusal comes from the import's own ceiling and the request reaches a handler at all.
     */
    private static byte[] oversizeCsv() {
        StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
        String row = "2026-09-01,PADDING PADDING PADDING PADDING PADDING PADDING,-1.00\n";
        while (csv.length() <= ImportLimits.DEFAULT_MAX_FILE_BYTES) {
            csv.append(row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
