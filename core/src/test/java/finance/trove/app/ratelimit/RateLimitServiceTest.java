package finance.trove.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Bucket behaviour: capacity, isolation between identities, and isolation between rules.
 *
 * <p>The isolation assertions are the ones worth having. A limiter whose buckets are keyed too
 * broadly refuses innocent callers; one whose buckets are keyed too narrowly — per URI rather than
 * per pattern, the easy mistake — refuses nobody at all while looking, from the code, exactly the
 * same.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    private static final RateLimitProperties PROPERTIES =
        new RateLimitProperties(true, Duration.ofMinutes(5), Duration.ofMinutes(10), 1000, 60);

    @Mock
    private RateLimitRules rules;

    private RateLimitService service() {
        return new RateLimitService(rules, PROPERTIES);
    }

    @Test
    @DisplayName("A caller may spend the burst capacity and is refused on the next request")
    void theBurstIsTheCeiling() {
        RateLimitRule rule = new RateLimitRule("/api/v1/auth/login", 1, 3);
        when(rules.ruleFor("/api/v1/auth/login")).thenReturn(Optional.of(rule));
        RateLimitService service = service();

        for (int i = 1; i <= 3; i++) {
            assertThat(service.consume("198.18.0.1", "/api/v1/auth/login").allowed())
                .as("request %s is inside the burst of 3", i)
                .isTrue();
        }

        RateLimitDecision refused = service.consume("198.18.0.1", "/api/v1/auth/login");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(60);
        assertThat(refused.rule()).isEqualTo(rule);
    }

    @Test
    @DisplayName("Two identities do not share a bucket")
    void identitiesAreIsolated() {
        when(rules.ruleFor("/api/v1/auth/login"))
            .thenReturn(Optional.of(new RateLimitRule("/api/v1/auth/login", 1, 1)));
        RateLimitService service = service();

        assertThat(service.consume("198.18.0.1", "/api/v1/auth/login").allowed()).isTrue();
        assertThat(service.consume("198.18.0.1", "/api/v1/auth/login").allowed()).isFalse();
        assertThat(service.consume("198.18.0.2", "/api/v1/auth/login").allowed())
            .as("one address exhausting its bucket must not refuse a different one")
            .isTrue();
    }

    @Test
    @DisplayName("One identity's buckets on two different rules are separate")
    void rulesAreIsolated() {
        when(rules.ruleFor("/api/v1/auth/login"))
            .thenReturn(Optional.of(new RateLimitRule("/api/v1/auth/login", 1, 1)));
        when(rules.ruleFor("/api/v1/admin/users"))
            .thenReturn(Optional.of(new RateLimitRule("/api/**", 600, 1200)));
        RateLimitService service = service();

        assertThat(service.consume("u", "/api/v1/auth/login").allowed()).isTrue();
        assertThat(service.consume("u", "/api/v1/auth/login").allowed()).isFalse();
        assertThat(service.consume("u", "/api/v1/admin/users").allowed())
            .as("spending the login bucket must not lock the same caller out of the ledger")
            .isTrue();
    }

    @Test
    @DisplayName("Two URIs matching one pattern share that pattern's bucket")
    void oneBucketPerPatternNotPerUri() {
        RateLimitRule rule = new RateLimitRule("/api/**", 2, 2);
        when(rules.ruleFor("/api/v1/admin/users/a")).thenReturn(Optional.of(rule));
        when(rules.ruleFor("/api/v1/admin/users/b")).thenReturn(Optional.of(rule));
        RateLimitService service = service();

        assertThat(service.consume("u", "/api/v1/admin/users/a").allowed()).isTrue();
        assertThat(service.consume("u", "/api/v1/admin/users/b").allowed()).isTrue();
        assertThat(service.consume("u", "/api/v1/admin/users/a").allowed())
            .as("a bucket per URI would be no limit at all: every id is a fresh bucket")
            .isFalse();
    }

    @Test
    @DisplayName("A path no rule covers is allowed and consumes no bucket")
    void unmatchedPathsAreUnlimited() {
        when(rules.ruleFor("/actuator/prometheus")).thenReturn(Optional.empty());
        RateLimitService service = service();

        RateLimitDecision decision = service.consume("u", "/actuator/prometheus");
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.rule()).isNull();
        assertThat(service.liveBuckets())
            .as("an unlimited path must not populate the bucket map, or the ceiling on live "
                + "buckets is spent on traffic that is never counted")
            .isZero();
    }

    @Test
    @DisplayName("A null identity still gets a bucket rather than a NullPointerException")
    void anonymousWithNoResolvableAddress() {
        when(rules.ruleFor("/api/v1/auth/login"))
            .thenReturn(Optional.of(new RateLimitRule("/api/v1/auth/login", 1, 1)));
        RateLimitService service = service();

        assertThat(service.consume(null, "/api/v1/auth/login").allowed()).isTrue();
        assertThat(service.consume(null, "/api/v1/auth/login").allowed()).isFalse();
    }

    @Test
    @DisplayName("The bucket key cannot be spelled by one identity to collide with another's")
    void bucketKeysCannotCollide() {
        RateLimitRule login = new RateLimitRule("/api/v1/auth/login", 60, 120);
        assertThat(RateLimitService.key("abc", login))
            .isEqualTo("abc|/api/v1/auth/login|60:120");
        assertThat(RateLimitService.key(null, login))
            .isEqualTo("-|/api/v1/auth/login|60:120");
    }

    @Test
    @DisplayName("Raising a limit takes effect at once rather than one bucket-TTL later")
    void anEditedRuleGetsAFreshBucket() {
        when(rules.ruleFor("/api/v1/auth/providers"))
            .thenReturn(Optional.of(new RateLimitRule("/api/v1/auth/providers", 1, 1)));
        RateLimitService service = service();

        assertThat(service.consume("198.18.0.1", "/api/v1/auth/providers").allowed()).isTrue();
        assertThat(service.consume("198.18.0.1", "/api/v1/auth/providers").allowed()).isFalse();

        // The admin raises the ceiling and the reload job publishes it.
        when(rules.ruleFor("/api/v1/auth/providers"))
            .thenReturn(Optional.of(new RateLimitRule("/api/v1/auth/providers", 60, 120)));

        assertThat(service.consume("198.18.0.1", "/api/v1/auth/providers").allowed())
            .as("a bucket carries the configuration it was built with, so keying on the pattern "
                + "alone would leave an operator watching the reload report the new rule while "
                + "the old refusals continued for another ten minutes")
            .isTrue();
    }

    @Test
    @DisplayName("\"I cannot tell\" is passed on to the filter, never softened into \"unlimited\"")
    void anUnavailableRuleSetPropagates() {
        when(rules.ruleFor("/api/v1/auth/login"))
            .thenThrow(new RateLimitUnavailableException("no rules have been loaded yet"));
        RateLimitService service = service();

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.consume("198.18.0.1", "/api/v1/auth/login"))
            .as("the filter has to be able to tell 'you are over your limit' from 'I could not "
                + "tell', because the two answers differ on /v1/auth/**: not knowing means "
                + "refusing (§5.8). Catching this and answering unlimited() would put the "
                + "sign-in surface back on the fail-open path")
            .isInstanceOf(RateLimitUnavailableException.class);
    }

    @Test
    @DisplayName("The properties record supplies sane defaults rather than zeroes")
    void propertyDefaults() {
        RateLimitProperties defaults = new RateLimitProperties(true, null, null, 0, 0);
        assertThat(defaults.reloadInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.bucketTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.maxBuckets()).isEqualTo(100_000);
        assertThat(defaults.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("A nonsensical interval fails the boot rather than the first request")
    void impossibleIntervalsAreRefusedAtStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RateLimitProperties(
                true, Duration.ZERO, Duration.ofMinutes(10), 10, 60))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reload-interval");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RateLimitProperties(
                true, Duration.ofMinutes(5), Duration.ofMinutes(-1), 10, 60))
            .as("a record's canonical constructor runs during context refresh, which is where a "
                + "bad value should surface (§1.6)")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bucket-ttl");
    }
}
