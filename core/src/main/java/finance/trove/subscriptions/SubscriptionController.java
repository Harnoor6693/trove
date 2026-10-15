package finance.trove.subscriptions;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.subscriptions.alert.AlertService;
import finance.trove.subscriptions.detection.SubscriptionDetectionService;
import finance.trove.subscriptions.dto.SubscriptionAlertResponse;
import finance.trove.subscriptions.dto.SubscriptionResponse;
import finance.trove.subscriptions.dto.SubscriptionSummaryResponse;
import finance.trove.subscriptions.subscription.SubscriptionService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The household's subscription surface: the detected rows, their alerts, the summary and the
 * manual scan ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.8; {@code openapi/trove-core.yaml}
 * tag {@code Subscriptions}).
 *
 * <p>Identity is always {@link TroveUserDetails}, from signed claims — never a path, a body or a
 * header. Every route below is resolved inside {@code principal.getHouseholdId()}, so an id
 * belonging to another household is simply not found: 404, never 403, and the refusal never
 * confirms that the row exists (§9 risk 2).
 *
 * <p><strong>There is no create and no update.</strong> A subscription is an observation about
 * the ledger, written only by detection — the nightly {@code subscription-scan} job,
 * {@code POST /v1/subscriptions/scan} below, and the listener that runs after an import writes
 * transactions. The only change a member can make is the soft delete.
 *
 * <p><strong>{@code DELETE} is a SOFT delete.</strong> It sets {@code is_cancelled} and the row
 * survives: it leaves {@code GET /v1/subscriptions} and every summary figure but stays readable
 * at {@code GET /v1/subscriptions/{id}} with {@code cancelled: true}. A hard delete would be
 * re-detected by the next scan and the member would have to dismiss the same merchant every
 * night.
 */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptions;
    private final AlertService alerts;
    private final SubscriptionDetectionService detection;

    public SubscriptionController(SubscriptionService subscriptions, AlertService alerts,
                                  SubscriptionDetectionService detection) {
        this.subscriptions = subscriptions;
        this.alerts = alerts;
        this.detection = detection;
    }

    /** {@code GET /v1/subscriptions} — the household's active rows, by merchant name. */
    @GetMapping
    public ApiResponse<List<SubscriptionResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(active(principal));
    }

    /**
     * {@code GET /v1/subscriptions/summary} — run-rate per currency and the next fourteen days.
     *
     * <p>{@code currency} on the body is the CALLING member's own {@code users.currency}, never a
     * literal and never derived from the rows (finding F1 / inventory SD-3).
     */
    @GetMapping("/summary")
    public ApiResponse<SubscriptionSummaryResponse> summary(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(
            subscriptions.summary(principal.getHouseholdId(), principal.getUserId()));
    }

    /** {@code GET /v1/subscriptions/alerts} — the household's unread alerts, newest first. */
    @GetMapping("/alerts")
    public ApiResponse<List<SubscriptionAlertResponse>> listAlerts(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(alerts.listUnread(principal.getHouseholdId()).stream()
            .map(SubscriptionAlertResponse::of).toList());
    }

    /**
     * {@code PATCH /v1/subscriptions/alerts/{id}/read} — acknowledge one alert.
     *
     * <p>Idempotent: acknowledging an already-read alert returns the same row rather than
     * conflicting. Takes no request body.
     */
    @PatchMapping("/alerts/{id}/read")
    public ApiResponse<SubscriptionAlertResponse> markAlertRead(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            SubscriptionAlertResponse.of(alerts.markRead(principal.getHouseholdId(), id)));
    }

    /**
     * {@code POST /v1/subscriptions/scan} — run detection now, synchronously, for the caller's
     * OWN household only.
     *
     * <p>The household comes from the signed token and there is no way to name another one. It
     * calls exactly the method the nightly job calls, so the two cannot drift apart, and it is
     * safe to run beside the job because the write is an upsert on
     * {@code UNIQUE (household_id, merchant_name)}.
     *
     * <p>200 rather than 201: the scan creates no single named resource, and re-running it over
     * unchanged history usually creates nothing at all. The body is the same one
     * {@code GET /v1/subscriptions} would return, so a client needs one round trip rather than
     * two.
     */
    @PostMapping("/scan")
    public ApiResponse<List<SubscriptionResponse>> scan(
        @AuthenticationPrincipal TroveUserDetails principal) {
        detection.detectForHousehold(principal.getHouseholdId());
        return ApiResponse.ok(active(principal));
    }

    /** {@code GET /v1/subscriptions/{id}} — one row, cancelled ones included. */
    @GetMapping("/{id}")
    public ApiResponse<SubscriptionResponse> get(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {
        return ApiResponse.ok(
            SubscriptionResponse.of(subscriptions.get(principal.getHouseholdId(), id)));
    }

    /**
     * {@code DELETE /v1/subscriptions/{id}} — dismiss it. The row survives; see the class
     * javadoc. Answers the closed empty envelope, {@code {"success": true}}.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        subscriptions.cancel(principal.getHouseholdId(), id);
        return ApiResponse.ok(null);
    }

    private List<SubscriptionResponse> active(TroveUserDetails principal) {
        return subscriptions.listActive(principal.getHouseholdId()).stream()
            .map(SubscriptionResponse::of).toList();
    }
}
