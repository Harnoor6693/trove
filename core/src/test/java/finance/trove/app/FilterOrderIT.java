package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.audit.AuditLogFilter;
import finance.trove.app.ratelimit.RateLimitFilter;
import finance.trove.app.support.BaseIT;
import finance.trove.auth.jwt.CookieOrBearerJwtAuthFilter;
import finance.trove.auth.jwt.CsrfCookieFilter;
import finance.trove.auth.jwt.CsrfRequiresHeaderFilter;
import finance.trove.common.observability.RequestIdMdcFilter;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.CorsFilter;

/**
 * The §5.1 filter order, asserted against the chain the application actually built.
 *
 * <p><strong>Why a test and not a comment.</strong> {@code SecurityConfig} has always named this
 * class in its javadoc — "{@code FilterOrderIT} asserts the resulting sequence" — and until this
 * file existed it did not, which made the one property the class calls "not cosmetic" a claim
 * with nothing behind it (confirmed review finding 7). {@code ArchitectureTest} only proves the
 * filters exist and are not {@code @Component}s; a chain re-ordered with every filter still
 * present passes every other test in the tree, and three later steps have to replay their own
 * edits onto this block.
 *
 * <p><strong>What re-ordering would actually cost.</strong> The limiter before the JWT filter sees
 * no principal, so every authenticated request falls into one flat anonymous bucket and V12's
 * per-route rows become unreachable. {@code CsrfRequiresHeaderFilter} before it has no
 * {@code trove.authSource} to read, so cookie-mode mutations stop being protected.
 * {@code CsrfCookieFilter} before {@code CsrfFilter} plants no cookie, so the first login 403s.
 * {@code AuditLogFilter} anywhere but last records a status the client never received. None of
 * those shows up as a compile error and only the third has an obvious symptom.
 */
class FilterOrderIT extends BaseIT {

    /** §5.1's sequence, in order. Every entry is asserted to appear exactly once. */
    private static final List<Class<? extends Filter>> EXPECTED = List.of(
        CorsFilter.class,
        CookieOrBearerJwtAuthFilter.class,
        RateLimitFilter.class,
        CsrfRequiresHeaderFilter.class,
        CsrfFilter.class,
        CsrfCookieFilter.class,
        AuditLogFilter.class);

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    @Qualifier("requestIdMdcFilterRegistration")
    private FilterRegistrationBean<RequestIdMdcFilter> requestIdMdcFilterRegistration;

    private List<Filter> chainFilters() {
        assertThat(springSecurityFilterChain).isInstanceOf(FilterChainProxy.class);
        List<SecurityFilterChain> chains = ((FilterChainProxy) springSecurityFilterChain)
            .getFilterChains();
        assertThat(chains)
            .as("§9 risk 1: one chain, and no package may declare a second one and opt itself out")
            .hasSize(1);
        return chains.get(0).getFilters();
    }

    @Test
    @DisplayName("The chain is exactly §5.1's order, and every filter in it appears once")
    void theChainIsInTheDocumentedOrder() {
        List<Filter> filters = chainFilters();
        List<String> names = filters.stream().map(f -> f.getClass().getSimpleName()).toList();

        List<Integer> positions = EXPECTED.stream()
            .map(expected -> {
                List<Filter> matches = filters.stream().filter(expected::isInstance).toList();
                assertThat(matches)
                    .as("%s must be in the chain exactly once — the chain is %s",
                        expected.getSimpleName(), names)
                    .hasSize(1);
                return filters.indexOf(matches.get(0));
            })
            .toList();

        assertThat(positions)
            .as("§5.1's order, which the chain is: %s", names)
            .isSorted();
    }

    @Test
    @DisplayName("Each custom filter has its own anchor, so no two share a position")
    void theCustomFiltersAreAnchoredSeparately() {
        List<Filter> filters = chainFilters();

        int jwt = indexOf(filters, CookieOrBearerJwtAuthFilter.class);
        int limiter = indexOf(filters, RateLimitFilter.class);
        int csrfHeader = indexOf(filters, CsrfRequiresHeaderFilter.class);
        int csrfCookie = indexOf(filters, CsrfCookieFilter.class);
        int audit = indexOf(filters, AuditLogFilter.class);

        assertThat(List.of(jwt, limiter, csrfHeader, csrfCookie, audit))
            .as("two filters registered against the SAME neighbour receive the same order value "
                + "and their relative order then depends on sort stability, i.e. on nothing "
                + "anybody wrote down. Four anchors — CorsFilter, CookieOrBearerJwtAuthFilter, "
                + "CsrfFilter, CsrfCookieFilter — for four filters")
            .doesNotHaveDuplicates()
            .isSorted();
        assertThat(filters.subList(audit + 1, filters.size()))
            .as("the audit filter wraps everything this application adds, so the status it "
                + "records is the one the client received: nothing of Trove's own may run "
                + "outside it. What follows is Spring Security's own tail — authorization, "
                + "exception translation — whose answers it therefore still measures")
            .allSatisfy(later -> assertThat(later.getClass().getName())
                .doesNotStartWith("finance.trove"));
    }

    @Test
    @DisplayName("RequestIdMdcFilter is registered once, outside the chain, ahead of all of it")
    void theRequestIdFilterIsOutsideAndFirst() {
        assertThat(chainFilters())
            .as("it is registered by ObservabilityConfig with a FilterRegistrationBean, not by "
                + "the security chain: a filter that is both a bean and a Filter is registered "
                + "twice by Boot (§9 risk 8)")
            .noneMatch(RequestIdMdcFilter.class::isInstance);
        assertThat(requestIdMdcFilterRegistration.getOrder())
            .as("HIGHEST_PRECEDENCE is what gives a 401 written by TroveAuthenticationEntryPoint "
                + "the same requestId as a 200 written by a controller")
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        throw new AssertionError(type.getSimpleName() + " is not in the security filter chain");
    }
}
