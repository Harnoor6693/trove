package finance.trove.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import finance.trove.common.json.TroveJsonModule;
import finance.trove.common.observability.RequestIdMdcFilter;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two refusals written inside the security chain, which never reach
 * {@link GlobalExceptionHandler}. They have to produce the same envelope as every other error;
 * collapse step 1 had them as a string literal in {@code SecurityConfig}, and a 403 not at all.
 */
class SecurityErrorWriterTest {

    private static final String REQUEST_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final JsonMapper mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .addModule(new TroveJsonModule())
        .build();

    private final SecurityErrorWriter writer =
        new SecurityErrorWriter(mapper, new ErrorResponseFactory());
    private final MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/households");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("the 401 is JSON, carries WWW-Authenticate: Bearer, and never redirects")
    void entryPointWritesTheSharedEnvelope() throws IOException {
        request.setAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);

        new TroveAuthenticationEntryPoint(writer).commence(request, response,
            new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getHeader("Location")).isNull();

        DocumentContext body = JsonPath.parse(response.getContentAsString());
        assertThat(body.<Boolean>read("$.success")).isFalse();
        assertThat(body.<Integer>read("$.error.status")).isEqualTo(401);
        assertThat(body.<String>read("$.error.title")).isEqualTo("Unauthorized");
        assertThat(body.<String>read("$.error.type")).endsWith("/unauthorized");
        assertThat(body.<String>read("$.error.instance"))
            .as("the security chain refuses before any handler is matched, so there is no route "
                + "template to report and the last segment — the one that could be a token — is "
                + "redacted instead")
            .isEqualTo("/api/v1/*");
        assertThat(body.<String>read("$.error.requestId")).isEqualTo(REQUEST_ID);
        assertThat(response.getContentAsString()).doesNotContain("no token");
    }

    @Test
    @DisplayName("the 403 closes gap P2-7: an admin-route refusal is JSON, not a bare 500")
    void accessDeniedHandlerWritesTheSharedEnvelope() throws IOException {
        new TroveAccessDeniedHandler(writer).handle(request, response,
            new AccessDeniedException("Access is denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        DocumentContext body = JsonPath.parse(response.getContentAsString());
        assertThat(body.<Integer>read("$.error.status")).isEqualTo(403);
        assertThat(body.<String>read("$.error.title")).isEqualTo("Forbidden");
        assertThat(body.<String>read("$.error.detail")).isEqualTo(TroveAccessDeniedHandler.DETAIL);
    }

    @Test
    @DisplayName("a committed response is left alone — a second write would corrupt a stream")
    void doesNotWriteToACommittedResponse() throws IOException {
        response.getWriter().write("data: partial-sse-frame");
        response.flushBuffer();

        writer.write(request, response, HttpStatus.FORBIDDEN, "forbidden", "denied");

        assertThat(response.getContentAsString()).isEqualTo("data: partial-sse-frame");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the two bodies differ only in status, type, title and detail")
    void bothRefusalsShareOneShape() throws IOException {
        MockHttpServletResponse unauthorised = new MockHttpServletResponse();
        MockHttpServletResponse forbidden = new MockHttpServletResponse();

        writer.write(request, unauthorised, HttpStatus.UNAUTHORIZED, "unauthorized", "a");
        writer.write(request, forbidden, HttpStatus.FORBIDDEN, "forbidden", "b");

        assertThat(JsonPath.parse(unauthorised.getContentAsString()).<Object>read("$").toString())
            .contains("success", "error");
        assertThat(JsonPath.parse(forbidden.getContentAsString()).<Object>read("$").toString())
            .contains("success", "error");
    }
}
