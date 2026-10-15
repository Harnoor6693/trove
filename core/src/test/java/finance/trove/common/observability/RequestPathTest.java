package finance.trove.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The one rule this class exists for: a credential that travels in a path must never reach a log
 * line or an error body.
 */
class RequestPathTest {

    /** A real 43-character base64url invite token, the shape {@code InviteService} mints. */
    private static final String TOKEN = "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWpr";

    @Test
    @DisplayName("A matched route is described by its template, so the token never appears")
    void aMatchedRouteIsDescribedByItsTemplate() {
        MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/auth/invites/" + TOKEN);
        request.setContextPath("/api");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
            "/v1/auth/invites/{token}");

        String described = RequestPath.describe(request);

        assertThat(described).isEqualTo("/api/v1/auth/invites/{token}");
        assertThat(described)
            .as("the invite token is a live credential for the full 72-hour TTL; a log shipper "
                + "keeps it as long as it keeps anything")
            .doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("Without a matched route the last segment is redacted rather than trusted")
    void anUnmatchedRequestLosesItsLastSegment() {
        MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/auth/invites/" + TOKEN);
        request.setContextPath("/api");

        assertThat(RequestPath.describe(request))
            .as("the security chain refuses requests before any handler is matched, and those "
                + "are exactly the requests whose paths nobody has vetted")
            .isEqualTo("/api/v1/auth/invites/*")
            .doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("A template on an application served at the root is not prefixed")
    void anEmptyContextPathIsNotPrefixed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/status");
        request.setContextPath("");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/v1/status");

        assertThat(RequestPath.describe(request)).isEqualTo("/v1/status");
    }

    @Test
    @DisplayName("A request that does not exist still produces something printable")
    void degenerateInputs() {
        assertThat(RequestPath.describe(null)).isEqualTo("-");
        assertThat(RequestPath.redactLastSegment(null)).isEqualTo("-");
        assertThat(RequestPath.redactLastSegment("")).isEqualTo("-");
        assertThat(RequestPath.redactLastSegment("/")).isEqualTo("/");
        assertThat(RequestPath.redactLastSegment("/api/v1/invites/")).isEqualTo("/api/v1/invites/");
        assertThat(RequestPath.redactLastSegment("noslash")).isEqualTo("noslash");
    }
}
