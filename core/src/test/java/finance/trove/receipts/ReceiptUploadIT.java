package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The upload route end to end: the 202, the two stored files, the per-file 413, and the real
 * quota accounting (collapse step 14 scope item 10).
 *
 * <p><strong>What the quota case here does and does not prove.</strong> The refusal itself — used
 * plus incoming over the allowance — is asserted in {@code ReceiptUploadGauntletTest} against a
 * stubbed {@code LocalDiskStorage.usage}, because the allowance is 2 GB and no integration test
 * can fill it, and it cannot be lowered for one class either: {@code BaseIT} shares ONE Spring
 * context across the whole suite and a {@code @TestPropertySource} here would fork a second one,
 * which is banned. What a mock cannot prove, and what is proved here, is the half underneath it:
 * that {@code usage(RECEIPTS, householdId)} really counts the bytes an upload just wrote, that a
 * household mate's upload counts against the <em>same</em> allowance, and that nothing is
 * accounted per user — which is the deviation from gap row 11's "per-user" wording this step
 * relies on.
 *
 * <p>The three things that can only be proved here rather than in a unit test are this class's
 * point: the two {@code receipt_image} rows really land in Postgres, the two files really exist on
 * the data volume under keys {@code LocalDiskStorage} generated, and the whole request and response
 * really conform to {@code openapi/trove-core.yaml} — which {@code BaseIT.send} checks on every
 * call here for free.
 *
 * <p>No AI is configured for any member in the integration suite, so every receipt here takes the
 * decided degradation: {@code COMPLETE} with {@code reviewRequired}. That is the ordinary install
 * and it is what {@code ReceiptManualFlowIT} follows through.
 */
class ReceiptUploadIT extends AuthFlowSupport {

    @Autowired
    private LocalDiskStorage storage;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "receipt-upload");
    }

    private HttpResponse<String> upload(byte[] content, String filename, String contentType,
                                        String source) {
        List<Part> parts = source == null
            ? List.of(Part.file("file", filename, contentType, content))
            : List.of(Part.file("file", filename, contentType, content),
                Part.field("source", source));
        return postMultipart(appUrl("/v1/receipts/upload"), parts,
            "Authorization", "Bearer " + member.accessToken());
    }

    @Test
    @DisplayName("the quota really counts this household's bytes, and a mate's count against them")
    void quotaAccountingIsRealAndPerHousehold() {
        long before = storage.usage(StorageArea.RECEIPTS, member.householdId());

        HttpResponse<String> mine =
            upload(ReceiptImageFixtures.jpeg(1_000, 1_000), "receipt.jpg", "image/jpeg", null);
        assertThat(mine.statusCode()).isEqualTo(202);
        long afterMine = storage.usage(StorageArea.RECEIPTS, member.householdId());
        assertThat(afterMine)
            .as("the stored image and its thumbnail are what consume the allowance")
            .isGreaterThan(before);

        // A household mate, invited into the SAME household by the same admin.
        Session mate = inviteAndRegister(loginBootstrapAdmin(), "receipt-quota-mate");
        assertThat(mate.householdId()).isEqualTo(member.householdId());
        HttpResponse<String> theirs = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "mate.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(1_000, 1_000))),
            "Authorization", "Bearer " + mate.accessToken());
        assertThat(theirs.statusCode()).isEqualTo(202);

        long afterTheirs = storage.usage(StorageArea.RECEIPTS, member.householdId());
        assertThat(afterTheirs)
            .as("gap row 11 words the quota as per-user; the disk is shared and the ledger is "
                + "shared, so a mate's receipt spends the household's allowance")
            .isGreaterThan(afterMine);

        // And nothing is accounted per user: the uploader's own id names no directory at all.
        assertThat(storage.usage(StorageArea.RECEIPTS, member.userId()))
            .as("the second path segment of every receipts key is the HOUSEHOLD id")
            .isZero();
        assertThat(storage.usage(StorageArea.RECEIPTS, mate.userId())).isZero();
    }

    @Test
    @DisplayName("an upload answers 202 with {id, status, createdAt} and nothing else")
    void uploadAnswers202WithTheStatedShape() {
        HttpResponse<String> response =
            upload(ReceiptImageFixtures.jpeg(1_200, 900), "receipt.jpg", "image/jpeg", "SCAN");

        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);

        JsonNode data = Json.data(response.body());
        assertThat(data.path("id").asString()).isNotBlank();
        assertThat(data.path("createdAt").asString()).isNotBlank();
        // §4.10 states this shape explicitly because neither client's expectation matched it:
        // the web client wanted {jobId, status} and the mobile one wanted a whole receipt.
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("id", "status", "createdAt");
    }

    @Test
    @DisplayName("with no vision model the receipt is COMPLETE and reviewRequired, not FAILED")
    void withoutAiTheReceiptIsCompleteAndAwaitingReview() {
        HttpResponse<String> response =
            upload(ReceiptImageFixtures.jpeg(800, 600), "receipt.jpg", "image/jpeg", null);
        assertThat(response.statusCode()).isEqualTo(202);
        String id = Json.data(response.body()).path("id").asString();

        // The decided degradation (owner decision 2026-09-03). It is the ORDINARY outcome on an
        // install with no AI, not an error, and the status route says so.
        assertThat(Json.data(response.body()).path("status").asString()).isEqualTo("COMPLETE");

        HttpResponse<String> status = get(appUrl("/v1/receipts/" + id + "/status"),
            member.accessToken());
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(Json.data(status.body()).path("status").asString()).isEqualTo("COMPLETE");
        assertThat(Json.data(status.body()).path("reviewRequired").asBoolean()).isTrue();
        assertThat(Json.data(status.body()).path("confidence").isNull()).isTrue();
    }

    @Test
    @DisplayName("two receipt_image rows land, and both files really exist on the volume")
    void twoImageRowsAndTwoFilesExist() {
        HttpResponse<String> response =
            upload(ReceiptImageFixtures.jpeg(2_400, 1_600), "receipt.jpg", "image/jpeg", null);
        UUID id = UUID.fromString(Json.data(response.body()).path("id").asString());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT kind, page_no, file_key, mime_type, size_bytes, sha256 FROM receipt_image "
                + "WHERE receipt_id = ? ORDER BY kind", id);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("kind"))
            .containsExactly("ORIGINAL", "THUMBNAIL");
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("file_key");
            // The key is a LocalDiskStorage key, not a MinIO object name and not a path: three
            // segments, area / household / uuid.ext, generated server-side.
            assertThat(key).startsWith("receipts/" + member.householdId() + "/");
            assertThat(key).endsWith(".jpg");
            assertThat(key.split("/")).hasSize(3);
            assertThat(row.get("mime_type")).isEqualTo("image/jpeg");
            assertThat((Long) row.get("size_bytes")).isPositive();
            assertThat((String) row.get("sha256")).hasSize(64);
            assertThat(row.get("page_no")).isEqualTo(0);

            assertThat(storage.exists(key))
                .as("the file behind %s must really be on the volume", row.get("kind"))
                .isTrue();
            assertThat(storage.size(key)).isEqualTo((Long) row.get("size_bytes"));
        }
    }

    @Test
    @DisplayName("the stored bytes are a JPEG this server encoded, not the bytes that were sent")
    void theStoredBytesAreARecodedJpeg() {
        byte[] sent = ReceiptImageFixtures.jpegWithGpsExif(2_400, 1_600);
        assertThat(ReceiptImageFixtures.hasApp1Segment(sent)).isTrue();

        HttpResponse<String> response = upload(sent, "receipt.jpg", "image/jpeg", null);
        UUID id = UUID.fromString(Json.data(response.body()).path("id").asString());

        String key = jdbcTemplate.queryForObject(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ? AND kind = 'ORIGINAL'",
            String.class, id);
        byte[] stored = storage.readAllBytes(key);

        assertThat(stored).isNotEqualTo(sent);
        assertThat(ReceiptImageFixtures.hasApp1Segment(stored))
            .as("the GPS coordinates a phone writes into a receipt photograph must not reach the "
                + "volume; the re-encode strips them by construction")
            .isFalse();
        assertThat(ReceiptImageFixtures.containsExifIdentifier(stored)).isFalse();
    }

    @Test
    @DisplayName("a PNG is accepted and stored as a JPEG")
    void aPngIsStoredAsAJpeg() {
        HttpResponse<String> response =
            upload(ReceiptImageFixtures.png(600, 400), "receipt.png", "image/png", "UPLOAD");
        assertThat(response.statusCode()).isEqualTo(202);
        UUID id = UUID.fromString(Json.data(response.body()).path("id").asString());

        assertThat(jdbcTemplate.queryForList(
            "SELECT mime_type FROM receipt_image WHERE receipt_id = ?", String.class, id))
            .containsOnly("image/jpeg");
    }

    @Test
    @DisplayName("a PNG declared as a JPEG is 400, and nothing is stored")
    void mismatchedMagicBytesAre400() {
        HttpResponse<String> response =
            upload(ReceiptImageFixtures.png(100, 100), "receipt.jpg", "image/jpeg", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("a 60000x60000 PNG header is 400 and nothing decodes it")
    void aDimensionBombIs400() {
        HttpResponse<String> response = upload(ReceiptImageFixtures.pngDeclaring(60_000, 60_000),
            "bomb.png", "image/png", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("HEIC is refused server-side: the client converts")
    void heicIs400() {
        byte[] heic = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        HttpResponse<String> response = upload(heic, "IMG_0001.HEIC", "image/heic", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("an over-size upload is 413 upload-too-large")
    void anOversizeUploadIs413() {
        // Larger than trove.receipt.max-file-bytes (10 MB) and inside the container's 20 MB
        // multipart cap, so it is this operation's own ceiling that answers rather than Tomcat's.
        byte[] oversize = new byte[11 * 1024 * 1024];
        oversize[0] = (byte) 0xFF;
        oversize[1] = (byte) 0xD8;
        oversize[2] = (byte) 0xFF;

        HttpResponse<String> response = upload(oversize, "big.jpg", "image/jpeg", null);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(Json.errorCode(response.body())).isEqualTo("upload-too-large");
        // The message names bytes only — never the filename the client chose and never a storage
        // key. ("receipts/" on its own would match the `instance` field, which legitimately
        // carries the matched ROUTE TEMPLATE; a key is that prefix followed by the household id.)
        assertThat(response.body())
            .doesNotContain("big.jpg")
            .doesNotContain("receipts/" + member.householdId());
    }

    @Test
    @DisplayName("an empty file part is 400")
    void anEmptyPartIs400() {
        HttpResponse<String> response = upload(new byte[0], "empty.jpg", "image/jpeg", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("an anonymous upload is 401 and stores nothing")
    void anonymousUploadIs401() {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "r.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(100, 100))));

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a filename of ../../etc/passwd is stored under a generated key like any other")
    void aTraversalFilenameChangesNothing() {
        HttpResponse<String> response = upload(ReceiptImageFixtures.jpeg(200, 200),
            "../../etc/passwd", "image/jpeg", null);
        assertThat(response.statusCode()).isEqualTo(202);
        UUID id = UUID.fromString(Json.data(response.body()).path("id").asString());

        for (String key : jdbcTemplate.queryForList(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ?", String.class, id)) {
            assertThat(key).doesNotContain("..").doesNotContain("passwd");
            assertThat(key).startsWith("receipts/" + member.householdId() + "/");
        }
    }

    @Test
    @DisplayName("the list route is the envelope, is paged, and carries its totals in meta")
    void theListIsPagedInsideTheEnvelope() {
        upload(ReceiptImageFixtures.jpeg(300, 300), "one.jpg", "image/jpeg", null);
        upload(ReceiptImageFixtures.jpeg(300, 300), "two.jpg", "image/jpeg", null);

        HttpResponse<String> response = get(appUrl("/v1/receipts?page=0&limit=1"),
            member.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = Json.parse(response.body());
        // The mobile client expects a bare array from this route and is wrong on both counts:
        // the envelope wins and the list is paged. Step 21b regenerates its types.
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").isArray()).isTrue();
        assertThat(Json.elements(body.path("data"))).hasSize(1);
        assertThat(body.path("meta").path("limit").asInt()).isEqualTo(1);
        assertThat(body.path("meta").path("total").asLong()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a limit above the cap is 400 rather than being clamped")
    void anOutOfRangeLimitIs400() {
        // The contract's shared LimitQuery caps `limit` at 200, so the request validator refuses
        // 5000 before it ever reaches the server — which is a generated client's behaviour, not
        // this server's. The opt-out is request-only and the RESPONSE is still validated, so what
        // this test proves is what it says: the SERVER refuses it too, rather than clamping it to
        // 200 and quietly answering a question nobody asked.
        HttpResponse<String> response = withoutRequestValidation(
            "limit=5000 violates the contract's own LimitQuery maximum; no generated client can "
                + "send it, and the point is that the server refuses it rather than clamping",
            () -> get(appUrl("/v1/receipts?limit=5000"), member.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }
}
