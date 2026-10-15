package finance.trove.auth.oidc;

import finance.trove.auth.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Sends a failed OIDC callback back to the login page with a key it understands.
 *
 * <p>The default Spring Security handler redirects to {@code /login?error} on the API origin,
 * which under this deployment is a JSON 404 rather than a page. It also renders the provider's
 * error text, which is attacker-influenceable — an {@code error_description} query parameter
 * echoed into a page is a reflected-content problem with no upside. So nothing from the provider
 * is passed through: the reason is logged server-side and the browser gets a fixed key.
 *
 * <p>{@code session} is the key the login page uses for "your sign-in attempt went stale", which
 * is what a missing or undecryptable state cookie is. Everything else is {@code oidc}.
 */
public class TroveOidcFailureHandler implements AuthenticationFailureHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TroveOidcFailureHandler.class);

    private final AuthProperties properties;

    public TroveOidcFailureHandler(AuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String message = exception.getMessage();
        LOG.warn("OIDC sign-in failed: {}", message);
        boolean stateProblem = message != null
            && (message.contains("authorization_request_not_found")
            || message.contains("invalid_state_parameter"));
        response.sendRedirect(properties.publicUrl() + "/login?error="
            + (stateProblem ? "session" : "oidc"));
    }
}
