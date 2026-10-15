package finance.trove.app.ratelimit;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

/**
 * The in-memory copy of {@code rate_limit_config} (V12), and the matcher that picks a rule for a
 * path.
 *
 * <p><strong>Not a Spring Data repository.</strong> Deliberately: the table is eight rows read in
 * full every five minutes and never queried by id, and every Spring Data interface in this
 * application has to extend a household-scoped base or be allowlisted ({@code ArchitectureTest}) —
 * a rule that exists to stop unscoped finders reaching ledger tables and that this table, which
 * has no tenant at all, would only be an exception to.
 *
 * <p>The cache is a {@code volatile} reference to an immutable list, so the reload job publishes a
 * new list atomically and the request threads reading it need no lock. A reload that fails keeps
 * the previous rules rather than falling back to none: "the database blinked" must not read as
 * "no limits apply".
 *
 * <h2>Never loaded is not the same as no rule matches</h2>
 * Until a reload has succeeded <em>once</em>, {@link #ruleFor} throws
 * {@link RateLimitUnavailableException} rather than answering "no rule". The difference is the
 * whole fail-closed contract: an empty answer is a decision — this path is unlimited — and
 * {@code RateLimitService} turns it into {@code unlimited()}, so a limiter that had never loaded
 * anything would silently pass every request on the sign-in surface through unmetered, which is
 * exactly what the 503 {@code LimiterUnavailable} in the spec promises it will not do. That state
 * is reachable in two ways that both happen in practice: the window between the connector
 * accepting requests and the first reload, and a first reload that fails.
 */
@Component
public class RateLimitRules {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitRules.class);

    private static final String SELECT =
        "SELECT endpoint_pattern, requests_per_min, burst_capacity FROM rate_limit_config";

    /**
     * The order rules are HELD in, for a readable listing and a deterministic
     * {@link #current()}: longest pattern first, ties broken alphabetically.
     *
     * <p>It is deliberately <strong>not</strong> how a rule is chosen for a request — see
     * {@link #ruleFor}. Length alone is what the pre-collapse service used and it is subtly wrong:
     * {@code /api/v1/auth/me} and {@code /api/v1/auth/**} are the same length, so the tie fell to
     * the alphabetical loser, {@code **} sorted before {@code me}, and an exact route was silently
     * measured against its wildcard's much larger bucket. That is invisible in production — every
     * request still gets a limit, just the wrong one — and it is exactly the class of defect that
     * makes a rate limit nobody can reproduce.
     */
    private static final Comparator<RateLimitRule> MOST_SPECIFIC_FIRST =
        Comparator.comparingInt((RateLimitRule rule) -> rule.pattern().length()).reversed()
            .thenComparing(RateLimitRule::pattern);

    private final JdbcTemplate jdbc;
    private final AntPathMatcher matcher = new AntPathMatcher();

    private volatile List<RateLimitRule> rules = List.of();

    /**
     * Set by the first successful reload and never cleared. A later failure keeps the rules it
     * already has (and stays loaded); a first failure leaves this false, and the limiter refuses
     * to guess.
     */
    private volatile boolean loaded;

    public RateLimitRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Re-reads every rule. Called once at startup and then on the {@code rate-limit-reload}
     * schedule (§5.6).
     *
     * @return how many rules are now in force
     */
    @Transactional(readOnly = true)
    public int reload() {
        try {
            List<RateLimitRule> fresh = jdbc.query(SELECT, (rs, row) -> new RateLimitRule(
                rs.getString("endpoint_pattern"),
                rs.getInt("requests_per_min"),
                rs.getInt("burst_capacity")))
                .stream()
                .sorted(MOST_SPECIFIC_FIRST)
                .toList();
            rules = fresh;
            loaded = true;
            LOG.debug("Rate limit rules reloaded: {} in force", fresh.size());
            return fresh.size();
        } catch (RuntimeException e) {
            // swallow-ok: enforcement continues with the rules already loaded, and with none at
            // all the limiter refuses the sign-in surface rather than passing it (see ruleFor).
            // Dropping the rules because one read failed would switch the limiter off exactly
            // when the database is under strain, which is the wrong direction to fail in (§5.8).
            //
            // RuntimeException, not DataAccessException: a single malformed row is not a database
            // error. `endpoint_pattern` is `VARCHAR(200) NOT NULL` and V12's CHECKs do not forbid
            // an empty string, so a row inserted by the admin editor V12 exists for can make
            // RateLimitRule's constructor throw IllegalArgumentException inside the RowMapper —
            // which Spring does not wrap — and that escaped the narrower catch, escaped the
            // @Scheduled method, and froze the loader on whatever it last held.
            LOG.error("Rate limit rule reload failed; continuing with the {} rules already loaded "
                + "(loaded={})", rules.size(), loaded, e);
            return rules.size();
        }
    }

    /**
     * One synchronous reload once the application is serving.
     *
     * <p>{@code ApplicationReadyEvent} rather than {@code @PostConstruct}: a bean that queries
     * during context refresh fails the boot whenever Postgres is a few seconds behind the
     * application, on a deployment whose whole job is to survive a database restart. By the time
     * this fires the pool is up, and {@link #reload()} cannot throw — so a database that is still
     * unavailable leaves the limiter unloaded (and therefore fail-closed on {@code /v1/auth/**})
     * instead of taking the process down.
     *
     * <p>It closes the window the {@code rate-limit-reload} fixed-delay job leaves open on every
     * boot: the connector accepts requests as soon as the context refreshes, and until the
     * scheduler's first execution nothing would have been metered.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        LOG.info("Rate limit rules loaded at startup: {} in force", reload());
    }

    /**
     * The most specific rule matching {@code uri}, or empty when none does.
     *
     * <p>Specificity is decided by {@link AntPathMatcher#getPatternComparator}, which is Spring's
     * own answer to the question the dispatcher asks on every request: an exact pattern beats one
     * with a single-segment wildcard, which beats one with {@code **}, and only then does length
     * break a tie. Sorting by length instead — the obvious implementation, and the one the
     * pre-collapse service shipped — makes {@code /api/v1/auth/**} win over the equally long
     * {@code /api/v1/auth/me}, so the exact route is measured against the wildcard's bucket and
     * its own row in {@code rate_limit_config} never applies to anything.
     *
     * @throws RateLimitUnavailableException when no reload has ever succeeded. Empty means "no
     *                                       rule covers this path"; it must never also mean "I do
     *                                       not know what the rules are"
     */
    public Optional<RateLimitRule> ruleFor(String uri) {
        if (!loaded) {
            throw new RateLimitUnavailableException(
                "No rate limit rules have been loaded yet: the limiter cannot decide about "
                    + uri + ". The sign-in surface refuses rather than passing (§5.8).");
        }
        if (uri == null) {
            return Optional.empty();
        }
        Comparator<String> mostSpecific = matcher.getPatternComparator(uri);
        return rules.stream()
            .filter(rule -> matcher.match(rule.pattern(), uri))
            .min((a, b) -> mostSpecific.compare(a.pattern(), b.pattern()));
    }

    /** The rules currently in force, most specific first. Test seam. */
    public List<RateLimitRule> current() {
        return rules;
    }

    /**
     * Whether a reload has ever succeeded. False means the limiter has no opinion at all, which
     * is a refusal on {@code /v1/auth/**} and a pass everywhere else.
     */
    public boolean isLoaded() {
        return loaded;
    }
}
