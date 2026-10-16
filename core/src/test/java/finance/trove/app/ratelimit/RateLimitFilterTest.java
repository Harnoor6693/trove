package finance.trove.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.exception.ErrorResponseFactory;
import finance.trove.common.exception.SecurityErrorWriter;
import finance.trove.common.json.TroveJsonModule;
import finance.trove.users.UserRole;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The filter's three decisions: who is exempt, what a refusal looks like on the wire, and — the
 * one that matters most — which way it fails when it cannot decide at all.
 *
 * <p>The pre-collapse filter caught every exception and let the request through with an ERROR line
 * reading "failing open". On {@code /v1/auth/**} that turns an outage of the counting store into a
 * way of switching brute-force protection off, which is why {@link #theSignInSurfaceFailsClosed()}
 * exists and why it is the assertion to keep if this class is ever trimmed.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final RateLimitProperties ENABLED =
        new RateLimitProperties(true, Duration.ofMinutes(5), Duration.ofMinutes(10), 1000, 60);

    private static final RateLimitRule LOGIN_RULE =
        new RateLimitRule("/api/v1/auth/login", 60, 120);

    @Mock
    private RateLimitService limiter;

    private final SecurityErrorWriter errorWriter = new SecurityErrorWriter(
        JsonMapper.builder().addModule(new TroveJsonModule()).build(),
        new ErrorResponseFactory());

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContextPath("/api");
        request.setRemoteAddr("198.18.0.7");
        return request;
    }

    private RateLimitFilter filter(RateLimitProperties properties) {
        return new RateLimitFilter(limiter, properties, errorWriter);
    }

    @Test
    @DisplayName("An allowed request is passed straight through")
    void allowedRequestsProceed() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.allowed(LOGIN_RULE));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/auth/login"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("A refusal is 429 rate-limited, carries Retry-After, and stops the chain")
    void refusalShape() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.refused(LOGIN_RULE, 60));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/auth/login"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After"))
            .as("both clients need to know how long to wait; without it the only strategy left "
                + "is to retry immediately, which is what produced the 429")
            .isEqualTo("60");
        assertThat(response.getContentAsString())
            .contains("\"success\":false")
            .contains("https://trove.finance/errors/rate-limited");
    }

    @Test
    @DisplayName("The sign-in surface FAILS CLOSED when the limiter cannot decide")
    void theSignInSurfaceFailsClosed() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenThrow(new IllegalStateException("bucket store is broken"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/auth/login"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus())
            .as("a limiter fault must not be a way of reaching the sign-in surface unmetered "
                + "(gap P2-5); the pre-collapse filter logged \"failing open\" here")
            .isEqualTo(503);
        assertThat(response.getContentAsString())
            .contains("https://trove.finance/errors/service-unavailable");
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("Everything that is not the sign-in surface fails OPEN")
    void theLedgerSurfaceFailsOpen() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenThrow(new IllegalStateException("bucket store is broken"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/admin/users"), response, chain);

        verify(chain)
            .doFilter(any(), any());
        assertThat(response.getStatus())
            .as("a limiter fault must not lock a household out of its own ledger, and there is no "
                + "credential to guess at behind these routes")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("GET /v1/status and the actuator are never limited")
    void theProbesAreExempt() {
        assertThat(RateLimitFilter.isExempt(request("/api/v1/status")))
            .as("the SPA's degraded-state banner polls this continuously")
            .isTrue();
        MockHttpServletRequest actuator = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(RateLimitFilter.isExempt(actuator))
            .as("rate-limiting the liveness probe means the container restarts itself under load")
            .isTrue();
        assertThat(RateLimitFilter.isExempt(request("/api/v1/auth/login"))).isFalse();
    }

    @Test
    @DisplayName("An exempt path never reaches the limiter at all")
    void exemptPathsSkipTheLimiter() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest status = new MockHttpServletRequest("GET", "/api/v1/status");
        status.setContextPath("/api");

        filter(ENABLED).doFilter(status, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(limiter, never()).consume(any(), any());
    }

    @Test
    @DisplayName("The master switch really switches it off")
    void theSwitchIsRead() throws Exception {
        RateLimitProperties off =
            new RateLimitProperties(false, Duration.ofMinutes(5), Duration.ofMinutes(10), 10, 60);
        FilterChain chain = mock(FilterChain.class);

        filter(off).doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(limiter, never()).consume(any(), any());
    }

    @Test
    @DisplayName("An unlimited path proceeds without a Retry-After header")
    void unlimitedPathsCarryNoRetryAfter() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.unlimited());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/admin/users"), response,
            mock(FilterChain.class));

        assertThat(response.getHeader("Retry-After")).isNull();
    }

    // ------------------------------------------------------- the path the decisions are made on

    @Test
    @DisplayName("The rule is matched on the DECODED path, not the raw one the client spelled")
    void theRuleIsMatchedOnTheDecodedPath() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.allowed(LOGIN_RULE));
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);

        filter(ENABLED).doFilter(request("/api/v1/auth/regist%65r"), new MockHttpServletResponse(),
            mock(FilterChain.class));

        verify(limiter).consume(anyString(), path.capture());
        assertThat(path.getValue())
            .as("getRequestURI() is never decoded, but Spring routes on the decoded path: "
                + "/api/v1/auth/regist%%65r reaches the register handler while matching only "
                + "/api/v1/auth/**, so V12's 20/40 row for that route is replaced by the "
                + "wildcard's 60/120 — a limit a client chooses for itself by spelling a letter")
            .isEqualTo("/api/v1/auth/register");
    }

    @Test
    @DisplayName("An encoded spelling of a sign-in route still fails CLOSED")
    void anEncodedSignInPathStillFailsClosed() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenThrow(new RateLimitUnavailableException("no rules loaded"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/%61uth/login"), response,
            mock(FilterChain.class));

        assertThat(response.getStatus())
            .as("/api/v1/%%61uth/login reaches the login handler; matched raw it does not start "
                + "with /v1/auth/, so the sign-in surface silently reverted to failing open for "
                + "that spelling")
            .isEqualTo(503);
    }

    @Test
    @DisplayName("An encoded spelling of /v1/status is still exempt")
    void anEncodedStatusPathIsStillExempt() {
        assertThat(RateLimitFilter.isExempt(request("/api/v1/statu%73")))
            .as("otherwise the exemption is only as good as the client's spelling")
            .isTrue();
    }

    @Test
    @DisplayName("Decoding is per segment: matrix parameters go, an encoded slash stays put")
    void decodingIsPerSegment() {
        assertThat(RateLimitFilter.decode("/api/v1/auth/login;jsessionid=abc"))
            .as("a matrix parameter is not part of the path the router matched")
            .isEqualTo("/api/v1/auth/login");
        assertThat(RateLimitFilter.decode("/api/v1/auth/lo%2Fgin"))
            .as("an encoded slash is one character inside ONE segment for the dispatcher; "
                + "letting it become a separator here would match a pattern the router never "
                + "considered")
            .isEqualTo("/api/v1/auth/lo%2Fgin");
        assertThat(RateLimitFilter.decode("/api/v1/auth/%zz"))
            .as("a malformed escape — Tomcat answers 400 before the chain runs, and guessing at "
                + "one here would invent a path nobody sent")
            .isEqualTo("/api/v1/auth/%zz");
        assertThat(RateLimitFilter.decode(null)).isEmpty();
    }

    @Test
    @DisplayName("A limiter that has never loaded a rule refuses the sign-in surface")
    void unloadedRulesRefuseTheSignInSurface() throws Exception {
        RateLimitRules neverLoaded = new RateLimitRules(mock(JdbcTemplate.class));
        RateLimitFilter filter = new RateLimitFilter(
            new RateLimitService(neverLoaded, ENABLED), ENABLED, errorWriter);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        MockHttpServletResponse allowed = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/auth/login"), refused, chain);
        filter.doFilter(request("/api/v1/admin/users"), allowed, chain);

        assertThat(refused.getStatus())
            .as("an empty rule list used to read as 'nothing is limited', so the whole sign-in "
                + "surface was unmetered between the connector accepting requests and the "
                + "reload job's first execution — and for ever if that reload failed")
            .isEqualTo(503);
        assertThat(allowed.getStatus())
            .as("and the ledger surface still passes: §5.8's asymmetry, not a blanket outage")
            .isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("The envelope on a refusal is the one both clients already parse")
    void theRefusalIsEnveloped() throws Exception {
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.refused(LOGIN_RULE, 60));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(ENABLED).doFilter(request("/api/v1/auth/login"), response,
            mock(FilterChain.class));

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
            .as("ApiResponse<%s>, exactly as every other refusal on this API",
                ApiResponse.class.getSimpleName())
            .contains("\"error\":")
            .contains("\"status\":429");
    }

    @Test
    @DisplayName("The public sign-in routes are metered by address even with a valid token")
    void thePublicSignInRoutesAreMeteredByAddress() throws Exception {
        TroveUserDetails principal = new TroveUserDetails(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "throwaway@trove.test", UserRole.MEMBER, Instant.now());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(limiter.consume(anyString(), anyString()))
            .thenReturn(RateLimitDecision.allowed(LOGIN_RULE));
        try {
            filter(ENABLED).doFilter(request("/api/v1/auth/login"), new MockHttpServletResponse(),
                mock(FilterChain.class));
            filter(ENABLED).doFilter(request("/api/v1/transactions"),
                new MockHttpServletResponse(), mock(FilterChain.class));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(limiter, description("B3-06: keyed on the token's user, one throwaway account "
            + "bought its own bucket on the brute-force surface, apart from its address's"))
            .consume("198.18.0.7", "/api/v1/auth/login");
        verify(limiter, description("everywhere else a signed-in caller keeps a bucket of their "
            + "own, so a household behind one NAT does not share a ceiling"))
            .consume(principal.getUserId().toString(), "/api/v1/transactions");
    }
}
