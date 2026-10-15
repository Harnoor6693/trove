package finance.trove.budgets.period;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.budgets.envelope.EnvelopeService;
import finance.trove.budgets.envelope.dto.EnvelopeRequest;
import finance.trove.budgets.envelope.dto.EnvelopeResponse;
import finance.trove.budgets.envelope.dto.EnvelopeUpdateRequest;
import finance.trove.budgets.period.dto.BudgetPeriodRequest;
import finance.trove.budgets.period.dto.BudgetPeriodResponse;
import finance.trove.budgets.period.dto.BudgetPeriodUpdateRequest;
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
 * The household's budgets: periods and the envelopes inside them
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, body or
 * header. A period or envelope of another household is 404, never 403 (§9 risk 2).
 *
 * <p>The envelope routes live here rather than in a controller of their own because they are a
 * sub-resource of a period: their path is rooted at {@code /v1/budgets/{id}}, and splitting them
 * across two {@code @RequestMapping}s that share a prefix is how a route ends up served by
 * something the spec does not describe. {@link EnvelopeService} is a separate bean, so the
 * package boundary §1.3 draws is still the real one.
 *
 * <p>{@code userId} is passed alongside {@code householdId} on every envelope read for one reason:
 * envelope health depends on where today falls in the period's month, and "today" is a per-member
 * boundary from {@code UserClock}, not a property of the household (gap P2-15).
 */
@RestController
@RequestMapping("/v1/budgets")
public class BudgetController {

    private final BudgetPeriodService periods;
    private final EnvelopeService envelopes;

    public BudgetController(BudgetPeriodService periods, EnvelopeService envelopes) {
        this.periods = periods;
        this.envelopes = envelopes;
    }

    @GetMapping
    public ApiResponse<List<BudgetPeriodResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(periods.list(principal.getHouseholdId()).stream()
            .map(BudgetPeriodResponse::of).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BudgetPeriodResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody BudgetPeriodRequest request) {
        BudgetPeriod created = periods.create(principal.getHouseholdId(), principal.getUserId(),
            request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(BudgetPeriodResponse.of(created)));
    }

    /**
     * {@code GET /v1/budgets/current} — this month in the CALLER's timezone, 404 when there is
     * none.
     *
     * <p>Declared before {@code /{id}} is irrelevant to Spring's matching (a literal segment
     * always beats a template) but it is where a reader looks for it.
     */
    @GetMapping("/current")
    public ApiResponse<BudgetPeriodResponse> current(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(BudgetPeriodResponse.of(
            periods.current(principal.getHouseholdId(), principal.getUserId())));
    }

    @GetMapping("/{id}")
    public ApiResponse<BudgetPeriodResponse> get(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            BudgetPeriodResponse.of(periods.get(principal.getHouseholdId(), id)));
    }

    @PutMapping("/{id}")
    public ApiResponse<BudgetPeriodResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody BudgetPeriodUpdateRequest request) {
        return ApiResponse.ok(
            BudgetPeriodResponse.of(periods.update(principal.getHouseholdId(), id, request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        periods.delete(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/envelopes")
    public ApiResponse<List<EnvelopeResponse>> listEnvelopes(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            envelopes.list(principal.getHouseholdId(), principal.getUserId(), id));
    }

    @PostMapping("/{id}/envelopes")
    public ResponseEntity<ApiResponse<EnvelopeResponse>> createEnvelope(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody EnvelopeRequest request) {
        EnvelopeResponse created = envelopes.create(principal.getHouseholdId(),
            principal.getUserId(), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping("/{id}/envelopes/{envelopeId}")
    public ApiResponse<EnvelopeResponse> getEnvelope(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @PathVariable UUID envelopeId) {
        return ApiResponse.ok(
            envelopes.get(principal.getHouseholdId(), principal.getUserId(), id, envelopeId));
    }

    @PutMapping("/{id}/envelopes/{envelopeId}")
    public ApiResponse<EnvelopeResponse> updateEnvelope(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @PathVariable UUID envelopeId, @Valid @RequestBody EnvelopeUpdateRequest request) {
        return ApiResponse.ok(envelopes.update(principal.getHouseholdId(),
            principal.getUserId(), id, envelopeId, request));
    }

    @DeleteMapping("/{id}/envelopes/{envelopeId}")
    public ApiResponse<Void> deleteEnvelope(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @PathVariable UUID envelopeId) {
        envelopes.delete(principal.getHouseholdId(), id, envelopeId);
        return ApiResponse.ok(null);
    }
}
