package finance.trove.transactions.dashboard;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.transactions.dashboard.dto.DashboardTodayResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's spending snapshot ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>The pre-collapse controller took its user id from a client-supplied header and answered a
 * zero-valued body when the header was absent, "so the UI can render before identity propagation
 * is complete" — which meant an unauthenticated caller got a 200 and a signed-in one got whatever
 * user id they chose to send (finding G1). Identity here is the signed token, and an anonymous
 * caller is refused by the security chain before this class is reached.
 */
@RestController
@RequestMapping("/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /** {@code GET /v1/dashboard/today} — boundaries in the caller's own timezone. */
    @GetMapping("/today")
    public ApiResponse<DashboardTodayResponse> today(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(
            dashboard.today(principal.getHouseholdId(), principal.getUserId()));
    }
}
