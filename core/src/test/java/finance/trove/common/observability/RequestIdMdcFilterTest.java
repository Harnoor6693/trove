package finance.trove.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private Logger filterLogger;

    @BeforeEach
    void captureCompletionLines() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestIdMdcFilter.class);
        captured.start();
        filterLogger.addAppender(captured);
    }

    @AfterEach
    void clear() {
        filterLogger.detachAppender(captured);
        captured.stop();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("generates an id, puts it in the MDC for the chain, and echoes it in the header")
    void generatesAndEchoesAnId() throws Exception {
        String[] seenInsideChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
            seenInsideChain[0] = MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID));

        assertThat(seenInsideChain[0]).isNotNull();
        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .isEqualTo(seenInsideChain[0]);
        assertThat(request.getAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE))
            .isEqualTo(seenInsideChain[0]);
    }

    @Test
    @DisplayName("a well-formed inbound X-Request-Id is honoured, so a hop keeps one correlation id")
    void honoursAWellFormedInboundId() throws Exception {
        String inbound = UUID.randomUUID().toString();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, inbound);

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER)).isEqualTo(inbound);
    }

    @ParameterizedTest(name = "a request id of [{0}] is replaced with a fresh UUID")
    @NullSource
    @ValueSource(strings = {
        "",
        "not-a-uuid",
        "../../etc/passwd",
        "11111111-1111-1111-1111-111111111111 injected",
        "11111111-1111-1111-1111-11111111111",
    })
    @DisplayName("an id that is not a plain UUID is never echoed — it lands in logs and error bodies")
    void refusesAnyIdThatIsNotAUuid(String header) {
        UUID generated = RequestIdMdcFilter.parseOrGenerate(header);

        assertThat(generated).isNotNull();
        assertThat(generated.toString()).isNotEqualTo(header);
    }

    @Test
    @DisplayName("the MDC is cleared even when the chain throws, so a pooled thread cannot leak it")
    void clearsTheMdcWhenTheChainThrows() {
        FilterChain boom = (req, res) -> {
            throw new ServletException("boom");
        };

        try {
            filter.doFilter(request, response, boom);
        } catch (ServletException | IOException expected) {
            assertThat(expected).hasMessageContaining("boom");
        }

        assertThat(MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdMdcFilter.MDC_USER_ID)).isNull();
    }

    @Test
    @DisplayName("an authenticated principal reaches the completion line as userId")
    void recordsTheAuthenticatedPrincipal() throws Exception {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userId.toString(), "n/a",
                AuthorityUtils.createAuthorityList("ROLE_USER")));

        filter.doFilter(request, response, noopChain());

        assertThat(captured.list).singleElement().satisfies(event -> {
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdMdcFilter.MDC_USER_ID,
                userId.toString());
            assertThat(event.getMDCPropertyMap())
                .containsKey(RequestIdMdcFilter.MDC_REQUEST_ID);
        });
    }

    @Test
    @DisplayName("an anonymous principal is not recorded as a user")
    void doesNotRecordAnonymousAsAUser() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(request, response, noopChain());

        assertThat(captured.list).singleElement().satisfies(event ->
            assertThat(event.getMDCPropertyMap())
                .doesNotContainKey(RequestIdMdcFilter.MDC_USER_ID));
    }

    @Test
    @DisplayName("the completion line's level tracks the status: INFO, WARN, then ERROR")
    void logsEveryStatusBandAtItsOwnLevel() throws Exception {
        for (int status : new int[] {200, 404, 500}) {
            MockHttpServletRequest each = new MockHttpServletRequest("GET", "/api/v1/x");
            each.setContextPath("/api");
            each.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/x");
            filter.doFilter(each, new MockHttpServletResponse(), (req, r) ->
                ((MockHttpServletResponse) r).setStatus(status));
        }

        assertThat(captured.list).extracting(ILoggingEvent::getLevel)
            .containsExactly(Level.INFO, Level.WARN, Level.ERROR);
        assertThat(captured.list).extracting(ILoggingEvent::getFormattedMessage)
            .allSatisfy(line -> assertThat(line).contains("GET /api/v1/x").contains("durationMs="));
    }

    @Test
    @DisplayName("the completion line carries the route, never a path segment nobody vetted")
    void neverLogsARawPathSegment() throws Exception {
        String token = "a-live-invite-token-that-opens-a-household";
        MockHttpServletRequest preview =
            new MockHttpServletRequest("GET", "/api/v1/auth/invites/" + token);
        preview.setContextPath("/api");

        filter.doFilter(preview, new MockHttpServletResponse(), noopChain());

        assertThat(captured.list).singleElement().satisfies(event -> assertThat(
            event.getFormattedMessage())
            .as("this statement runs at INFO for every 2xx, and the invite preview is a public "
                + "GET whose path segment IS the credential")
            .doesNotContain(token)
            .contains("/api/v1/auth/invites/*"));
    }

    @Test
    @DisplayName("an id already set on the response by an upstream filter is not overwritten")
    void doesNotOverwriteAnExistingResponseHeader() throws Exception {
        response.setHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "upstream-value");

        filter.doFilter(request, response, noopChain());

        assertThat(response.getHeader(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .isEqualTo("upstream-value");
    }

    private static FilterChain noopChain() {
        return (req, res) -> {
        };
    }
}
