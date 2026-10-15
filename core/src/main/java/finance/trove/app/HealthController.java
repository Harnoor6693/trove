package finance.trove.app;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unauthenticated, detail-free status probe of readiness gap P1-1:
 * {@code GET /api/v1/status} → {@code {"status":"UP|DEGRADED","version":"…","time":"…"}}.
 *
 * <p>It exists so that the SPA's {@code /welcome} degraded state, {@code trove status} and any
 * external uptime check have one public endpoint to key off without exposing the management
 * port (9090) or any component detail. The aggregate comes from the same
 * {@link HealthEndpoint} the actuator serves, so this endpoint cannot drift from
 * {@code /actuator/health}; only the aggregate status crosses the boundary, never the
 * component map.
 */
@RestController
@RequestMapping("/v1")
public class HealthController {

    private final HealthEndpoint healthEndpoint;
    private final String version;

    public HealthController(HealthEndpoint healthEndpoint,
                            ObjectProvider<BuildProperties> buildProperties) {
        this.healthEndpoint = healthEndpoint;
        this.version = buildProperties.getIfAvailable() != null
            ? buildProperties.getObject().getVersion()
            : "unknown";
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of(
            "status", summarise(healthEndpoint.health().getStatus()),
            "version", version,
            "time", Instant.now().toString());
    }

    /**
     * Collapses the actuator aggregate to the two words the public contract allows. Anything
     * that is not {@code UP} — {@code DOWN}, {@code OUT_OF_SERVICE}, {@code UNKNOWN} or a custom
     * status — is {@code DEGRADED}: which component is unhappy is management-port information.
     */
    static String summarise(Status aggregate) {
        return Status.UP.equals(aggregate) ? "UP" : "DEGRADED";
    }
}
