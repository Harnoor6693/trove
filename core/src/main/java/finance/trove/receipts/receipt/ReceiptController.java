package finance.trove.receipts.receipt;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.common.exception.ValidationException;
import finance.trove.receipts.receipt.dto.ReceiptPatchRequest;
import finance.trove.receipts.receipt.dto.ReceiptResponse;
import finance.trove.receipts.receipt.dto.ReceiptStatusResponse;
import finance.trove.receipts.receipt.dto.ReceiptSummaryResponse;
import finance.trove.receipts.receipt.dto.ReceiptReviewRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's receipts (§4.10).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, a body or a
 * header. The scope is {@code householdId}: a receipt is shared ledger data, so a household mate
 * sees, edits and downloads one somebody else uploaded, and another household's id is
 * <strong>404, never 403</strong> (§9 risk 2).
 *
 * <p>Every JSON body is the {@code ApiResponse} envelope. The two byte-streaming routes are the
 * exception and both are authenticated exactly like the rest — no presigned URL, no public path,
 * no token in a query string (RS-7 is the report service's version of that mistake). Both carry
 * {@code X-Content-Type-Options: nosniff}, because an image served from the application's own
 * origin whose content is not what its type claims would otherwise be a stored scripting vector
 * against this API (gap row 11).
 */
@RestController
@RequestMapping("/v1/receipts")
public class ReceiptController {

    /** Matches the contract's shared {@code LimitQuery} parameter. */
    static final int DEFAULT_LIMIT = 50;

    /** Matches the contract's shared {@code LimitQuery} parameter. */
    static final int MAX_LIMIT = 200;

    private final ReceiptService receipts;
    private final ReceiptFileService files;

    public ReceiptController(ReceiptService receipts, ReceiptFileService files) {
        this.receipts = receipts;
        this.files = files;
    }

    /**
     * {@code GET /v1/receipts} — one page of the household's receipts, newest first.
     *
     * <p>Paged, and the page is the envelope's {@code data} with its totals in {@code meta}. The
     * mobile client currently expects a bare array and is wrong on both counts; step 21b
     * regenerates its types. An unbounded receipts list is defect RS-9 in another costume.
     */
    @GetMapping
    public ApiResponse<List<ReceiptSummaryResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer limit) {

        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw new ValidationException("page must be >= 0, got " + resolvedPage);
        }
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
            throw new ValidationException(
                "limit must be between 1 and " + MAX_LIMIT + ", got " + resolvedLimit);
        }

        PagedResult<ReceiptSummaryResponse> result =
            receipts.list(principal.getHouseholdId(), resolvedPage, resolvedLimit);
        return ApiResponse.paged(result.content(), PaginationMeta.from(result));
    }

    /** {@code GET /v1/receipts/{id}} — the whole row, with its line items and thumbnail path. */
    @GetMapping("/{id}")
    public ApiResponse<ReceiptResponse> get(@AuthenticationPrincipal TroveUserDetails principal,
                                            @PathVariable UUID id) {
        return ApiResponse.ok(ReceiptResponse.of(receipts.get(principal.getHouseholdId(), id)));
    }

    /** {@code GET /v1/receipts/{id}/status} — the cheap poll target. */
    @GetMapping("/{id}/status")
    public ApiResponse<ReceiptStatusResponse> status(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            ReceiptStatusResponse.of(receipts.get(principal.getHouseholdId(), id)));
    }

    /** {@code PATCH /v1/receipts/{id}} — manual fields, and the transaction link. */
    @PatchMapping("/{id}")
    public ApiResponse<ReceiptResponse> patch(@AuthenticationPrincipal TroveUserDetails principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody ReceiptPatchRequest request) {
        return ApiResponse.ok(
            ReceiptResponse.of(receipts.patch(principal.getHouseholdId(), id, request)));
    }

    /** {@code PATCH /v1/receipts/{id}/review} — accept or reject the extracted fields. */
    @PatchMapping("/{id}/review")
    public ApiResponse<ReceiptResponse> review(@AuthenticationPrincipal TroveUserDetails principal,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody ReceiptReviewRequest request) {
        return ApiResponse.ok(ReceiptResponse.of(
            receipts.review(principal.getHouseholdId(), id, request.accepted())));
    }

    /** {@code DELETE /v1/receipts/{id}} — the row, its image rows and the files behind them. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        receipts.delete(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }

    /**
     * {@code GET /v1/receipts/{id}/download} — the stored image.
     *
     * <p>{@code Content-Disposition: attachment} with a filename built from the receipt's own id,
     * the stored MIME type, and {@code nosniff}. The stored type is always {@code image/jpeg}
     * because the upload re-encodes everything it accepts, which is why the contract declares
     * exactly one media type for this operation.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal TroveUserDetails principal,
                                           @PathVariable UUID id) {
        ReceiptFileService.StoredFile file =
            files.download(principal.getHouseholdId(), id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.filename() + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.content());
    }

    /**
     * {@code GET /v1/receipts/{id}/thumbnail} — the small image a list view renders.
     *
     * <p><strong>Authenticated on purpose</strong> (scope item 4): this is a photograph of the
     * household's spending, and an unauthenticated image path would be a way to read it without a
     * session. A client fetches the bytes with its own credentials rather than handing the path to
     * an image component — which the mobile client's own type already documents.
     *
     * <p>No {@code Content-Disposition}: a thumbnail is meant to be rendered, not saved.
     * {@code nosniff} still applies, for the reason the download states.
     */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@AuthenticationPrincipal TroveUserDetails principal,
                                            @PathVariable UUID id) {
        ReceiptFileService.StoredFile file = files.thumbnail(principal.getHouseholdId(), id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType()))
            .header("X-Content-Type-Options", "nosniff")
            .body(file.content());
    }
}
