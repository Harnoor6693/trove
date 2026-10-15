package finance.trove.app.audit;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/audit} — the household's request log ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §4.2; gap P2-17, "D7 fixed").
 *
 * <p>The table existed in the pre-collapse gateway and was written to on every request; nothing
 * ever read it, and no route exposed it. That is what §4.2 records as D7: an audit log nobody can
 * read is a cost with no benefit.
 *
 * <p><strong>Not under {@code /v1/admin}, and still ADMIN.</strong> §4.2 and §5.1 both give the
 * path as {@code /v1/audit}, and both name it alongside {@code /v1/admin/**} in the role guard.
 * The path stays as specified because the clients are generated from the document; the guard is
 * therefore the {@code @PreAuthorize} here plus the explicit matcher {@code SecurityConfig} adds
 * for this one path. {@code OpenApiRouteDriftIT} holds the document's
 * {@code x-trove-required-role: ADMIN} against both mechanisms, so dropping either one fails the
 * build.
 *
 * <p><strong>Scoping is the principal's household, never a parameter.</strong> There is no
 * household id on this route to tamper with, and {@link AuditLogQueryService} has no method that
 * reads the table without one.
 */
@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditLogQueryService auditLog;

    public AuditController(AuditLogQueryService auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * One page of the caller's household's audit log, newest first.
     *
     * <p>The bounds are enforced rather than clamped: a client that computed {@code page=-1} or
     * asked for ten thousand rows is told, with a {@code fieldErrors} entry naming the parameter,
     * instead of being shown page zero and left to wonder. {@code @Validated} on the class is what
     * turns these annotations into a {@code ConstraintViolationException}, which
     * {@code GlobalExceptionHandler} renders as the 400 {@code validation-error} the spec's
     * {@code QueryRejected} response describes.
     */
    @GetMapping("/v1/audit")
    public ApiResponse<List<AuditLogEntry>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "" + AuditLogQueryService.DEFAULT_LIMIT)
        @Min(1) @Max(AuditLogQueryService.MAX_LIMIT) int limit) {

        PagedResult<AuditLogEntry> result =
            auditLog.page(principal.getHouseholdId(), page, limit);
        return ApiResponse.paged(result.content(), PaginationMeta.from(result));
    }
}
