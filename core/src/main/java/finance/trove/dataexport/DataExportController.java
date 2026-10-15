package finance.trove.dataexport;

import finance.trove.auth.dto.StepUpRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.common.exception.ValidationException;
import finance.trove.dataexport.dto.DataExportRunResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's full data exports ({@code /v1/me/export}; collapse step 18, gap row 16).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, a body or a
 * header ({@code SourceConventionTest} bans {@code X-User-Id} outright). <strong>Here "somebody
 * else" includes a household mate</strong>: a run belonging to another member answers 404, which
 * is the opposite of every ledger route in the tree and is the point — an archive is one person's
 * whole financial life, exactly as a tax filing is individual. The transactions inside the archive
 * still come from the shared household ledger, and the operation description says so.
 *
 * <p>Every JSON body is the {@code ApiResponse} envelope. The download is the one exception and
 * streams bytes.
 */
@RestController
@RequestMapping("/v1/me/export")
public class DataExportController {

    /** Matches the contract's shared {@code LimitQuery} parameter. */
    static final int DEFAULT_LIMIT = 50;

    /** Matches the contract's shared {@code LimitQuery} parameter. */
    static final int MAX_LIMIT = 200;

    private final DataExportService exports;

    public DataExportController(DataExportService exports) {
        this.exports = exports;
    }

    /** {@code GET /v1/me/export} — this member's runs, newest first, bounded. */
    @GetMapping
    public ApiResponse<List<DataExportRunResponse>> list(
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

        PagedResult<DataExportRun> result =
            exports.list(principal.getUserId(), resolvedPage, resolvedLimit);
        return ApiResponse.paged(
            result.content().stream().map(DataExportRunResponse::of).toList(),
            PaginationMeta.from(result));
    }

    /**
     * {@code POST /v1/me/export} — step up, record the run, hand the work to the executor.
     *
     * <p>202, not 201: the row exists, but the thing the caller asked for — an archive — does not
     * yet. The same answer the tax export and the receipt upload give, for the same reason.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataExportRunResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody StepUpRequest request) {

        DataExportRun run = exports.request(principal.getUserId(), principal.getHouseholdId(),
            request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.ok(DataExportRunResponse.of(run)));
    }

    /** {@code GET /v1/me/export/{id}} — the poll target. */
    @GetMapping("/{id}")
    public ApiResponse<DataExportRunResponse> get(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(DataExportRunResponse.of(exports.get(principal.getUserId(), id)));
    }

    /**
     * {@code GET /v1/me/export/{id}/download} — the archive.
     *
     * <p>{@code application/zip}, always the {@code attachment} form with a server-built filename,
     * and {@code X-Content-Type-Options: nosniff} so that a browser cannot decide the bytes are
     * really an HTML document. Nothing is presigned and no storage URL is handed out: the bytes
     * come off this server's own data volume through {@code LocalDiskStorage}, which re-checks the
     * stored key against its root on every read.
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal TroveUserDetails principal,
                                           @PathVariable UUID id) {
        DataExportService.DownloadableArchive archive =
            exports.download(principal.getUserId(), id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + archive.filename() + "\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(archive.content());
    }
}
