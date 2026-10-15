package finance.trove.common.exception;

import finance.trove.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an {@link ApiResponse} error envelope straight to the servlet response, for the two
 * refusals that happen inside the security chain and therefore never reach
 * {@link GlobalExceptionHandler}: {@link TroveAuthenticationEntryPoint} and
 * {@link TroveAccessDeniedHandler}.
 *
 * <p>It serialises with the application's own {@code ObjectMapper} — the Jackson 3
 * {@code JsonMapper} the {@code JsonMapperBuilderCustomizer} of §5.4 configured — rather than a
 * string literal, so the 401 body cannot drift from the 404 body when the wire format changes.
 * Collapse step 1 wrote that literal by hand in {@code SecurityConfig} precisely because this
 * class did not exist yet.
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final ErrorResponseFactory errors;

    public SecurityErrorWriter(ObjectMapper objectMapper, ErrorResponseFactory errors) {
        this.objectMapper = objectMapper;
        this.errors = errors;
    }

    /**
     * Sets the status, content type and body. Does nothing if the response is already committed
     * — which happens when the failure is raised after a streaming endpoint began writing, and
     * where a second write would corrupt the frame the client is mid-way through reading.
     */
    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String slug, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body =
            ApiResponse.error(errors.build(status, slug, detail, request));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
