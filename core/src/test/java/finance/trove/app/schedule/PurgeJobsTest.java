package finance.trove.app.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.AuditLogWriter;
import finance.trove.app.ratelimit.RateLimitRules;
import finance.trove.auth.local.LoginAttemptRepository;
import finance.trove.auth.reset.PasswordResetService;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.households.invites.InviteService;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The wiring §5.6 asks for, asserted structurally.
 *
 * <p>Four purge methods existed since collapse step 3 and none of them was ever called; the
 * failure was invisible because there is nothing to see — the tables simply grow. So the
 * assertions here are about the annotations as much as the behaviour: a {@code @Scheduled} that
 * lost its {@code @SchedulerLock}, or two jobs that came to share a lock name, are both silent
 * faults that only a test naming them catches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurgeJobsTest {

    @Mock
    private RefreshTokenService refreshTokens;
    @Mock
    private InviteService invites;
    @Mock
    private PasswordResetService passwordResets;
    @Mock
    private LoginAttemptRepository loginAttempts;
    @Mock
    private RateLimitRules rateLimitRules;
    @Mock
    private AuditLogWriter auditLog;

    @InjectMocks
    private PurgeJobs jobs;

    @Test
    @DisplayName("Every ShedLock name is distinct")
    void lockNamesAreDistinct() {
        assertThat(new HashSet<>(PurgeJobs.LOCK_NAMES))
            .as("ShedLock keys on the name alone, so two jobs sharing one would serialise into "
                + "each other and the second would silently never run")
            .hasSameSizeAs(PurgeJobs.LOCK_NAMES);
    }

    @Test
    @DisplayName("Every @SchedulerLock on the class is declared in LOCK_NAMES, and vice versa")
    void theDeclaredNamesAreTheRealOnes() {
        Set<String> onMethods = new HashSet<>();
        for (Method method : PurgeJobs.class.getDeclaredMethods()) {
            SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
            if (lock != null) {
                onMethods.add(lock.name());
            }
        }
        assertThat(onMethods)
            .as("a list that has drifted from the annotations proves nothing about them")
            .containsExactlyInAnyOrderElementsOf(PurgeJobs.LOCK_NAMES);
    }

    @Test
    @DisplayName("Every database sweep is both scheduled and lock-guarded")
    void everySweepIsScheduledAndLocked() {
        List<String> unguarded = new ArrayList<>();
        for (Method method : PurgeJobs.class.getDeclaredMethods()) {
            if (method.getAnnotation(Scheduled.class) == null
                || "reloadRateLimits".equals(method.getName())) {
                continue;
            }
            if (method.getAnnotation(SchedulerLock.class) == null) {
                unguarded.add(method.getName());
            }
        }
        assertThat(unguarded)
            .as("a sweep without a lock runs on every instance and, on a rolling restart, twice")
            .isEmpty();
    }

    @Test
    @DisplayName("The rate-limit reload is deliberately NOT lock-guarded")
    void theReloadIsNotLockGuarded() throws Exception {
        Method reload = PurgeJobs.class.getDeclaredMethod("reloadRateLimits");

        assertThat(reload.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(reload.getAnnotation(SchedulerLock.class))
            .as("it refreshes state in THIS JVM's heap. A lock would mean that with two instances "
                + "only one ever reloaded and the other enforced stale rules for ever — the lock "
                + "causing the divergence it normally prevents")
            .isNull();
    }

    @Test
    @DisplayName("Every trigger fires on UTC, not the container's local zone")
    void everyCronFiresOnUtc() {
        List<String> wrongZone = new ArrayList<>();
        for (Method method : PurgeJobs.class.getDeclaredMethods()) {
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            if (scheduled != null && !scheduled.cron().isEmpty()
                && !"UTC".equals(scheduled.zone())) {
                wrongZone.add(method.getName());
            }
        }
        assertThat(wrongZone)
            .as("§5.6: the trigger fires on UTC; what a job COMPUTES resolves per user from "
                + "users.timezone (gap P2-15). Conflating the two is what made every pre-collapse "
                + "digest arrive at the wrong local hour")
            .isEmpty();
    }

    @Test
    @DisplayName("Each sweep purges past its own cutoff, and the cutoff is in the past")
    void eachSweepPurgesWithAGrace() {
        Instant before = Instant.now();

        jobs.purgeRefreshTokens();
        jobs.purgeExpiredInvites();
        jobs.purgeExpiredPasswordResets();
        jobs.purgeLoginAttempts();

        ArgumentCaptor<Instant> refresh = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokens).purgeExpired(refresh.capture());
        assertThat(refresh.getValue())
            .as("deleting a row the moment it expires turns \"this link has expired\" into the "
                + "same answer a forged token gets")
            .isBefore(before);

        ArgumentCaptor<Instant> attempts = ArgumentCaptor.forClass(Instant.class);
        verify(loginAttempts).purgeBefore(attempts.capture());
        assertThat(attempts.getValue())
            .as("login_attempt is kept far longer than the lockout window needs, so an operator "
                + "can still ask what happened last week")
            .isBefore(before.minus(PurgeJobs.GRACE));

        verify(invites).purgeExpired(any());
        verify(passwordResets).purgeExpired(any());
    }

    @Test
    @DisplayName("audit_log is swept too, past a cutoff a year in the past")
    void theAuditLogIsSwept() {
        Instant before = Instant.now();

        jobs.purgeAuditLog();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(auditLog).purgeBefore(cutoff.capture());
        assertThat(cutoff.getValue())
            .as("V11 takes a row per mutating request for ever; a fifth append-only table with no "
                + "sweep is the exact defect the other four jobs exist to fix")
            .isBefore(before.minus(PurgeJobs.AUDIT_RETENTION).plusSeconds(60))
            .isAfter(before.minus(PurgeJobs.AUDIT_RETENTION).minusSeconds(60));
    }

    @Test
    @DisplayName("The reload delegates to the rules holder")
    void theReloadDelegates() {
        when(rateLimitRules.reload()).thenReturn(8);

        jobs.reloadRateLimits();

        verify(rateLimitRules).reload();
    }
}
