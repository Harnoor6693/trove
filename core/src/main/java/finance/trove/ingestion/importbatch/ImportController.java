package finance.trove.ingestion.importbatch;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.dto.SyncLogResponse;
import finance.trove.ingestion.importbatch.dto.ImportCommitRequest;
import finance.trove.ingestion.importbatch.dto.ImportPreviewResponse;
import java.io.IOException;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading, previewing, committing and undoing a CSV or OFX import
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>Identity is {@link TroveUserDetails#getHouseholdId()} and
 * {@link TroveUserDetails#getUserId()}, both from signed claims. Nothing here reads an identity
 * from a path, a body or a header, and a batch belonging to another household is 404 rather than
 * 403 — a 403 would confirm the row exists, which over enough probes is an enumeration oracle for
 * another household's ids.
 *
 * <p><strong>Four routes and no fifth.</strong> There is deliberately no "update the mapping"
 * route: the member's adjustment travels with the commit that uses it (plan §6, "user adjusts,
 * POST commit"), which is one fewer stored state and one fewer window in which a batch's mapping
 * and its preview disagree.
 */
@RestController
@RequestMapping("/v1/imports")
public class ImportController {

    private final ImportService imports;
    private final ImportFileValidator validator;

    public ImportController(ImportService imports, ImportFileValidator validator) {
        this.imports = imports;
        this.validator = validator;
    }

    /**
     * {@code POST /v1/imports} — uploads a file and answers with a preview.
     *
     * <p><strong>Nothing is written to the ledger.</strong> The batch is created in status
     * {@code PREVIEW}; {@code POST /v1/imports/{id}/commit} is what inserts anything.
     *
     * <p>The body is read through {@link ImportFileValidator#readWithinLimit}, which checks the
     * part's declared size before reading a byte of it — so an over-size upload answers 413
     * {@code upload-too-large} without being buffered and without being stored.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ImportPreviewResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "accountId", required = false) UUID accountId,
        @RequestParam(name = "sourceKind", required = false) SourceKind sourceKind) {

        byte[] content = read(file);
        ImportPreviewResponse preview = imports.create(principal.getHouseholdId(),
            principal.getUserId(), content, file.getContentType(), file.getOriginalFilename(),
            file.getSize(), accountId, sourceKind);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(preview));
    }

    /** {@code GET /v1/imports/{id}/preview} — re-reads an uncommitted batch. */
    @GetMapping("/{id}/preview")
    public ApiResponse<ImportPreviewResponse> preview(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(imports.preview(principal.getHouseholdId(), id));
    }

    /**
     * {@code POST /v1/imports/{id}/commit} — writes the rows.
     *
     * <p>The body is optional and supersedes the detected mapping. 409 {@code conflict} when the
     * batch is not in {@code PREVIEW}: committing twice is a mistake, and a 200 would hide it.
     */
    @PostMapping("/{id}/commit")
    public ApiResponse<SyncLogResponse> commit(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @RequestBody(required = false) ImportCommitRequest request) {
        return ApiResponse.ok(SyncLogResponse.of(imports.commit(principal.getHouseholdId(),
            principal.getUserId(), id, request)));
    }

    /**
     * {@code DELETE /v1/imports/{id}} — removes the rows this batch inserted that nobody has
     * touched since, and marks the batch {@code UNDONE}.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> undo(@AuthenticationPrincipal TroveUserDetails principal,
                                  @PathVariable UUID id) {
        imports.undo(principal.getHouseholdId(), id);
        return ApiResponse.<Void>ok(null);
    }

    private byte[] read(MultipartFile file) {
        try {
            return validator.readWithinLimit(file.getInputStream(), file.getSize());
        } catch (IOException e) {
            throw new ValidationException("The uploaded file could not be read.");
        }
    }
}
