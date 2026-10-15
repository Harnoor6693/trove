package finance.trove.taxexport;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.dto.ApiResponse;
import finance.trove.taxexport.dto.TaxLineMappingRequest;
import finance.trove.taxexport.dto.TaxLineMappingResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The category-to-tax-line map ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.12).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, a body or a
 * header. The map is <strong>per member</strong>: an override belongs to the member who wrote it
 * and a household mate never sees it, because a filing is individual (§1.5, §6). The categories it
 * is drawn over are still the household's, which is why the household id from the same principal
 * decides which categories are visible.
 */
@RestController
@RequestMapping("/v1/tax-export/lines")
public class TaxLineMappingController {

    private final TaxLineMappingService lines;

    public TaxLineMappingController(TaxLineMappingService lines) {
        this.lines = lines;
    }

    /**
     * {@code GET /v1/tax-export/lines} — every visible category with the caller's effective line.
     *
     * <p>{@code jurisdiction} defaults to the caller's own {@code users.country}, which is also the
     * jurisdiction stamped on every run they start, so the screen shows the lines the next export
     * will actually carry.
     */
    @GetMapping
    public ApiResponse<List<TaxLineMappingResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(required = false) Jurisdiction jurisdiction) {

        Jurisdiction resolved = jurisdiction != null
            ? jurisdiction
            : lines.defaultJurisdiction(principal.getUserId(), principal.getHouseholdId());
        return ApiResponse.ok(
            lines.listLines(principal.getUserId(), principal.getHouseholdId(), resolved));
    }

    /**
     * {@code PUT /v1/tax-export/lines/{categoryId}} — the caller's own override.
     *
     * <p>A category the caller cannot see is 404 and never 403, so the answer does not confirm
     * that another household's category id is real.
     */
    @PutMapping("/{categoryId}")
    public ApiResponse<TaxLineMappingResponse> upsert(
        @AuthenticationPrincipal TroveUserDetails principal,
        @PathVariable UUID categoryId,
        @Valid @RequestBody TaxLineMappingRequest request) {

        return ApiResponse.ok(lines.upsert(principal.getUserId(), principal.getHouseholdId(),
            categoryId, request));
    }
}
