package finance.trove.ai.insight;

import finance.trove.ai.insight.dto.InsightResponse;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/insights} — the caller's own weekly notes
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11).
 *
 * <p><strong>A safe GET.</strong> The source's equivalent returned undelivered notes and marked
 * them delivered as a side effect of reading, which makes a GET a write and makes "have I already
 * seen this" depend on who fetched the page last. Here {@code delivered} is set by whatever
 * actually delivers a note somewhere else — collapse step 11's notification channel — and reading
 * this page changes nothing.
 *
 * <p>Scoped to the principal, with no id anywhere on the route to tamper with. These rows are per
 * member, so there is nothing here a household mate can reach.
 */
@RestController
@Validated
public class InsightController {

    /**
     * The shared {@code LimitQuery} default. It is 50 here for the same reason it is 50 in
     * {@code ChatController}, {@code TaxExportController}, {@code TransactionQuery} and
     * {@code AuditLogQueryService}: {@code openapi/trove-core.yaml} documents one default for the
     * parameter every paged list reuses, and a route that quietly returns fewer rows than the
     * contract promises is a page a client cannot reason about. A member accumulates one note a
     * week, so 50 is a year of them.
     */
    public static final int DEFAULT_LIMIT = 50;

    /** Matches {@code LimitQuery.maximum} in {@code openapi/trove-core.yaml}. */
    public static final int MAX_LIMIT = 200;

    private final InsightService insights;

    public InsightController(InsightService insights) {
        this.insights = insights;
    }

    /**
     * One page of the caller's notes, newest first.
     *
     * <p>The bounds are enforced rather than clamped, as everywhere else on this API: a client that
     * computed {@code page=-1} is told, with a {@code fieldErrors} entry naming the parameter.
     */
    @GetMapping("/v1/insights")
    public ApiResponse<List<InsightResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit) {

        PagedResult<InsightResponse> result = insights.page(principal.getUserId(), page, limit);
        return ApiResponse.paged(result.content(), PaginationMeta.from(result));
    }
}
