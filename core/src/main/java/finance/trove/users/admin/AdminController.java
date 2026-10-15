package finance.trove.users.admin;

import finance.trove.auth.dto.AdminSettingsRequest;
import finance.trove.auth.dto.AdminSettingsResponse;
import finance.trove.auth.dto.AdminUserResponse;
import finance.trove.auth.dto.CreateInviteRequest;
import finance.trove.auth.dto.InviteResponse;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.auth.StepUpRequiredException;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.households.invites.InviteService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Household administration ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.1, §4.2; gap P2-7).
 *
 * <p>Guarded twice, on purpose. {@code /v1/admin/**} is {@code hasRole('ADMIN')} in the security
 * chain's matcher list, and every method here also carries {@code @PreAuthorize}. The matcher is
 * the one that holds if a method is added without the annotation; the annotation is the one that
 * holds if the matcher list is ever reorganised. Both refusals produce the JSON envelope rather
 * than a bare 500 — the matcher's through {@code TroveAccessDeniedHandler}, the annotation's
 * through {@code GlobalExceptionHandler}'s {@code AccessDeniedException} branch — which is the
 * concrete fix gap P2-7 asked for.
 *
 * <p><strong>Admin is not a tenancy bypass.</strong> Every lookup is scoped to the admin's own
 * household. Under one-household-per-install that is currently a tautology; it is written this way
 * so it stays true the day it stops being one, which is the only time the mistake would be
 * expensive (§9 risk 2).
 */
@RestController
@RequestMapping("/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final InviteService invites;
    private final HouseholdRepository households;
    private final UserRepository users;
    private final UserAdminService userAdmin;
    private final LocalAccountService accounts;
    private final TotpService totp;
    private final UserClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AdminController(InviteService invites, HouseholdRepository households,
                           UserRepository users, UserAdminService userAdmin,
                           LocalAccountService accounts, TotpService totp, UserClock clock) {
        this.invites = invites;
        this.households = households;
        this.users = users;
        this.userAdmin = userAdmin;
        this.accounts = accounts;
        this.totp = totp;
        this.clock = clock;
    }

    /**
     * {@code GET /v1/admin/users} — the household's members, oldest first (§4.2).
     *
     * <p>Scoped by the caller's own household id from the signed token. That is not decoration:
     * it is why an admin of household A cannot enumerate household B, and it is enforced by the
     * repository having no unscoped finder to call ({@code UserScopedRepository}).
     */
    @GetMapping("/users")
    public ApiResponse<List<AdminUserResponse>> listUsers(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(users.findAllByHouseholdIdOrderByCreatedAtAsc(
                principal.getHouseholdId()).stream()
            .map(user -> AdminUserResponse.of(user, totp.isEnabled(user.getId())))
            .toList());
    }

    /**
     * {@code DELETE /v1/admin/users/{id}} — removes a member of the caller's own household.
     *
     * <p>Two refusals, and the order matters:
     *
     * <ul>
     *   <li>A member of another household is a <strong>404</strong>, not a 403. The lookup is
     *       household-scoped, so "not yours" and "does not exist" are the same answer and neither
     *       confirms that an id is real (§9 risk 2).</li>
     *   <li>The last ADMIN is a <strong>409</strong>. An install whose only administrator has
     *       been deleted has no way back in: registration is closed by default, invites are an
     *       admin route, and so is the settings flag that would open registration. The guard
     *       counts rather than comparing ids, so it catches an admin deleting themselves and an
     *       admin deleting the other one equally — self-deletion is allowed only while somebody
     *       else can still administer the household. Counting and deleting happen inside one
     *       transaction behind a lock on the household row ({@code UserAdminService}), because a
     *       guard made of a read followed by a write is not a guard when two requests arrive at
     *       once.</li>
     * </ul>
     *
     * <p><strong>Since collapse step 18 this is a SOFT delete, and deliberately the same one
     * {@code DELETE /v1/me} performs.</strong> One path, one guard, one event: keeping the admin
     * delete hard while the self delete was soft would mean two behaviours a client had to
     * explain, and the last-ADMIN guard would have to be right in two places. The member is
     * deactivated, stamped and their sessions revoked immediately — {@code is_active = false} is
     * already honoured by sign-in, refresh, the weekly digest, insight recipients and the webhook
     * audience — and {@code POST /v1/admin/users/{id}/restore} can bring them back for the grace
     * period. After that {@code AccountPurgeJob} erases the row.
     *
     * <p>{@code GET /v1/admin/users} already returns {@code active}, so a soft-deleted member
     * stays visible to the administrator who has to decide whether to restore them.
     *
     * <p>When the erasure finally happens, every credential, session and OIDC link of the member
     * goes with the row through {@code ON DELETE CASCADE} (V4-V7); their {@code security_event}
     * history survives with a null {@code user_id} (V9), because an incident review must not be
     * erasable by deleting an account, and the household's shared ledger survives with the
     * author nulled (owner decision 17).
     */
    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> deleteUser(@AuthenticationPrincipal TroveUserDetails principal,
                                        @PathVariable UUID id) {
        userAdmin.deleteMember(principal.getHouseholdId(), principal.getUserId(), id);
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code GET /v1/admin/invites} — a bare array in {@code data}, newest first.
     *
     * <p>{@code token} and {@code url} are populated for invites that can still be redeemed, and
     * null for the rest. The web admin UI reads the link from here rather than from the creation
     * response ({@code apps/web/src/pages/Settings/index.tsx:215}), so the clock is passed in:
     * "still redeemable" is a question about now.
     */
    @GetMapping("/invites")
    public ApiResponse<List<InviteResponse>> listInvites(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(invites.list(principal.getHouseholdId(), clock.now()));
    }

    /**
     * {@code POST /v1/admin/invites} — 201 with the one and only readable copy of the token.
     *
     * <p>The body is optional and {@code {}} is the normal case: the web client sends literally
     * {@code createInvite({})} ({@code apps/web/src/pages/Settings/index.tsx:167}). An
     * {@code @Valid @RequestBody} that was required would 400 that call.
     */
    @PostMapping("/invites")
    public ResponseEntity<ApiResponse<InviteResponse>> createInvite(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody(required = false) CreateInviteRequest request) {

        InviteResponse invite = invites.create(principal.getHouseholdId(), principal.getUserId(),
            request, clock.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(invite));
    }

    /** {@code DELETE /v1/admin/invites/{id}} — withdraws an unredeemed invite. */
    @DeleteMapping("/invites/{id}")
    public ApiResponse<Void> revokeInvite(@AuthenticationPrincipal TroveUserDetails principal,
                                          @PathVariable UUID id) {
        invites.revoke(principal.getHouseholdId(), id, principal.getUserId(), clock.now());
        return ApiResponse.<Void>ok(null);
    }

    /** {@code GET /v1/admin/settings}. */
    @GetMapping("/settings")
    public ApiResponse<AdminSettingsResponse> settings(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(
            new AdminSettingsResponse(household(principal).isRegistrationOpen()));
    }

    /**
     * {@code PUT /v1/admin/settings} — {@code {registrationOpen}}.
     *
     * <p>Written to the household row rather than to configuration so that an admin's decision
     * survives a restart. The environment variable is only ever a seed for the first boot.
     */
    @PutMapping("/settings")
    public ApiResponse<AdminSettingsResponse> updateSettings(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody AdminSettingsRequest request) {

        Household household = household(principal);
        if (request.registrationOpen() != null) {
            household.setRegistrationOpen(request.registrationOpen());
            households.save(household);
        }
        return ApiResponse.ok(new AdminSettingsResponse(household.isRegistrationOpen()));
    }

    /**
     * {@code POST /v1/admin/users/{id}/restore} — brings a member back inside the deletion grace.
     *
     * <p>The other half of collapse step 18's soft delete, and the reason the delete above is soft
     * at all: gap row 16 asks for a seven-day window an administrator can undo from, and an erased
     * row cannot be undone from anything.
     *
     * <p><strong>There is deliberately no self-target refusal here</strong>, unlike
     * {@code unlock} and {@code clearTotp}. Those two are refused on self because they weaken the
     * caller's own security state from a route that asks for no password. This one cannot be
     * turned on the caller: an administrator inside the grace is deactivated, so their sessions
     * are revoked and their token is refused by the filter chain before it reaches any controller.
     * A rule that could never fire would only read as a rule somebody had thought about.
     *
     * <p>Refusals: another household's id is 404, a member who was never deleted is 409, and a
     * member whose grace has already passed is 409 — the row is gone or the next purge pass is
     * about to take it, and reactivating an account queued for erasure is a promise the server
     * cannot keep.
     */
    @PostMapping("/users/{id}/restore")
    public ApiResponse<Void> restoreUser(@AuthenticationPrincipal TroveUserDetails principal,
                                         @PathVariable UUID id) {
        userAdmin.restoreMember(principal.getHouseholdId(), principal.getUserId(), id);
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/admin/users/{id}/unlock} — ends every sign-in throttle on the account
     * without changing a password (gap P2-5, B3-05).
     *
     * <p>Throttles end by themselves; this is for an account under attack from many addresses,
     * which stays throttled for addresses it has not signed in from until the attack stops.
     */
    @PostMapping("/users/{id}/unlock")
    public ApiResponse<Void> unlock(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        refuseSelf(principal, id, "clear your own lock");
        accounts.unlock(member(principal, id).getId());
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code DELETE /v1/admin/users/{id}/totp} — clears a member's second factor (§1.5).
     *
     * <p>The lost-phone escape hatch. It writes {@code TOTP_DISABLE} with {@code actor_id} set to
     * the admin and {@code user_id} to the member, which is the entire reason those are two
     * columns: "the second factor was removed from this account" and "this account removed its own
     * second factor" are different stories and an incident review needs to tell them apart.
     */
    @DeleteMapping("/users/{id}/totp")
    public ApiResponse<Void> clearTotp(@AuthenticationPrincipal TroveUserDetails principal,
                                       @PathVariable UUID id) {
        refuseSelf(principal, id, "remove your own second factor");
        totp.disable(member(principal, id).getId(), principal.getUserId());
        return ApiResponse.<Void>ok(null);
    }

    /**
     * Refuses an administrative action aimed at the administrator performing it.
     *
     * <p>The routes above are the escape hatch for <em>somebody else</em> — §1.5 states it as
     * "admin can clear a household member's TOTP ({@code actor != subject})". Turned on the
     * caller's own account they are something else entirely: a way around the step-up.
     * {@code DELETE /v1/auth/totp} demands the password <em>and</em> a live code before it will
     * remove a second factor, precisely because holding a session is not the same as knowing the
     * password — the borrowed unlocked laptop, the exfiltrated bearer token. An admin who could
     * clear their own factor from here would need neither, so the strongest control on the
     * account would be removable by the weakest credential that reaches it.
     *
     * <p>Unlocking is the same shape and gets the same answer, so that "an admin route never acts
     * on the admin's own security state" is one rule rather than a list of exceptions. The way
     * out for an administrator who has genuinely lost their own factor or locked themselves out
     * is the console ({@code finance.trove.app.cli}), which requires shell access to the server —
     * a credential a stolen session does not confer.
     */
    private static void refuseSelf(TroveUserDetails principal, UUID id, String action) {
        if (id.equals(principal.getUserId())) {
            throw new StepUpRequiredException(
                "An administrator cannot " + action + " from the admin routes. Use your own "
                    + "account security settings, which ask for your password, or the trove "
                    + "admin console on the server.");
        }
    }

    private Household household(TroveUserDetails principal) {
        return households.findById(principal.getHouseholdId())
            .orElseThrow(() -> ResourceNotFoundException.of("Household",
                principal.getHouseholdId()));
    }

    private User member(TroveUserDetails principal, UUID id) {
        return users.findByIdAndHouseholdId(id, principal.getHouseholdId())
            .orElseThrow(() -> ResourceNotFoundException.of("User", id));
    }
}
