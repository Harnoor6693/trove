package finance.trove.budgets.goal;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.budgets.goal.dto.GoalRequest;
import finance.trove.budgets.goal.dto.GoalResponse;
import finance.trove.budgets.goal.dto.GoalUpdateRequest;
import finance.trove.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's savings goals ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, body or
 * header. A goal of another household is 404, never 403 (§9 risk 2).
 */
@RestController
@RequestMapping("/v1/goals")
public class GoalController {

    private final GoalService goals;

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    @GetMapping
    public ApiResponse<List<GoalResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(goals.list(principal.getHouseholdId()).stream()
            .map(GoalResponse::of).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GoalResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody GoalRequest request) {
        SavingsGoal created = goals.create(principal.getHouseholdId(), principal.getUserId(),
            request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(GoalResponse.of(created)));
    }

    @PutMapping("/{id}")
    public ApiResponse<GoalResponse> update(@AuthenticationPrincipal TroveUserDetails principal,
                                            @PathVariable UUID id,
                                            @Valid @RequestBody GoalUpdateRequest request) {
        return ApiResponse.ok(
            GoalResponse.of(goals.update(principal.getHouseholdId(), id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        goals.delete(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }
}
