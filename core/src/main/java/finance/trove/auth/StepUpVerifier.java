package finance.trove.auth;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.dto.StepUpRequest;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.time.UserClock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The step-up every irreversible member action goes through (collapse step 18; §1.5, gap P2-6,
 * gap row 16).
 *
 * <p>Holding a session is not evidence of who is at the keyboard — the borrowed unlocked laptop,
 * the exfiltrated bearer token — so exporting a copy of an entire account and deleting an account
 * both demand the account's own factors again. The pattern is
 * {@code AccountSecurityController}'s: check the password with
 * {@link LocalAccountService#matchesCurrentPassword}, check a current code with
 * {@link TotpService#verify}, record a {@code LOGIN_FAIL} carrying the stage that failed, and
 * raise {@link StepUpRequiredException} (403, slug {@code step-up-required}).
 *
 * <h2>The hole this class closes</h2>
 * {@code matchesCurrentPassword} falls back to a fixed dummy hash when the member has no
 * {@code local_credential} row — which is exactly right for {@code authenticate}, where the dummy
 * is what makes an unknown address and a wrong password take the same time and produce the same
 * answer. Used as a step-up it means something else entirely: it returns <strong>false for every
 * OIDC-only member, whatever they type</strong>. A password-only step-up would therefore lock
 * precisely those members out of the deletion route that exists because Apple 5.1.1(v) and Play's
 * account-deletion policy require it — permanently, silently, and with an error message telling
 * them their password is wrong when they do not have one.
 *
 * <h2>The rule, decided here and implemented once</h2>
 * The factor set is resolved from what the account HAS, and at least one factor must be proved:
 * <ol>
 *   <li><strong>A local password exists.</strong> {@code password} must match. If TOTP is also
 *       enrolled, {@code code} must match as well — both, exactly as
 *       {@code DELETE /v1/auth/totp} demands, because either one alone lets through the very
 *       attacker the second factor exists to stop.</li>
 *   <li><strong>No password, TOTP enrolled.</strong> The {@code code} alone is the step-up. It is
 *       a live secret from a device the member holds and it is strictly stronger evidence than the
 *       session already in hand, which is the whole test a step-up applies.</li>
 *   <li><strong>Neither.</strong> Refused, 403 {@code step-up-required}, with a message that says
 *       what to do: ask an administrator for a password-reset link
 *       ({@code POST /v1/auth/password/reset-request}), set a password with it, and come back.
 *       That path really works — the reset creates the credential row a member who registered
 *       through an identity provider never had. What this class will NOT do is accept the session
 *       alone: that is the credential the step-up exists to distrust, and silently accepting it on
 *       the two most destructive routes in the product would be worse than the hole it replaces.
 *       Refusing loudly, with a route to follow, is the honest answer.</li>
 * </ol>
 *
 * <p>Every failure records a {@code LOGIN_FAIL} with a {@code stage} of {@code step-up-password},
 * {@code step-up-totp} or {@code step-up-unavailable}, through {@code SecurityEventRecorder.record}
 * — which is {@code REQUIRES_NEW}, because the row is written BECAUSE the request failed and a
 * rollback would otherwise eat it.
 */
@Component
public class StepUpVerifier {

    /** The audit metadata key every stage below is recorded under. */
    static final String STAGE = "stage";

    private final LocalAccountService accounts;
    private final TotpService totp;
    private final SecurityEventRecorder securityEvents;
    private final UserClock clock;

    public StepUpVerifier(LocalAccountService accounts, TotpService totp,
                          SecurityEventRecorder securityEvents, UserClock clock) {
        this.accounts = accounts;
        this.totp = totp;
        this.securityEvents = securityEvents;
        this.clock = clock;
    }

    /**
     * Proves the caller is who the session says, or throws.
     *
     * @param userId  the caller, from signed claims — never from a path, a body or a header
     * @param request the factors offered; may be empty, which simply fails
     * @throws StepUpRequiredException 403 for a wrong factor, a missing one, or an account with no
     *                                 factor to prove
     */
    public void verify(UUID userId, StepUpRequest request) {
        boolean hasPassword = accounts.hasPassword(userId);
        boolean hasTotp = totp.isEnabled(userId);

        if (!hasPassword && !hasTotp) {
            securityEvents.record(SecurityEventType.LOGIN_FAIL, userId,
                Map.of(STAGE, "step-up-unavailable"));
            throw new StepUpRequiredException(
                "This action needs your password or an authentication code, and this account has "
                    + "neither: it signs in through an identity provider only. Ask an "
                    + "administrator for a password reset link, set a password with it, and try "
                    + "again.");
        }

        if (hasPassword) {
            String password = request == null ? null : request.password();
            if (password == null || !accounts.matchesCurrentPassword(userId, password)) {
                securityEvents.record(SecurityEventType.LOGIN_FAIL, userId,
                    Map.of(STAGE, "step-up-password"));
                throw StepUpRequiredException.wrongPassword();
            }
        }

        if (hasTotp) {
            String code = request == null ? null : request.code();
            if (code == null || !totp.verify(userId, code, clock.now())) {
                securityEvents.record(SecurityEventType.LOGIN_FAIL, userId,
                    Map.of(STAGE, "step-up-totp"));
                throw StepUpRequiredException.wrongCode();
            }
        }
    }
}
