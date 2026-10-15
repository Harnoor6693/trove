package finance.trove.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;

/**
 * Readiness gap P2-5, both halves.
 *
 * <p>The account half locks one address after repeated failures. The network half — the one an
 * account lock cannot cover — refuses an address that has been guessing across many accounts,
 * which is what credential stuffing actually looks like: one guess each against a thousand
 * addresses, tripping no per-account counter anywhere.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LockoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String IP = "203.0.113.7";

    @Mock
    private LoginAttemptRepository attempts;

    @Mock
    private LocalCredentialRepository credentials;

    @Mock
    private SecurityEventRecorder securityEvents;

    private final LockoutProperties properties = new LockoutProperties(
        5, Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(15),
        10, Duration.ofHours(24), 20, Duration.ofMinutes(10));

    private LockoutService service() {
        return new LockoutService(attempts, credentials, properties, securityEvents);
    }

    private static LocalCredential credential() {
        return new LocalCredential(UUID.randomUUID(), "$argon2id$stub", NOW);
    }

    // ------------------------------------------------------------------ per-account

    @Test
    @DisplayName("Below the threshold the counter rises but no lock is set")
    void countsWithoutLockingBelowTheThreshold() {
        when(attempts.countFailuresSince(anyString(), any())).thenReturn(3L);
        LocalCredential credential = credential();
        when(credentials.findByUserId(any())).thenReturn(java.util.Optional.of(credential));

        service().recordFailure("a@trove.test", IP, UUID.randomUUID(), NOW);

        assertThat(credential.getFailedAttempts()).isEqualTo(3);
        assertThat(credential.getLockedUntil()).isNull();
        verify(securityEvents, never()).record(eq(SecurityEventType.LOCKOUT), any(), any());
    }

    @Test
    @DisplayName("The fifth failure inside the window locks the account and writes LOCKOUT")
    void locksAtTheThreshold() {
        when(attempts.countFailuresSince(anyString(), any())).thenReturn(5L);
        LocalCredential credential = credential();
        UUID userId = UUID.randomUUID();
        when(credentials.findByUserId(userId)).thenReturn(java.util.Optional.of(credential));

        service().recordFailure("a@trove.test", IP, userId, NOW);

        assertThat(credential.getLockedUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(credential.isLockedAt(NOW.plusSeconds(10))).isTrue();
        assertThat(credential.isLockedAt(NOW.plusSeconds(31))).isFalse();
        verify(securityEvents).record(eq(SecurityEventType.LOCKOUT), eq(userId), any());
    }

    @Test
    @DisplayName("The lock doubles per further failure and stops at the ceiling")
    void backoffDoublesThenCaps() {
        LockoutService service = service();

        assertThat(service.backoffFor(5)).isEqualTo(Duration.ofSeconds(30));
        assertThat(service.backoffFor(6)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.backoffFor(7)).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.backoffFor(8)).isEqualTo(Duration.ofMinutes(4));
        assertThat(service.backoffFor(9)).isEqualTo(Duration.ofMinutes(8));
        assertThat(service.backoffFor(10))
            .as("the ceiling holds; an attacker's throughput collapses while a user who mistyped "
                + "twice waits half a minute")
            .isEqualTo(Duration.ofMinutes(15));
        assertThat(service.backoffFor(400))
            .as("the shift is clamped rather than overflowing a long")
            .isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Past the hard threshold the lock becomes the long one an admin clears")
    void hardLockTakesOver() {
        when(attempts.countFailuresSince(anyString(), any())).thenReturn(10L);
        LocalCredential credential = credential();
        when(credentials.findByUserId(any())).thenReturn(java.util.Optional.of(credential));

        service().recordFailure("a@trove.test", IP, UUID.randomUUID(), NOW);

        assertThat(credential.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("An address with no account is still recorded, for the per-address count")
    void recordsAttemptsAgainstUnknownAddresses() {
        service().recordFailure("nobody@trove.test", IP, null, NOW);

        verify(attempts).record("nobody@trove.test", IP, false, NOW);
        verify(attempts, never()).countFailuresSince(anyString(), any());
    }

    @Test
    @DisplayName("A success clears the counter and the lock")
    void successClearsTheLock() {
        LocalCredential credential = credential();
        credential.recordFailure(7, NOW.plusSeconds(600));

        service().recordSuccess("a@trove.test", IP, credential, NOW);

        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getLockedUntil()).isNull();
        verify(attempts).record("a@trove.test", IP, true, NOW);
    }

    // ------------------------------------------------------------------ per-address

    @Test
    @DisplayName("Under the per-address threshold the request proceeds")
    void allowsAnAddressUnderTheThreshold() {
        when(attempts.countIpFailuresSince(eq(IP), any())).thenReturn(19L);

        assertThatCode(() -> service().assertAddressNotThrottled(IP, NOW))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("At the per-address threshold the request is refused with 429 account-locked")
    void refusesAnAddressOverTheThreshold() {
        when(attempts.countIpFailuresSince(eq(IP), any())).thenReturn(20L);

        assertThatThrownBy(() -> service().assertAddressNotThrottled(IP, NOW))
            .isInstanceOf(TooManyLoginAttemptsException.class)
            .satisfies(thrown -> {
                TooManyLoginAttemptsException e = (TooManyLoginAttemptsException) thrown;
                assertThat(e.getStatus().value()).isEqualTo(429);
                assertThat(e.getSlug()).isEqualTo("account-locked");
            });
        verify(securityEvents).record(eq(SecurityEventType.LOCKOUT), eq(null), eq(null), any());
    }

    @Test
    @DisplayName("The window is the configured one, not since the beginning of time")
    void countsOverTheConfiguredWindow() {
        when(attempts.countIpFailuresSince(eq(IP), eq(NOW.minus(Duration.ofMinutes(10)))))
            .thenReturn(0L);

        service().assertAddressNotThrottled(IP, NOW);

        verify(attempts).countIpFailuresSince(IP, NOW.minus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("A counting failure refuses the request — the limiter is fail-CLOSED")
    void failsClosedWhenTheCountCannotBeTaken() {
        when(attempts.countIpFailuresSince(anyString(), any()))
            .thenThrow(new QueryTimeoutException("the database is unhappy"));

        assertThatThrownBy(() -> service().assertAddressNotThrottled(IP, NOW))
            .as("the pre-collapse limiter logged 'failing open' here, which made an outage of the "
                + "counting store into a way of switching brute-force protection off (gap P2-5)")
            .isInstanceOf(TooManyLoginAttemptsException.class);
    }

    @Test
    @DisplayName("An unresolvable address is REFUSED, not waved through")
    void refusesAnUnattributableAddress() {
        assertThatThrownBy(() -> service().assertAddressNotThrottled(null, NOW))
            .as("ClientIpResolver answers null for an empty or implausibly long value, and "
                + "RemoteIpValve does not check that the X-Forwarded-For entry it settles on is "
                + "a syntactically valid address. Returning here made the one control that still "
                + "binds after V12 raised the login rule switchable off by anybody able to "
                + "produce such a value — and it contradicted this method's own fail-closed "
                + "javadoc")
            .isInstanceOf(TooManyLoginAttemptsException.class);
        assertThatThrownBy(() -> service().assertAddressNotThrottled("  ", NOW))
            .isInstanceOf(TooManyLoginAttemptsException.class);
        verify(attempts, never()).countIpFailuresSince(anyString(), any());
    }

    @Test
    @DisplayName("Every refusal carries the Retry-After the spec promises on a 429")
    void everyRefusalCarriesARetryAfter() {
        when(attempts.countIpFailuresSince(eq(IP), any())).thenReturn(20L);

        assertThatThrownBy(() -> service().assertAddressNotThrottled(IP, NOW))
            .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, thrown ->
                assertThat(thrown.getRetryAfterSeconds())
                    .as("the counting window is when the oldest of the failures being counted "
                        + "falls out of it; any shorter answer invites a retry the gate is "
                        + "certain to refuse again")
                    .isEqualTo(Duration.ofMinutes(10).toSeconds()));
        assertThatThrownBy(() -> service().assertAddressNotThrottled(null, NOW))
            .isInstanceOfSatisfying(TooManyLoginAttemptsException.class, thrown ->
                assertThat(thrown.getRetryAfterSeconds()).isPositive());
    }
}
