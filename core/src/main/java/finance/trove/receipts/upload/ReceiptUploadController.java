package finance.trove.receipts.upload;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.dto.ReceiptUploadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code POST /v1/receipts/upload} (§4.10).
 *
 * <p>The two part names are the ones the mobile client already sends, {@code file} and
 * {@code source}, so no client changes for this operation. The identity comes from
 * {@link TroveUserDetails} and the {@code X-Trove-Province} header the source read is gone: a
 * client-supplied province deciding a tax calculation is exactly the class of input this collapse
 * removes, and the member's own {@code users.region} is read on the background thread instead.
 *
 * <p><strong>202, not 201.</strong> The row exists but the thing the caller wants — a receipt with
 * fields on it — does not yet, and on an install with no vision model it never will be by the
 * server. The body is {@code {id, status, createdAt}} and deliberately not a full receipt (§4.10
 * states the shape because neither client's expectation matched it).
 *
 * <p>413 is not handled here: {@code MaxUploadSizeExceededException} already maps to
 * {@code upload-too-large} in the merged {@code GlobalExceptionHandler} (§5 item 3 — do not add a
 * second handler), and {@link UploadTooLargeException} carries the same status and slug for the
 * two ceilings this operation enforces itself.
 *
 * <p>A separate controller from {@code ReceiptController} rather than a method on it, which is how
 * the source had it and is worth keeping: this is the only multipart route in the package and the
 * only one whose whole body is a security gauntlet.
 */
@RestController
public class ReceiptUploadController {

    private final ReceiptUploadService uploads;

    public ReceiptUploadController(ReceiptUploadService uploads) {
        this.uploads = uploads;
    }

    /**
     * Stores the image and starts, or deliberately skips, the pipeline.
     *
     * @param file   the image; JPEG or PNG, declared and actual
     * @param source {@code UPLOAD} or {@code SCAN}; absent or unrecognised is {@code UPLOAD}
     */
    @PostMapping(value = "/v1/receipts/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ReceiptUploadResponse>> upload(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "source", required = false) String source) {

        Receipt receipt = uploads.upload(principal.getHouseholdId(), principal.getUserId(), file,
            source);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(ReceiptUploadResponse.of(receipt)));
    }
}
