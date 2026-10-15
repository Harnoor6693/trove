package finance.trove.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Enumeration safety (readiness gap P2-4).
 *
 * <p>The property under test is that an unknown address and a wrong password are
 * <em>indistinguishable</em>: same exception, same message, same slug, same status, and the same
 * amount of work done. The last one is the easy half to get wrong — an implementation that returns
 * early when the user does not exist answers in microseconds while a wrong password takes ~100 ms,
 * and that difference is a reliable oracle for "does this person bank here?" over the internet,
 * not just on a LAN.
 *
 * <p>The cost parameters here are deliberately tiny. The timing property is asserted structurally
 * — exactly one {@code matches} call on every path — rather than by measuring a clock, because a
 * wall-clock assertion on a shared CI runner is a flaky test that eventually gets deleted, taking
 * the guarantee with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalAccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String PASSWORD = "a-perfectly-fine-passphrase";

    @Mock
    private UserRepository users;

    @Mock
    private LocalCredentialRepository credentials;

    @Mock
    private LockoutService lockout;

    @Mock
    private SecurityEventRecorder securityEvents;

    /** Counts verifications so "exactly one, whatever the outcome" is an assertion, not a hope. */
    private static final class CountingEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new Argon2PasswordEncoder(16, 32, 1, 1024, 1);
        private int matchCalls;

        @Override
        public String encode(CharSequence raw) {
            return delegate.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            matchCalls++;
            return delegate.matches(raw, encoded);
        }

        @Override
        public boolean upgradeEncoding(String encoded) {
            return delegate.upgradeEncoding(encoded);
        }
    }

    private CountingEncoder encoder;
    private LocalAccountService service;
    private User user;
    private LocalCredential credential;

    @BeforeEach
    void setUp() {
        encoder = new CountingEncoder();
        PasswordPolicy policy =
            new PasswordPolicy(new PasswordProperties(12, 128, 16, 32, 1, 1024, 1));
        service = new LocalAccountService(users, credentials, encoder, policy, lockout,
            securityEvents);
        user = new User(UUID.randomUUID(), UserRole.MEMBER, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
        credential = new LocalCredential(UUID.randomUUID(), encoder.encode(PASSWORD), NOW);
        encoder.matchCalls = 0;
    }

    @Test
    @DisplayName("The right password for an active account returns the user")
    void happyPath() {
        when(users.findByEmailIgnoreCase("member@trove.test")).thenReturn(Optional.of(user));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThat(service.authenticate("member@trove.test", PASSWORD, "203.0.113.1", NOW))
            .isSameAs(user);
        verify(lockout).recordSuccess(anyString(), anyString(), any(), any());
        assertThat(encoder.matchCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("An unknown address and a wrong password are byte-for-byte the same refusal")
    void unknownAddressAndWrongPasswordAreIdentical() {
        when(users.findByEmailIgnoreCase("nobody@trove.test")).thenReturn(Optional.empty());
        InvalidCredentialsException unknown = (InvalidCredentialsException) org.assertj.core.api
            .Assertions.catchThrowable(() ->
                service.authenticate("nobody@trove.test", PASSWORD, "203.0.113.1", NOW));
        int callsForUnknown = encoder.matchCalls;

        encoder.matchCalls = 0;
        when(users.findByEmailIgnoreCase("member@trove.test")).thenReturn(Optional.of(user));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));
        InvalidCredentialsException wrong = (InvalidCredentialsException) org.assertj.core.api
            .Assertions.catchThrowable(() ->
                service.authenticate("member@trove.test", "the-wrong-passphrase", "203.0.113.1",
                    NOW));

        assertThat(unknown).isNotNull();
        assertThat(wrong).isNotNull();
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
        assertThat(unknown.getStatus()).isEqualTo(wrong.getStatus());
        assertThat(unknown.getSlug()).isEqualTo(wrong.getSlug()).isEqualTo("credentials-invalid");
        assertThat(callsForUnknown)
            .as("an unknown address must still cost one argon2id verification, against a fixed "
                + "dummy hash, or the timing difference is an oracle for who banks here")
            .isEqualTo(1)
            .isEqualTo(encoder.matchCalls);
    }

    @Test
    @DisplayName("A locked account fails identically, and still spends the verification")
    void lockedAccountsFailLikeAWrongPassword() {
        credential.recordFailure(5, NOW.plus(Duration.ofMinutes(10)));
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() ->
            service.authenticate("member@trove.test", PASSWORD, "203.0.113.1", NOW))
            .isInstanceOf(InvalidCredentialsException.class)
            .hasMessage(InvalidCredentialsException.DETAIL);
        assertThat(encoder.matchCalls)
            .as("skipping the hash for a locked account would make it measurably faster than a "
                + "wrong password, revealing the lock the response deliberately hides")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("A deactivated account fails identically")
    void deactivatedAccountsFailTheSameWay() {
        user.setActive(false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() ->
            service.authenticate("member@trove.test", PASSWORD, "203.0.113.1", NOW))
            .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("An OIDC-only account with no password fails identically")
    void accountsWithoutAPasswordFailTheSameWay() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(credentials.findByUserId(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.authenticate("member@trove.test", PASSWORD, "203.0.113.1", NOW))
            .isInstanceOf(InvalidCredentialsException.class);
        assertThat(encoder.matchCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("Creating a credential runs the password policy first")
    void createEnforcesThePolicy() {
        assertThatThrownBy(() -> service.createCredential(UUID.randomUUID(), "short", "a@b.test",
            NOW))
            .isInstanceOf(PasswordPolicyException.class);
    }

    @Test
    @DisplayName("Re-auth checks the password without touching the lockout counters")
    void reAuthDoesNotCountAgainstTheAccount() {
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThat(service.matchesCurrentPassword(user.getId(), PASSWORD)).isTrue();
        assertThat(service.matchesCurrentPassword(user.getId(), "wrong-passphrase-here")).isFalse();
        verify(lockout, org.mockito.Mockito.never())
            .recordFailure(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Re-auth for a user with no credential verifies the dummy and returns false")
    void reAuthAgainstNoCredential() {
        when(credentials.findByUserId(any())).thenReturn(Optional.empty());
        assertThat(service.matchesCurrentPassword(UUID.randomUUID(), PASSWORD)).isFalse();
        assertThat(encoder.matchCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("An admin unlock clears the counter without changing the password")
    void unlockClearsTheLock() {
        credential.recordFailure(9, NOW.plus(Duration.ofHours(24)));
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThat(service.unlock(user.getId())).isTrue();
        assertThat(credential.getLockedUntil()).isNull();
        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getPasswordHash()).isNotBlank();

        when(credentials.findByUserId(any())).thenReturn(Optional.empty());
        assertThat(service.unlock(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Setting a password enforces the policy and updates the timestamp")
    void setPassword() {
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        assertThatThrownBy(() ->
            service.setPassword(user.getId(), "password1234", "member@trove.test", NOW))
            .isInstanceOf(PasswordPolicyException.class);

        service.setPassword(user.getId(), "another-fine-passphrase", "member@trove.test",
            NOW.plusSeconds(60));
        assertThat(credential.getPasswordUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(encoder.matches("another-fine-passphrase", credential.getPasswordHash()))
            .isTrue();
    }
}
