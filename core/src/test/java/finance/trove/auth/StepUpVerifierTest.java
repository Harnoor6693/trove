package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.dto.StepUpRequest;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step-up matrix, and the hole it exists to close.
 *
 * <p>{@code LocalAccountService.matchesCurrentPassword} falls back to a fixed dummy hash when a
 * member has no {@code local_credential} row — correct for {@code authenticate}, where it is what
 * makes an unknown address and a wrong password indistinguishable, and <strong>silently fatal as a
 * step-up</strong>: it returns false for every OIDC-only member whatever they type. A
 * password-only step-up would therefore lock exactly those members out of the deletion route that
 * exists because Apple 5.1.1(v) and Play's account-deletion policy require it. The last three
 * tests here are that case.
 */
class StepUpVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final LocalAccountService accounts = mock(LocalAccountService.class);
    private final TotpService totp = mock(TotpService.class);
    private final SecurityEventRecorder securityEvents = mock(SecurityEventRecorder.class);

    private final UUID userId = UUID.randomUUID();

    private final StepUpVerifier verifier = new StepUpVerifier(accounts, totp, securityEvents,
        new UserClock((UserTimeZoneProvider) id -> ZoneId.of("America/Toronto"),
            Clock.fixed(NOW, ZoneOffset.UTC)));

    private void account(boolean hasPassword, boolean hasTotp) {
        when(accounts.hasPassword(userId)).thenReturn(hasPassword);
        when(totp.isEnabled(userId)).thenReturn(hasTotp);
    }

    private void expectStage(String stage) {
        verify(securityEvents).record(SecurityEventType.LOGIN_FAIL, userId,
            Map.of(StepUpVerifier.STAGE, stage));
    }

    @Test
    @DisplayName("Password only: the right password passes")
    void theRightPasswordPasses() {
        account(true, false);
        when(accounts.matchesCurrentPassword(userId, "right")).thenReturn(true);

        assertThatCode(() -> verifier.verify(userId, new StepUpRequest("right", null)))
            .doesNotThrowAnyException();
        verify(securityEvents, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("A wrong password is 403 step-up-required and records the password stage")
    void aWrongPasswordIsRefused() {
        account(true, false);
        when(accounts.matchesCurrentPassword(userId, "wrong")).thenReturn(false);

        assertThatThrownBy(() -> verifier.verify(userId, new StepUpRequest("wrong", null)))
            .isInstanceOf(StepUpRequiredException.class)
            .hasFieldOrPropertyWithValue("slug", StepUpRequiredException.SLUG);
        expectStage("step-up-password");
    }

    @Test
    @DisplayName("A missing password on an account that has one is refused, not accepted")
    void anAbsentPasswordIsRefused() {
        account(true, false);

        assertThatThrownBy(() -> verifier.verify(userId, new StepUpRequest(null, null)))
            .isInstanceOf(StepUpRequiredException.class);
        verify(accounts, never()).matchesCurrentPassword(eq(userId), anyString());
        expectStage("step-up-password");
    }

    @Test
    @DisplayName("Password and TOTP: both are demanded, and the right pair passes")
    void bothFactorsAreDemanded() {
        account(true, true);
        when(accounts.matchesCurrentPassword(userId, "right")).thenReturn(true);
        when(totp.verify(userId, "123456", NOW)).thenReturn(true);

        assertThatCode(() -> verifier.verify(userId, new StepUpRequest("right", "123456")))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A wrong code with the right password is 403 and records the TOTP stage")
    void aWrongCodeIsRefused() {
        account(true, true);
        when(accounts.matchesCurrentPassword(userId, "right")).thenReturn(true);
        when(totp.verify(userId, "000000", NOW)).thenReturn(false);

        assertThatThrownBy(() -> verifier.verify(userId, new StepUpRequest("right", "000000")))
            .isInstanceOf(StepUpRequiredException.class);
        expectStage("step-up-totp");
        verify(securityEvents, never()).record(SecurityEventType.LOGIN_FAIL, userId,
            Map.of(StepUpVerifier.STAGE, "step-up-password"));
    }

    @Test
    @DisplayName("An OIDC-only member with TOTP proves the code alone and is NOT silently refused")
    void anOidcOnlyMemberWithTotpPassesOnTheCode() {
        // THE HOLE. With a password-only step-up this member could never delete their account:
        // matchesCurrentPassword answers false against the dummy hash whatever they send, and the
        // message would tell them their password is wrong when they do not have one.
        account(false, true);
        when(totp.verify(userId, "123456", NOW)).thenReturn(true);

        assertThatCode(() -> verifier.verify(userId, new StepUpRequest(null, "123456")))
            .doesNotThrowAnyException();
        verify(accounts, never()).matchesCurrentPassword(eq(userId), anyString());
    }

    @Test
    @DisplayName("An OIDC-only member with TOTP is still refused on a wrong code")
    void anOidcOnlyMemberStillNeedsTheRightCode() {
        account(false, true);
        when(totp.verify(userId, "000000", NOW)).thenReturn(false);

        assertThatThrownBy(() -> verifier.verify(userId, new StepUpRequest(null, "000000")))
            .isInstanceOf(StepUpRequiredException.class);
        expectStage("step-up-totp");
    }

    @Test
    @DisplayName("An OIDC-only member with no second factor is refused, and told how to proceed")
    void anOidcOnlyMemberWithNoFactorIsToldWhatToDo() {
        account(false, false);

        assertThatThrownBy(() -> verifier.verify(userId, new StepUpRequest("anything", "000000")))
            .as("accepting the session alone would be accepting exactly the credential a step-up "
                + "exists to distrust, on the two most destructive routes in the product")
            .isInstanceOf(StepUpRequiredException.class)
            .hasMessageContaining("identity provider")
            .hasMessageContaining("password reset link");
        expectStage("step-up-unavailable");
        verify(accounts, never()).matchesCurrentPassword(eq(userId), anyString());
    }

    @Test
    @DisplayName("A null body is refused rather than dereferenced")
    void aNullBodyIsRefused() {
        account(true, false);

        assertThatThrownBy(() -> verifier.verify(userId, null))
            .isInstanceOf(StepUpRequiredException.class);
    }
}
