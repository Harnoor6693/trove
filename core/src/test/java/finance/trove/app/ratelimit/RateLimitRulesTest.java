package finance.trove.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Rule resolution — which of the seeded patterns a given URI is measured against.
 *
 * <p>Getting this wrong is invisible: every request still gets <em>a</em> bucket, just the wrong
 * one, and the symptom is a limit nobody can reproduce.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitRulesTest {

    @Mock
    private JdbcTemplate jdbc;

    private RateLimitRules load(List<RateLimitRule> rules) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.copyOf(rules));
        RateLimitRules loaded = new RateLimitRules(jdbc);
        loaded.reload();
        return loaded;
    }

    @Test
    @DisplayName("The most specific pattern wins, not the first row the database returned")
    void longestPatternWins() {
        RateLimitRules rules = load(List.of(
            new RateLimitRule("/api/**", 600, 1200),
            new RateLimitRule("/api/v1/auth/**", 60, 120),
            new RateLimitRule("/api/v1/auth/login", 60, 120)));

        assertThat(rules.ruleFor("/api/v1/auth/login"))
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/v1/auth/login");
        assertThat(rules.ruleFor("/api/v1/auth/refresh"))
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/v1/auth/**");
        assertThat(rules.ruleFor("/api/v1/admin/users"))
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/**");
    }

    @Test
    @DisplayName("An exact pattern beats an equally long wildcard for the same path")
    void exactnessBeatsLength() {
        RateLimitRules rules = load(List.of(
            new RateLimitRule("/api/v1/auth/**", 60, 120),
            new RateLimitRule("/api/v1/auth/me", 5, 10)));

        assertThat(rules.ruleFor("/api/v1/auth/me"))
            .as("the two patterns are the same length, so a length-only comparator falls back to "
                + "alphabetical order, '*' sorts before 'm', and the exact route is silently "
                + "measured against the wildcard's much larger bucket — its own row never "
                + "applying to anything")
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/v1/auth/me");
        assertThat(rules.ruleFor("/api/v1/auth/refresh"))
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/v1/auth/**");
    }

    @Test
    @DisplayName("A single-segment wildcard beats a double-segment one")
    void singleWildcardBeatsDouble() {
        RateLimitRules rules = load(List.of(
            new RateLimitRule("/api/**", 600, 1200),
            new RateLimitRule("/api/v1/accounts/*/sync", 10, 20)));

        assertThat(rules.ruleFor("/api/v1/accounts/abc/sync"))
            .get().extracting(RateLimitRule::pattern).isEqualTo("/api/v1/accounts/*/sync");
    }

    @Test
    @DisplayName("A single-segment wildcard matches one segment and not a path below it")
    void singleSegmentWildcard() {
        RateLimitRules rules = load(List.of(new RateLimitRule("/api/v1/accounts/*/sync", 10, 20)));

        assertThat(rules.ruleFor("/api/v1/accounts/abc/sync")).isPresent();
        assertThat(rules.ruleFor("/api/v1/accounts/abc/def/sync")).isEmpty();
    }

    @Test
    @DisplayName("A path no rule matches is unlimited, and says so rather than defaulting")
    void unmatchedPathHasNoRule() {
        RateLimitRules rules = load(List.of(new RateLimitRule("/api/v1/auth/**", 60, 120)));

        assertThat(rules.ruleFor("/actuator/prometheus")).isEmpty();
        assertThat(rules.ruleFor(null))
            .as("a null URI must not throw inside a filter")
            .isEmpty();
    }

    @Test
    @DisplayName("A failed reload keeps the rules already in force, it does not clear them")
    void aFailedReloadKeepsTheOldRules() {
        RateLimitRules rules = load(List.of(new RateLimitRule("/api/**", 600, 1200)));

        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenThrow(new QueryTimeoutException("the database blinked"));

        assertThat(rules.reload()).isEqualTo(1);
        assertThat(rules.ruleFor("/api/v1/auth/login"))
            .as("dropping the rules because one read failed would switch the limiter off exactly "
                + "when the database is under strain")
            .isPresent();
    }

    @Test
    @DisplayName("Before the first reload the limiter refuses to guess, it does not answer 'no "
        + "rule'")
    void beforeTheFirstReload() {
        RateLimitRules rules = new RateLimitRules(jdbc);

        assertThat(rules.isLoaded()).isFalse();
        assertThatThrownBy(() -> rules.ruleFor("/api/v1/auth/login"))
            .as("an empty answer is a decision — 'this path is unlimited' — and RateLimitService "
                + "turns it into unlimited(), so answering it here would leave the sign-in "
                + "surface unmetered for the whole window between the connector accepting "
                + "requests and the first reload, and for ever if that reload fails. The "
                + "fail-closed 503 the spec publishes as LimiterUnavailable is only reachable "
                + "when the limiter throws")
            .isInstanceOf(RateLimitUnavailableException.class)
            .hasMessageContaining("/api/v1/auth/login");
    }

    @Test
    @DisplayName("A malformed row cannot escape the reload and freeze the loader")
    void aMalformedRowIsContained() {
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenThrow(new IllegalArgumentException("endpointPattern must not be blank"));
        RateLimitRules rules = new RateLimitRules(jdbc);

        assertThat(rules.reload())
            .as("endpoint_pattern is VARCHAR(200) NOT NULL and V12's CHECKs do not forbid '', so "
                + "one row from the admin editor can make RateLimitRule's constructor throw "
                + "inside the RowMapper — which Spring does not wrap as a DataAccessException. "
                + "Escaping here would leave the @Scheduled method throwing and the loader stuck")
            .isZero();
        assertThat(rules.isLoaded()).isFalse();
        assertThatThrownBy(() -> rules.ruleFor("/api/v1/auth/login"))
            .as("and a first load that failed must stay fail-closed rather than quietly "
                + "unlimited")
            .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    @DisplayName("A reload that fails AFTER a good one leaves the limiter loaded and enforcing")
    void aLaterFailureDoesNotUnloadTheLimiter() {
        RateLimitRules rules = load(List.of(new RateLimitRule("/api/**", 600, 1200)));

        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenThrow(new IllegalStateException("a row nobody expected"));
        rules.reload();

        assertThat(rules.isLoaded()).isTrue();
        assertThat(rules.ruleFor("/api/v1/auth/login")).isPresent();
    }

    @Test
    @DisplayName("The startup listener loads the rules once the application is serving")
    void theStartupListenerLoads() {
        when(jdbc.query(anyString(), any(RowMapper.class)))
            .thenReturn(List.of(new RateLimitRule("/api/**", 600, 1200)));
        RateLimitRules rules = new RateLimitRules(jdbc);

        rules.loadOnStartup();

        assertThat(rules.isLoaded())
            .as("the fixed-delay job's first execution is not instantaneous, and until it runs "
                + "every route is fail-closed on /v1/auth/** and unmetered everywhere else")
            .isTrue();
        assertThat(rules.ruleFor("/api/v1/anything")).isPresent();
    }

    @Test
    @DisplayName("A row whose capacity is below its rate is refused rather than silently applied")
    void aBucketThatCanNeverFillIsRejected() {
        assertThatThrownBy(() -> new RateLimitRule("/api/**", 100, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("burstCapacity must be >= requestsPerMinute");
    }

    @Test
    @DisplayName("A rule needs a pattern and a positive rate")
    void ruleValidation() {
        assertThatThrownBy(() -> new RateLimitRule(" ", 10, 20))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateLimitRule("/api/**", 0, 20))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
