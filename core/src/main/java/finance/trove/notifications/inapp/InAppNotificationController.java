package finance.trove.notifications.inapp;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.notifications.inapp.dto.MarkedReadResponse;
import finance.trove.notifications.inapp.dto.NotificationResponse;
import finance.trove.notifications.inapp.dto.UnreadCountResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The member's notification inbox (§4.9).
 *
 * <p>Five routes, and every one of them acts on the caller and only on the caller. The identity
 * comes from {@code @AuthenticationPrincipal} — a claim of a token this server signed — and no
 * path variable, query parameter or header in this class names a user.
 *
 * <p><strong>A household mate is refused here, unlike almost everywhere else on this API.</strong>
 * The ledger is shared by the owner's decision, so a mate sees every transaction, budget and
 * account; a notification is addressed to one person, so a mate's notification id is 404 and never
 * 403. {@code AccessControlIT}'s step-11 block asserts both halves.
 *
 * <p>The page bounds are enforced rather than clamped, exactly as {@code AuditController}'s are: a
 * client that computed {@code page=-1} is told, with a {@code fieldErrors} entry naming the
 * parameter, rather than being shown page zero and left to wonder.
 */
@RestController
@Validated
@RequestMapping("/v1/notifications")
public class InAppNotificationController {

    private final InAppNotificationService notifications;

    public InAppNotificationController(InAppNotificationService notifications) {
        this.notifications = notifications;
    }

    /** {@code GET /v1/notifications} — one page of the caller's undismissed notifications. */
    @GetMapping
    public ApiResponse<List<NotificationResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "" + InAppNotificationService.DEFAULT_LIMIT)
        @Min(1) @Max(InAppNotificationService.MAX_LIMIT) int limit) {

        PagedResult<InAppNotification> result =
            notifications.page(principal.getUserId(), page, limit);
        return ApiResponse.paged(
            result.content().stream().map(NotificationResponse::of).toList(),
            PaginationMeta.from(result));
    }

    /** {@code GET /v1/notifications/unread-count} — the badge number mobile polls. */
    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount(
        @AuthenticationPrincipal TroveUserDetails principal) {

        return ApiResponse.ok(
            new UnreadCountResponse(notifications.unreadCount(principal.getUserId())));
    }

    /** {@code PATCH /v1/notifications/{id}/read} — idempotent; takes no body. */
    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationResponse> markRead(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id) {

        return ApiResponse.ok(
            NotificationResponse.of(notifications.markRead(principal.getUserId(), id)));
    }

    /** {@code POST /v1/notifications/read-all} — answers how many rows it changed. */
    @PostMapping("/read-all")
    public ApiResponse<MarkedReadResponse> markAllRead(
        @AuthenticationPrincipal TroveUserDetails principal) {

        return ApiResponse.ok(
            new MarkedReadResponse(notifications.markAllRead(principal.getUserId())));
    }

    /**
     * {@code DELETE /v1/notifications/{id}} — a dismissal, not a delete.
     *
     * <p>200 with the empty envelope rather than 204, matching
     * {@code DELETE /v1/categories/{id}} and {@code DELETE /v1/networth/assets/{id}}: every body
     * on this API except {@code GET /v1/status} is the envelope, and a 204 would be the one
     * response a client has to special-case.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> dismiss(@AuthenticationPrincipal TroveUserDetails principal,
                                     @PathVariable UUID id) {

        notifications.dismiss(principal.getUserId(), id);
        return ApiResponse.ok(null);
    }
}
