package finance.trove.app.schedule;

import finance.trove.app.audit.AuditLogWriter;
import finance.trove.app.ratelimit.RateLimitRules;
import finance.trove.auth.local.LoginAttemptRepository;
import finance.trove.auth.reset.PasswordResetService;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.common.time.Instants;
import finance.trove.households.invites.InviteService;
import java.time.Duration;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled sweeps ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.6).
 *
 * <p><strong>Every purge these call already existed and none of them was ever invoked.</strong>
 * {@code RefreshTokenService#purgeExpired}, {@code InviteService#purgeExpired},
 * {@code PasswordResetService#purgeExpired} and {@code LoginAttemptRepository#purgeBefore} were
 * written by collapse step 3, each with a comment saying "step 4 schedules it". Until this class
 * existed, four append-only tables grew without limit on every install — {@code login_attempt}
 * fastest of all, because it takes a row per failed login from anywhere on the internet.
 *
 * <p>The fifth job here sweeps {@code audit_log}, which this same collapse step introduces. It is
 * not in §5.6's list because the table is not either; it is here because a new append-only table
 * with no retention would be the very defect the paragraph above describes, added by the commit
 * that fixes it (confirmed review finding 6).
 *
 * <h2>Every method returns void, deliberately</h2>
 * ShedLock refuses to wrap a method with a return value: it cannot say what a locked-out
 * invocation should return, so it throws rather than inventing an answer. The counts go to the log
 * instead, which is where an operator looks for them, and the tests assert against the tables.
 *
 * <h2>Lock names are distinct, and that is load-bearing</h2>
 * ShedLock keys on the name alone, so two jobs sharing one would serialise into each other and the
 * second would silently never run while the first held the lock. {@link #LOCK_NAMES} is asserted
 * unique by {@code PurgeJobsTest}, and every name here appears in it.
 */
@Component
public class PurgeJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeJobs.class);

    /** Every ShedLock name this class takes. Read by the test that proves they are distinct. */
    public static final List<String> LOCK_NAMES = List.of(
        "refresh-token-purge",
        "invite-expiry-sweep",
        "password-reset-purge",
        "login-attempt-purge",
        "audit-log-purge");

    /**
     * How long a spent row is kept past its expiry before it is swept.
     *
     * <p>Not zero. A refresh token that expired four minutes ago is the token a client is about to
     * present in a race with its own rotation, and a reset link that expired a moment ago is the
     * one somebody is complaining about right now: deleting the row turns "this link has expired",
     * which is true and actionable, into the identical answer the endpoint gives a forged token.
     * The grace costs a day of rows.
     */
    static final Duration GRACE = Duration.ofDays(1);

    /**
     * {@code login_attempt} is evidence for the lockout window, which is measured in minutes, and
     * for an operator asking "was this address hammering us last week". Thirty days answers the
     * second question and is far longer than the first needs.
     */
    static final Duration LOGIN_ATTEMPT_RETENTION = Duration.ofDays(30);

    /**
     * How long an {@code audit_log} row is kept.
     *
     * <p>V11 takes a row per mutating request, for ever, and this class's own reason for existing
     * is that four other append-only tables had exactly that property. A year is the shortest
     * window that still answers the question the table is for — "who changed this, and when" —
     * across an annual cycle (a tax year, a yearly audit), and it is far longer than the
     * thirty days {@code login_attempt} keeps, because an audit row is evidence about a member of
     * the household rather than about the internet.
     */
    static final Duration AUDIT_RETENTION = Duration.ofDays(365);

    private final RefreshTokenService refreshTokens;
    private final InviteService invites;
    private final PasswordResetService passwordResets;
    private final LoginAttemptRepository loginAttempts;
    private final RateLimitRules rateLimitRules;
    private final AuditLogWriter auditLog;

    public PurgeJobs(RefreshTokenService refreshTokens, InviteService invites,
                     PasswordResetService passwordResets, LoginAttemptRepository loginAttempts,
                     RateLimitRules rateLimitRules, AuditLogWriter auditLog) {
        this.refreshTokens = refreshTokens;
        this.invites = invites;
        this.passwordResets = passwordResets;
        this.loginAttempts = loginAttempts;
        this.rateLimitRules = rateLimitRules;
        this.auditLog = auditLog;
    }

    /** §5.6 {@code refresh-token-purge}, daily at 03:10 UTC. */
    @Scheduled(cron = "0 10 3 * * *", zone = "UTC")
    @SchedulerLock(name = "refresh-token-purge", lockAtMostFor = "PT10M")
    public void purgeRefreshTokens() {
        LOG.info("refresh-token-purge removed {} expired sessions",
            refreshTokens.purgeExpired(Instants.now().minus(GRACE)));
    }

    /** §5.6 {@code invite-expiry-sweep}, daily at 03:20 UTC (gap P2-7). */
    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    @SchedulerLock(name = "invite-expiry-sweep", lockAtMostFor = "PT10M")
    public void purgeExpiredInvites() {
        LOG.info("invite-expiry-sweep removed {} expired invitations",
            invites.purgeExpired(Instants.now().minus(GRACE)));
    }

    /** The password-reset sibling of §5.6's {@code export-link-expiry-sweep}, 03:30 UTC. */
    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @SchedulerLock(name = "password-reset-purge", lockAtMostFor = "PT10M")
    public void purgeExpiredPasswordResets() {
        LOG.info("password-reset-purge removed {} expired reset links",
            passwordResets.purgeExpired(Instants.now().minus(GRACE)));
    }

    /** §5.6 {@code login-attempt-purge}, daily at 03:40 UTC (gap P2-5). */
    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    @SchedulerLock(name = "login-attempt-purge", lockAtMostFor = "PT10M")
    public void purgeLoginAttempts() {
        LOG.info("login-attempt-purge removed {} attempts older than {}",
            loginAttempts.purgeBefore(Instants.now().minus(LOGIN_ATTEMPT_RETENTION)),
            LOGIN_ATTEMPT_RETENTION);
    }

    /**
     * The retention sweep for {@code audit_log}, daily at 03:50 UTC.
     *
     * <p>Not one of §5.6's thirteen jobs, and deliberately added anyway: V11 is a new
     * append-only table introduced by this same step, and shipping it without a sweep would
     * reproduce the exact defect the four jobs above exist to fix (confirmed review finding 6).
     */
    @Scheduled(cron = "0 50 3 * * *", zone = "UTC")
    @SchedulerLock(name = "audit-log-purge", lockAtMostFor = "PT10M")
    public void purgeAuditLog() {
        LOG.info("audit-log-purge removed {} audit rows older than {}",
            auditLog.purgeBefore(Instants.now().minus(AUDIT_RETENTION)), AUDIT_RETENTION);
    }

    /**
     * §5.6 {@code rate-limit-reload}. Every five minutes, and once at startup — the fixed-delay
     * trigger fires immediately, which is how the rules are loaded at all: there is deliberately
     * no {@code @PostConstruct} reading the table, because a bean that queries during context
     * refresh fails the boot whenever Postgres is a few seconds behind the application, on a
     * deployment whose whole job is to survive a database restart.
     *
     * <p><strong>Not ShedLock-guarded, and that is a correction of §5.6's list.</strong> Every
     * other job here writes to the database, where running twice is the harm ShedLock prevents.
     * This one refreshes state that lives in <em>this</em> JVM's heap
     * ({@code RateLimitRules}'s volatile list). A lock on it would mean that with two instances
     * only one ever reloaded and the other enforced stale rules for ever — the lock would cause
     * exactly the divergence it is normally there to prevent. Running it on every instance is
     * correct and costs one eight-row read every five minutes.
     */
    @Scheduled(fixedDelayString = "${trove.rate-limit.reload-interval:PT5M}")
    public void reloadRateLimits() {
        LOG.debug("rate-limit-reload: {} rules in force", rateLimitRules.reload());
    }
}
