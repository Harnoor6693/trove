package finance.trove.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Entry point for {@code trove-core}, the Trove monolith.
 *
 * <p>One JVM serves every domain package under {@code finance.trove} against a single
 * Postgres catalog with a single Flyway history — see
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §1.1–§1.3. Component scanning is scoped to
 * {@code finance.trove} so that every package listed in §1.3 is picked up as it lands,
 * without a per-domain configuration class.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers every {@code @ConfigurationProperties} record
 * under {@code finance.trove} — {@code trove.crypto}, {@code trove.http}, {@code trove.egress},
 * {@code trove.time} at collapse step 2 — so a new one needs no {@code @EnableConfigurationProperties}
 * edit and, more to the point, so a record with a validating constructor fails the context refresh
 * rather than being quietly absent (§1.6: a bad TROVE_ENCRYPTION_KEY must stop the boot).
 *
 * <p>Deliberately absent at this step:
 * <ul>
 *   <li>{@code @EnableScheduling}/{@code @EnableSchedulerLock} — collapse step 4 adds
 *       {@code finance.trove.app.SchedulingConfig} (§5.6); no {@code @Scheduled} method
 *       exists yet and enabling the machinery early would start an idle scheduler.</li>
 *   <li>{@code @EnableAsync} — collapse step 4 adds {@code AsyncConfig} with named,
 *       bounded executors (§5.7).</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = "finance.trove")
@ConfigurationPropertiesScan("finance.trove")
// Both are required and neither is redundant with scanBasePackages above. Boot derives the
// packages it scans for @Entity and for Spring Data repositories from AutoConfigurationPackages,
// which is registered from THIS class's own package — finance.trove.app — and not from
// scanBasePackages. Without these two, every repository outside finance.trove.app (which is all
// of them) is silently absent and the context fails on the first constructor that wants one,
// naming a single repository and giving no hint that none of them exist.
@EnableJpaRepositories("finance.trove")
@EntityScan("finance.trove")
public class TroveApplication {

    public static void main(String[] args) {
        SpringApplication.run(TroveApplication.class, args);
    }
}
