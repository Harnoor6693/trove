package finance.trove.auth;

import finance.trove.app.audit.SecurityEventQueryService;
import finance.trove.app.audit.SecurityEventView;
import finance.trove.auth.dto.MeResponse;
import finance.trove.auth.dto.PreferencesResponse;
import finance.trove.auth.dto.PreferencesUpdateRequest;
import finance.trove.auth.dto.UpdateMeRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.dto.ApiResponse;
import finance.trove.users.User;
import finance.trove.users.UserProfileService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated view of the caller's own account
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.1 {@code MeController}, §4.2 preferences).
 *
 * <p>Three routes across two path prefixes, so there is no class-level mapping:
 * {@code GET /v1/auth/me} is the session read the clients hydrate from, while {@code /v1/me} and
 * {@code /v1/me/preferences} are the writes. The split is the clients' — {@code auth/me} is called
 * on every app start and is in the never-refresh list, the {@code /v1/me*} routes are ordinary
 * authenticated calls that do trigger a refresh on 401 — and it is cheaper to honour it than to
 * argue with two shipped clients about it.
 *
 * <p>Identity always comes from {@code @AuthenticationPrincipal}: the id and the household are
 * claims of a token this server signed. No route here takes a user id from a path, a query or a
 * body, which is the structural half of §9 risk 2 (the ArchUnit rule that bans an
 * {@code X-User-Id} header is the other half).
 */
@RestController
public class MeController {

    private final UserProfileService profiles;
    private final TotpService totp;
    private final SecurityEventQueryService securityEvents;

    public MeController(UserProfileService profiles, TotpService totp,
                        SecurityEventQueryService securityEvents) {
        this.profiles = profiles;
        this.totp = totp;
        this.securityEvents = securityEvents;
    }

    /**
     * {@code GET /v1/auth/me} — the full session shape of handoff §4a point 1.
     *
     * <p>Read fresh from the database on every call rather than assembled from token claims. A
     * token lives fifteen minutes; a display name, a locale or an {@code onboardingCompleted} flag
     * changed in that window has to be visible on the next read, and the alternative is a settings
     * screen that appears not to have saved.
     */
    @GetMapping("/v1/auth/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal TroveUserDetails principal) {
        User user = profiles.require(principal.getUserId(), principal.getHouseholdId());
        return ApiResponse.ok(MeResponse.of(user, totp.isEnabled(user.getId())));
    }

    /** {@code PUT /v1/me} — returns the full {@link MeResponse} the caller writes into its store. */
    @PutMapping("/v1/me")
    public ApiResponse<MeResponse> updateMe(@AuthenticationPrincipal TroveUserDetails principal,
                                            @Valid @RequestBody UpdateMeRequest request) {
        User user = profiles.updateProfile(principal.getUserId(), principal.getHouseholdId(),
            request);
        return ApiResponse.ok(MeResponse.of(user, totp.isEnabled(user.getId())));
    }

    /** {@code GET /v1/me/preferences}. */
    @GetMapping("/v1/me/preferences")
    public ApiResponse<PreferencesResponse> preferences(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(PreferencesResponse.of(
            profiles.require(principal.getUserId(), principal.getHouseholdId())));
    }

    /**
     * {@code PUT /v1/me/preferences} — a partial patch that returns the whole resource.
     *
     * <p>Returning the merged object rather than 204 is what lets the client write the response
     * straight into its query cache ({@code apps/web/src/hooks/useUpdatePreferencesMutation.ts:13})
     * instead of guessing at the merge itself and drifting from the server.
     */
    @PutMapping("/v1/me/preferences")
    public ApiResponse<PreferencesResponse> updatePreferences(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody PreferencesUpdateRequest request) {

        return ApiResponse.ok(PreferencesResponse.of(
            profiles.updatePreferences(principal.getUserId(), principal.getHouseholdId(),
                request)));
    }

    /**
     * {@code GET /v1/me/security-events} — the caller's own recent activity (gap P2-17).
     *
     * <p>Self-service, and scoped to the principal rather than to a path variable. Somebody who
     * suspects their password is known to another person needs to be able to see where their
     * account has been signed in from without asking an admin, and an admin who could be asked is
     * exactly the person a household member might not want to ask.
     */
    @GetMapping("/v1/me/security-events")
    public ApiResponse<List<SecurityEventView>> securityEvents(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(securityEvents.recentFor(principal.getUserId()));
    }
}
