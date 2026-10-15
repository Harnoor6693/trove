package finance.trove.app;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code flyway}, a member of the {@code readiness} group.
 *
 * <p>Spring Boot ships a Flyway <em>endpoint</em> but no Flyway <em>health indicator</em>, and
 * readiness gap P1-1 requires the probe to tell "still migrating" apart from "database down".
 * Without this contributor, a container whose schema is behind the jar reports {@code UP} on
 * {@code /actuator/health/readiness} the moment the pool opens.
 *
 * <p>{@code DOWN} when the schema history cannot be read (which the {@code db} contributor
 * would also catch) or when any migration is pending or failed — the forward-only policy of
 * gap P1-3 means a pending migration is never a normal steady state for a running instance.
 */
@Component
public class FlywayHealthIndicator extends AbstractHealthIndicator {

    private final Flyway flyway;

    public FlywayHealthIndicator(Flyway flyway) {
        super("Flyway schema history check failed");
        this.flyway = flyway;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        MigrationInfoService info = flyway.info();
        MigrationInfo[] applied = info.applied();
        MigrationInfo[] pending = info.pending();
        MigrationInfo current = info.current();

        builder.withDetail("applied", applied.length)
            .withDetail("pending", pending.length)
            .withDetail("current", current != null && current.getVersion() != null
                ? current.getVersion().toString()
                : "none");

        if (pending.length > 0) {
            builder.down().withDetail("reason", "migrations pending");
            return;
        }
        builder.up();
    }
}
