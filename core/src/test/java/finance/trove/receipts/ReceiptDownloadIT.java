package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The two byte-streaming routes (collapse step 14 scope items 4 and 8; gap row 11).
 *
 * <p>Four properties, and each one is a defect somewhere else in the source tree if it is missing:
 * both routes are <strong>authenticated</strong> (RS-7 handed out presigned URLs that needed no
 * session), the download carries <strong>{@code Content-Disposition: attachment}</strong> and both
 * carry <strong>{@code nosniff}</strong> (an inline-served upload from the application's own origin
 * whose bytes are not its declared type is a stored scripting vector), a
 * <strong>household mate can read them</strong> (a receipt is shared ledger data), and
 * <strong>household B gets 404</strong>.
 *
 * <p>{@code @TestInstance(PER_CLASS)} and a {@code @BeforeAll} that uploads once: the fixture is
 * three sign-ins and an upload, and repeating it per method would spend the suite's shared login
 * limits for nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptDownloadIT extends AuthFlowSupport {

    private Session uploader;
    private Session mate;
    private Session outsider;
    private UUID receiptId;

    @BeforeAll
    void uploadOnce() {
        Session admin = loginBootstrapAdmin();
        uploader = inviteAndRegister(admin, "receipt-download-owner");
        mate = inviteAndRegister(admin, "receipt-download-mate");
        // Household B: a fresh registration with its own household, which is what the bootstrap
        // admin's invite flow cannot produce. AccessControlIT's own second household is built the
        // same way.
        outsider = createSecondHousehold();

        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(1_600, 1_200))),
            "Authorization", "Bearer " + uploader.accessToken());
        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);
        receiptId = UUID.fromString(Json.data(response.body()).path("id").asString());
    }

    @Test
    @DisplayName("the download is refused anonymously")
    void anonymousDownloadIs401() {
        assertThat(get(appUrl("/v1/receipts/" + receiptId + "/download")).statusCode())
            .isEqualTo(401);
    }

    @Test
    @DisplayName("the thumbnail is refused anonymously — it is a photograph of the ledger")
    void anonymousThumbnailIs401() {
        assertThat(get(appUrl("/v1/receipts/" + receiptId + "/thumbnail")).statusCode())
            .isEqualTo(401);
    }

    @Test
    @DisplayName("the download carries Content-Disposition: attachment, nosniff and image/jpeg")
    void theDownloadCarriesItsHeaders() {
        HttpResponse<String> response =
            get(appUrl("/v1/receipts/" + receiptId + "/download"), uploader.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
            .startsWith("attachment;")
            // Built from the receipt's own id, never from anything a client sent.
            .contains(receiptId.toString());
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow())
            .isEqualTo("nosniff");
        // Always JPEG: the upload re-encodes everything it accepts, which is why the contract
        // declares exactly one media type for this operation.
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("image/jpeg");
        assertThat(response.body()).isNotEmpty();
    }

    @Test
    @DisplayName("the thumbnail carries nosniff and no Content-Disposition")
    void theThumbnailCarriesItsHeaders() {
        HttpResponse<String> response =
            get(appUrl("/v1/receipts/" + receiptId + "/thumbnail"), uploader.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElseThrow())
            .isEqualTo("nosniff");
        assertThat(response.headers().firstValue("Content-Disposition"))
            .as("a thumbnail is meant to be rendered, not saved")
            .isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("image/jpeg");
    }

    @Test
    @DisplayName("the thumbnail is smaller than the download: it is a different stored file")
    void theThumbnailIsADifferentFile() {
        int full = get(appUrl("/v1/receipts/" + receiptId + "/download"), uploader.accessToken())
            .body().length();
        int thumbnail =
            get(appUrl("/v1/receipts/" + receiptId + "/thumbnail"), uploader.accessToken())
                .body().length();

        assertThat(thumbnail).isLessThan(full);
    }

    @Test
    @DisplayName("a household mate CAN download and see the thumbnail: a receipt is shared ledger")
    void aHouseholdMateCanRead() {
        assertThat(mate.householdId()).isEqualTo(uploader.householdId());

        assertThat(get(appUrl("/v1/receipts/" + receiptId), mate.accessToken()).statusCode())
            .isEqualTo(200);
        assertThat(get(appUrl("/v1/receipts/" + receiptId + "/download"), mate.accessToken())
            .statusCode())
            .as("user_id on a receipt is the uploader, not the scope — hiding a member's receipt "
                + "from their own household is the mistake this asserts against")
            .isEqualTo(200);
        assertThat(get(appUrl("/v1/receipts/" + receiptId + "/thumbnail"), mate.accessToken())
            .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("household B gets 404 on every route, never 403")
    void householdBGets404() {
        assertThat(outsider.householdId()).isNotEqualTo(uploader.householdId());

        for (String path : List.of("", "/status", "/download", "/thumbnail")) {
            HttpResponse<String> response =
                get(appUrl("/v1/receipts/" + receiptId + path), outsider.accessToken());
            assertThat(response.statusCode())
                .as("GET /v1/receipts/{id}%s from household B", path)
                .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("a receipt id that never existed is the same 404")
    void anUnknownIdIsTheSame404() {
        HttpResponse<String> response =
            get(appUrl("/v1/receipts/" + UUID.randomUUID() + "/download"),
                uploader.accessToken());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(response.body())).isEqualTo("not-found");
    }

    /**
     * Creates household B and an ADMIN invite into it directly, then redeems that invite through
     * the real endpoint.
     *
     * <p>The two inserts are the only fixture in this file, and they exist for the reason
     * {@code AccessControlIT#createSecondHousehold} gives: the application deliberately offers no
     * way to create a second household — one per install is the owner's tenancy decision — so
     * there is no endpoint to drive. The redemption itself goes through production code, which is
     * what makes household B's member as genuine as household A's.
     */
    private Session createSecondHousehold() {
        UUID householdB = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES ('Receipt household B', "
                + "false) RETURNING id", UUID.class);

        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdB, finance.trove.households.invites.InviteService.hash(token));

        return registerWithInvite(token, uniqueEmail("receipt-download-outsider"),
            MEMBER_PASSWORD);
    }
}
