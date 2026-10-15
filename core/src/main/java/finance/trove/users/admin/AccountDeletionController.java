package finance.trove.users.admin;

import finance.trove.auth.StepUpVerifier;
import finance.trove.auth.dto.StepUpRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member deleting their own account (collapse step 18; gap row 16, §4.2).
 *
 * <p><strong>Why this exists at all.</strong> Apple's App Store guideline 5.1.1(v) and Google
 * Play's account-deletion policy both reject an application that offers sign-up and no in-app
 * deletion, and a self-hoster who cannot leave a household they joined has no recourse but a
 * database edit. Everything else here follows from that: the route has to work for every member,
 * including one who has only ever signed in through an identity provider, which is the hole
 * {@link StepUpVerifier} closes.
 *
 * <p><strong>It reuses {@link UserAdminService#deleteMember} rather than forking it.</strong> That
 * method already takes a {@code PESSIMISTIC_WRITE} lock on the household row before it counts
 * anything, already refuses the household's last ADMIN with 409, and already records
 * {@code ACCOUNT_DELETE} before it writes — and its guard counts rather than comparing ids, so it
 * covers a member deleting themselves without a single new branch. Self-deletion is
 * {@code targetId == actorId} and nothing else. A second implementation would be a second place
 * for the last-ADMIN guard to be wrong.
 *
 * <p>Identity comes from {@link TroveUserDetails} — signed claims, never a path, a body or a
 * header. There is deliberately no {@code userId} anywhere in this controller's surface: a
 * deletion route that took an id from a request would be the most valuable IDOR in the product.
 *
 * <p>It lives in {@code finance.trove.users.admin} beside the delete it calls, rather than in
 * {@code auth} beside {@code MeController}: the behaviour, the lock and the guard are all here,
 * and a route whose whole body is one delegation belongs next to what it delegates to.
 */
@RestController
@RequestMapping("/v1/me")
public class AccountDeletionController {

    private final UserAdminService userAdmin;
    private final StepUpVerifier stepUp;

    public AccountDeletionController(UserAdminService userAdmin, StepUpVerifier stepUp) {
        this.userAdmin = userAdmin;
        this.stepUp = stepUp;
    }

    /**
     * {@code DELETE /v1/me} — the caller deletes their own account, behind a step-up.
     *
     * <p>The step-up runs before anything is written, so a wrong password leaves nothing behind
     * but the {@code LOGIN_FAIL} the verifier records. The refusals a caller can meet are 403
     * {@code step-up-required} (wrong factor, or an account with no factor to prove) and 409
     * {@code conflict} (the household's last administrator).
     */
    @DeleteMapping
    public ApiResponse<Void> deleteMe(@AuthenticationPrincipal TroveUserDetails principal,
                                      @Valid @RequestBody StepUpRequest request) {
        stepUp.verify(principal.getUserId(), request);
        userAdmin.deleteMember(principal.getHouseholdId(), principal.getUserId(),
            principal.getUserId());
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/me/delete} — the same operation, same body, same effect.
     *
     * <p>Two verbs for one action because a DELETE that carries a request body is legal but poorly
     * supported: RFC 9110 leaves its semantics undefined, and intermediaries — proxies, some CDNs,
     * a few HTTP clients — drop the body without saying so. The step-up here needs a password or a
     * code, so a dropped body turns a deliberate, irreversible decision into an unexplained 403 in
     * front of somebody trying to leave. {@code AccountSecurityController} already carries the
     * identical pair for {@code DELETE /v1/auth/totp} and {@code POST /v1/auth/totp/disable}, with
     * the same reasoning; this is that precedent applied to the one route the app stores require.
     * The DELETE stays because it is the honest verb; this is the one that always works.
     */
    @PostMapping("/delete")
    public ApiResponse<Void> deleteMeByPost(@AuthenticationPrincipal TroveUserDetails principal,
                                            @Valid @RequestBody StepUpRequest request) {
        return deleteMe(principal, request);
    }
}
