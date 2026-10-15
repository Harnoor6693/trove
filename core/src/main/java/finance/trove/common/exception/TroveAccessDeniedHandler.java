package finance.trove.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * The 403 of {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1, closing the gap where an
 * admin-route refusal falls through to a bare 500 (gap P2-7): the chain-level
 * {@code AccessDeniedException} now produces the same JSON envelope as every other error.
 *
 * <p>The detail is deliberately generic. Confirming <em>which</em> role or step-up factor is
 * missing tells an attacker what the route needs; a 403 says only that this caller may not do
 * this. Cross-household access is a 404 rather than a 403 for the same reason (§9 risk 2).
 */
@Component
public class TroveAccessDeniedHandler implements AccessDeniedHandler {

    public static final String DETAIL = "You do not have permission to perform this action.";

    private final SecurityErrorWriter writer;

    public TroveAccessDeniedHandler(SecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writer.write(request, response, HttpStatus.FORBIDDEN, "forbidden", DETAIL);
    }
}
