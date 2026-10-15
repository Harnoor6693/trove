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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Readiness gap P2-5, both halves, and B3-05's policy.
 *
 * <p>The account half throttles one address for one account after repeated failures, and every
 * address the account has not been signed in from once the account passes its hourly ceiling. The network half — the one an
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
        100, Duration.ofHours(1), 20, Duration.ofMinutes(10));

    private LockoutService service() {
        return new LockoutService(attempts, credentials, properties, securityEvents);
    }

    private static LocalCredential credential() {
        return new LocalCredential(UUID.randomUUID(), "$argon2id$stub", NOW);
    }

    // ------------------------------------------------------------------ per-account

    /** Failures from {@link #IP} for any account, oldest first, as the repository answers. */
    private void failuresAt(Instant... at) {
        when(attempts.failuresFromSince(anyString(), eq(IP), any())).thenReturn(List.of(at));
    }

    @Test
    @DisplayName("Below the threshold the counter rises but no lock is set")
    void countsWithoutLockingBelowTheThreshold() {
        failuresAt(NOW.minusSeconds(120), NOW.minusSeconds(60), NOW);
        LocalCredential credential = credential();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        service().recordFailure("a@trove.test", IP, UUID.randomUUID(), NOW);

        assertThat(credential.getFailedAttempts()).isEqualTo(3);
        assertThat(credential.getLockedUntil()).isNull();
        assertThat(service().isLocked("a@trove.test", IP, NOW)).isFalse();
        verify(securityEvents, never()).record(eq(SecurityEventType.LOCKOUT), any(), any());
    }

    @Test
    @DisplayName("The fifth failure in a run throttles that address and writes LOCKOUT")
    void locksAtTheThreshold() {
        failuresAt(NOW.minusSeconds(4), NOW.minusSeconds(3), NOW.minusSeconds(2),
            NOW.minusSeconds(1), NOW);
        LocalCredential credential = credential();
        UUID userId = UUID.randomUUID();
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential));

        service().recordFailure("a@trove.test", IP, userId, NOW);

        assertThat(credential.getLockedUntil()).isEqualTo(NOW.plusSeconds(30));
        assertThat(service().isLocked("a@trove.test", IP, NOW.plusSeconds(10))).isTrue();
        assertThat(service().isLocked("a@trove.test", IP, NOW.plusSeconds(31))).isFalse();
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
    @DisplayName("There is no long lock: however many failures, a throttle ends within maxBackoff")
    void thereIsNoLongLock() {
        Instant[] run = new Instant[40];
        for (int i = 0; i < run.length; i++) {
            run[i] = NOW.minusSeconds(run.length - 1 - i);
        }
        failuresAt(run);
        LocalCredential credential = credential();
        when(credentials.findByUserId(any())).thenReturn(Optional.of(credential));

        service().recordFailure("a@trove.test", IP, UUID.randomUUID(), NOW);

        assertThat(credential.getLockedUntil())
            .as("the 24-hour lock this replaced was ten requests from anybody who knows the "
                + "address: a member signed out of their own finance server for a day")
            .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("A run does not decay while it is served; it ends after a quiet window")
    void aRunDoesNotDecayWhileItIsServed() {
        // A patient script: five quick guesses, then one each time the throttle lifts.
        List<Instant> run = new java.util.ArrayList<>();
        Instant at = NOW;
        for (int i = 0; i < 5; i++) {
            run.add(at);
            at = at.plusSeconds(1);
        }
        LockoutService service = service();
        for (int i = 0; i < 6; i++) {
            at = run.get(run.size() - 1).plus(service.backoffFor(run.size()));
            run.add(at);
        }
        Instant last = run.get(run.size() - 1);
        failuresAt(run.toArray(Instant[]::new));

        assertThat(service.throttle("a@trove.test", IP, last).until())
            .as("counted in a sliding fifteen-minute window, the oldest of these had already left "
                + "it, the count was back under five, and the script had five free guesses")
            .isEqualTo(last.plus(Duration.ofMinutes(15)));

        Instant quiet = last.plus(Duration.ofMinutes(15)).plus(Duration.ofMinutes(15));
        run.add(quiet.plusSeconds(1));
        failuresAt(run.toArray(Instant[]::new));
        assertThat(service.throttle("a@trove.test", IP, quiet.plusSeconds(1)))
            .as("a quiet window after the throttle ends the run")
            .isEqualTo(new LockoutService.Throttle(1, null));
    }

    @Test
    @DisplayName("Past the ceiling only addresses that signed in before get through")
    void theCeilingSparesKnownAddresses() {
        failuresAt();
        when(attempts.countFailuresSince(eq("a@trove.test"), eq(NOW.minus(Duration.ofHours(1)))))
            .thenReturn(100L);
        when(attempts.hasSucceededFrom("a@trove.test", IP)).thenReturn(false);

        assertThat(service().isLocked("a@trove.test", IP, NOW))
            .as("a botnet with one address per guess must still stop at ASVS's hundred an hour")
            .isTrue();

        when(attempts.hasSucceededFrom("a@trove.test", IP)).thenReturn(true);
        assertThat(service().isLocked("a@trove.test", IP, NOW))
            .as("and the owner at home keeps signing in while it does")
            .isFalse();
        assertThat(service().isLockedForSecondFactor("a@trove.test", IP, NOW))
            .as("but the second factor exempts nobody: a correct password is what made the "
                + "address known, so whoever reaches this step already has it")
            .isTrue();
    }

    @Test
    @DisplayName("Reaching the ceiling writes one LOCKOUT for the account")
    void reachingTheCeilingIsRecorded() {
        failuresAt(NOW);
        UUID userId = UUID.randomUUID();
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential()));
        when(attempts.countFailuresSince(eq("a@trove.test"), any())).thenReturn(100L);

        service().recordFailure("a@trove.test", IP, userId, NOW);

        verify(securityEvents).record(SecurityEventType.LOCKOUT, userId,
            Map.of("scope", "account", "failures", 100L));
    }

    @Test
    @DisplayName("An address with no account is still recorded, for the per-address count")
    void recordsAttemptsAgainstUnknownAddresses() {
        service().recordFailure("nobody@trove.test", IP, null, NOW);

        verify(attempts).record("nobody@trove.test", IP, false, NOW);
        verify(attempts, never()).failuresFromSince(anyString(), anyString(), any());
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
