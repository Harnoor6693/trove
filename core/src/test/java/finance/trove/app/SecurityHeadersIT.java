package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The response headers §5.1 asks for, and the two it deliberately leaves to Traefik.
 *
 * <p>The absence assertions matter as much as the presence ones. {@code Strict-Transport-Security}
 * and {@code X-Frame-Options} are set by Traefik's {@code security-headers} middleware (step 19),
 * which is also the only thing that terminates TLS; emitting them here as well would put two of
 * each on every response, with no way for an operator to tell which one they were meant to change.
 * A later step that "adds security headers" without reading this would reintroduce exactly that,
 * so the duplicate is asserted against.
 */
class SecurityHeadersIT extends AuthFlowSupport {

    private Optional<String> header(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name);
    }

    @Test
    @DisplayName("Every response carries the CSP of §5.1")
    void contentSecurityPolicyIsSet() {
        HttpResponse<String> response = get(appUrl("/v1/status"));

        assertThat(header(response, "Content-Security-Policy"))
            .as("the one header that most limits the blast radius of an XSS")
            .hasValue(SecurityConfig.CONTENT_SECURITY_POLICY);
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY)
            .contains("default-src 'self'")
            .contains("script-src 'self'")
            .contains("frame-ancestors 'none'")
            .contains("base-uri 'self'")
            .contains("form-action 'self'");
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY)
            .as("'unsafe-inline' survives on style-src alone; scripts get no such exemption")
            .doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    @DisplayName("Referrer, permissions and nosniff are set on an authenticated response too")
    void theOtherHeadersAreSet() {
        Session admin = loginBootstrapAdmin();
        HttpResponse<String> response = get(appUrl("/v1/auth/me"), admin.accessToken());

        assertThat(header(response, "Referrer-Policy"))
            .hasValue("strict-origin-when-cross-origin");
        assertThat(header(response, "Permissions-Policy"))
            .as("this application asks for none of these; saying so is what stops an injected "
                + "script asking on its behalf")
            .hasValue(SecurityConfig.PERMISSIONS_POLICY);
        assertThat(header(response, "X-Content-Type-Options"))
            .as("an uploaded receipt served back with a guessed content type is stored XSS")
            .hasValue("nosniff");
        assertThat(header(response, "Cache-Control"))
            .as("every body here is either a private ledger or a session")
            .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
    }

    @Test
    @DisplayName("HSTS and X-Frame-Options are Traefik's, and are not duplicated here")
    void theProxyOwnsHstsAndFraming() {
        HttpResponse<String> response = get(appUrl("/v1/status"));

        assertThat(header(response, "Strict-Transport-Security"))
            .as("only the TLS terminator is in a position to assert HSTS; this container is "
                + "reached over plain HTTP inside the compose network")
            .isEmpty();
        assertThat(header(response, "X-Frame-Options"))
            .as("superseded by frame-ancestors 'none' in the CSP, and set by Traefik for the "
                + "browsers that still want it")
            .isEmpty();
    }

    @Test
    @DisplayName("A refusal written inside the security chain carries the headers too")
    void refusalsAreDecoratedAsWell() {
        HttpResponse<String> unauthorised = get(appUrl("/v1/auth/me"));

        assertThat(unauthorised.statusCode()).isEqualTo(401);
        assertThat(header(unauthorised, "Content-Security-Policy"))
            .as("the header writer runs before the entry point, so a 401 is decorated like any "
                + "other response — a gap here would leave the error surface undefended")
            .hasValue(SecurityConfig.CONTENT_SECURITY_POLICY);
        assertThat(header(unauthorised, "X-Request-Id")).isPresent();
    }

    @Test
    @DisplayName("The liveness and readiness probes stay reachable with no credential")
    void theProbesStayOpen() {
        assertThat(get(managementUrl("/actuator/health/liveness")).statusCode())
            .as("the Docker HEALTHCHECK polls this; a probe that needs a secret reports "
                + "\"unhealthy\" the day the secret rotates")
            .isEqualTo(200);
        assertThat(get(managementUrl("/actuator/health/readiness")).statusCode())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("The metrics surface is open to the compose network and closed outside it")
    void metricsAreRestrictedToTheComposeNetwork() {
        assertThat(get(managementUrl("/actuator/prometheus")).statusCode())
            .as("the scrape arrives from loopback here and from the compose bridge in the "
                + "deployment; both are inside trove.management.allowed-networks, so the "
                + "Prometheus sidecar needs no configuration at all")
            .isEqualTo(200);

        assertThat(getWith(managementUrl("/actuator/prometheus"),
            FORWARDED_FOR, "203.0.113.55").statusCode())
            .as("request counts, timings and every meter tag are reconnaissance rather than a "
                + "liveness answer, and this port is not routed by Traefik — a caller that "
                + "resolves outside the compose network has no business reading them")
            .isIn(401, 403);
    }
}
