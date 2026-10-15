package finance.trove.taxexport;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.common.exception.ValidationException;
import finance.trove.taxexport.dto.CreateTaxExportRunRequest;
import finance.trove.taxexport.dto.TaxExportRunResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's tax-year export runs ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.12).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims. <strong>Here "somebody
 * else" includes a household mate</strong>: a run belonging to another member answers 404, which
 * is the opposite of every ledger route in the tree and is the point — a filing is individual
 * (§1.5, §6). The transactions inside the file still come from the shared household ledger.
 *
 * <p>Every JSON body is the {@code ApiResponse} envelope. The download is the one exception and
 * streams bytes; it is also the only non-JSON response on this API, which is why the contract
 * names its two media types explicitly.
 */
@RestController
@RequestMapping("/v1/tax-export/runs")
public class TaxExportController {

    /** Matches the contract's shared {@code LimitQuery} parameter. */
    static final int DEFAULT_LIMIT = 50;

    /** Matches the contract's shared {@code LimitQuery} parameter (RS-9: the source had none). */
    static final int MAX_LIMIT = 200;

    private final TaxExportService exports;

    public TaxExportController(TaxExportService exports) {
        this.exports = exports;
    }

    /** {@code GET /v1/tax-export/runs} — this member's runs, newest first, bounded (RS-9). */
    @GetMapping
    public ApiResponse<List<TaxExportRunResponse>> list(
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

        PagedResult<TaxExportRun> result =
            exports.list(principal.getUserId(), resolvedPage, resolvedLimit);
        return ApiResponse.paged(
            result.content().stream().map(TaxExportRunResponse::of).toList(),
            PaginationMeta.from(result));
    }

    /**
     * {@code POST /v1/tax-export/runs} — record the run and hand the work to the background
     * executor.
     *
     * <p>202, not 201: the row exists, but the thing the caller asked for — a file — does not yet.
     * The same answer collapse step 14's receipt upload gives, for the same reason.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TaxExportRunResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody CreateTaxExportRunRequest request) {

        TaxExportRun run = exports.request(principal.getUserId(), principal.getHouseholdId(),
            request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(TaxExportRunResponse.of(run)));
    }

    /** {@code GET /v1/tax-export/runs/{id}} — the poll target. */
    @GetMapping("/{id}")
    public ApiResponse<TaxExportRunResponse> get(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(TaxExportRunResponse.of(exports.get(principal.getUserId(), id)));
    }

    /** {@code DELETE /v1/tax-export/runs/{id}} — removes the row and the file together. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        exports.delete(principal.getUserId(), id);
        return ApiResponse.ok(null);
    }

    /**
     * {@code GET /v1/tax-export/runs/{id}/download} — the bytes.
     *
     * <p>The {@code Content-Type} is the run's own format and is one of exactly the two the
     * contract declares for this operation; {@code Content-Disposition} is always the
     * {@code attachment} form with a server-built filename, and {@code X-Content-Type-Options:
     * nosniff} keeps a browser from deciding a CSV is really an HTML document.
     *
     * <p>Nothing is presigned and no storage URL is handed out (RS-7): the bytes come off this
     * server's own data volume, through {@code LocalDiskStorage}, which re-checks the stored key
     * against its root on every read.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal TroveUserDetails principal,
                                           @PathVariable UUID id) {
        TaxExportService.DownloadableExport file = exports.download(principal.getUserId(), id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.format().contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.filename() + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(file.content());
    }
}
