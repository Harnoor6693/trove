package finance.trove.app;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may read the metrics surface on the management port
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1, §5.9; readiness P5).
 *
 * <p><strong>The two probes are not covered by this.</strong>
 * {@code /actuator/health/liveness} and {@code /actuator/health/readiness} are permitted outright
 * ({@code SecurityConfig.PUBLIC_ACTUATOR}), because the Docker {@code HEALTHCHECK} and any
 * orchestrator poll them with no credential and a probe that needs a secret reports "unhealthy"
 * the day the secret rotates. They disclose component status, which is the point of gap P1-1, and
 * they are on a port Traefik never routes.
 *
 * <p>{@code /actuator/prometheus} and {@code /actuator/metrics} are different: they carry request
 * counts, timings, JVM internals and every meter tag, which is a reconnaissance surface rather
 * than a liveness answer. Two independent ways in, either sufficient:
 * <ul>
 *   <li>the caller's resolved address is inside {@link #allowedNetworks} — the compose network, so
 *       the Prometheus sidecar under the {@code monitoring} profile needs no configuration at
 *       all;</li>
 *   <li>or the caller presents {@code Authorization: Bearer <token>} matching {@link #token}, for
 *       a scrape from outside the network. Blank by default, and while it is blank the header
 *       route is closed rather than open — an empty configured secret must never mean "everybody
 *       matches".</li>
 * </ul>
 *
 * @param allowedNetworks CIDR blocks whose members may read the metrics endpoints. The default is
 *                        the loopback and the RFC 1918 ranges Docker allocates bridge networks
 *                        from; it is deliberately the same shape as {@code TROVE_TRUSTED_PROXIES}
 *                        and deliberately a separate setting, because "I believe this peer's
 *                        forwarding headers" and "I will show this peer my metrics" are different
 *                        grants that only happen to have the same answer today
 * @param token           shared secret for a scrape from outside those networks; blank disables
 *                        that route entirely
 */
@ConfigurationProperties(prefix = "trove.management")
public record ManagementAccessProperties(List<String> allowedNetworks, String token) {

    /** Loopback plus the three RFC 1918 ranges, which is where a compose bridge network lives. */
    public static final List<String> DEFAULT_NETWORKS = List.of(
        "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    public ManagementAccessProperties {
        allowedNetworks = allowedNetworks == null || allowedNetworks.isEmpty()
            ? DEFAULT_NETWORKS
            : List.copyOf(allowedNetworks);
        token = token == null ? "" : token.strip();
    }

    /** Whether the bearer-token route is configured at all. */
    public boolean hasToken() {
        return !token.isEmpty();
    }
}
