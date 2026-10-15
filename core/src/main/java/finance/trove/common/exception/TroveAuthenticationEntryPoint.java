package finance.trove.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * The 401 of {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1: a JSON
 * {@code ApiResponse.error(ErrorResponse)}, never a redirect to a login page and never a
 * {@code WWW-Authenticate: Basic} challenge that would make a browser pop its own credential
 * dialog over the SPA.
 *
 * <p>{@code WWW-Authenticate: Bearer} is still sent, because that is what the scheme actually
 * is and mobile's interceptor keys its refresh on it.
 */
@Component
public class TroveAuthenticationEntryPoint implements AuthenticationEntryPoint {

    public static final String DETAIL = "Authentication is required to access this resource.";

    private final SecurityErrorWriter writer;

    public TroveAuthenticationEntryPoint(SecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        writer.write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", DETAIL);
    }
}
