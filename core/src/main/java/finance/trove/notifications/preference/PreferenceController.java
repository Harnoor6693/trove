package finance.trove.notifications.preference;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.send.DeliveryChannel;
import finance.trove.notifications.preference.dto.NotificationPreferencesResponse;
import finance.trove.notifications.preference.dto.NotificationPreferencesUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Each member's own notification preferences (§4.9).
 *
 * <p>Two routes, and both act on the caller and only on the caller. The identity comes from
 * {@code @AuthenticationPrincipal} — a claim of a token this server signed — and there is no path
 * variable, query parameter or header anywhere in this class that names a user. That is the
 * tenancy model rather than a convention: {@code notification_preference} is keyed by the user id,
 * a household mate has no more access to another member's settings than a stranger does, and there
 * is deliberately no route through which one could be built.
 */
@RestController
@RequestMapping("/v1/notifications/preferences")
public class PreferenceController {

    private final NotificationPreferenceService preferences;
    private final boolean emailAvailable;

    /**
     * {@code channels} decides {@code emailAvailable}: the EMAIL channel bean exists only when an
     * operator turns SMTP on, so its presence is exactly "this server can send email".
     */
    public PreferenceController(NotificationPreferenceService preferences,
                                List<DeliveryChannel> channels) {
        this.preferences = preferences;
        this.emailAvailable = channels.stream()
            .anyMatch(channel -> channel.channel() == NotificationChannel.EMAIL);
    }

    /** {@code GET} — the caller's settings, created with the shipped defaults on first access. */
    @GetMapping
    public ApiResponse<NotificationPreferencesResponse> read(
        @AuthenticationPrincipal TroveUserDetails principal) {

        return ApiResponse.ok(
            NotificationPreferencesResponse.of(preferences.getOrCreate(principal.getUserId()),
                emailAvailable));
    }

    /** {@code PUT} — a partial update; returns the whole resource for the client's query cache. */
    @PutMapping
    public ApiResponse<NotificationPreferencesResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody NotificationPreferencesUpdateRequest request) {

        return ApiResponse.ok(NotificationPreferencesResponse.of(
            preferences.update(principal.getUserId(), request), emailAvailable));
    }
}
