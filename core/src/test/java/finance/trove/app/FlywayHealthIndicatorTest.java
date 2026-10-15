package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class FlywayHealthIndicatorTest {

    @Test
    @DisplayName("UP with the applied count and current version when nothing is pending")
    void upWhenSchemaIsCurrent() {
        Health health = new FlywayHealthIndicator(
            flywayWith(2, 0, MigrationVersion.fromVersion("2"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("applied", 2)
            .containsEntry("pending", 0)
            .containsEntry("current", "2");
    }

    @Test
    @DisplayName("DOWN while migrations are still pending — 'migrating' is not 'ready'")
    void downWhenMigrationsArePending() {
        Health health = new FlywayHealthIndicator(
            flywayWith(1, 3, MigrationVersion.fromVersion("1"))).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
            .containsEntry("pending", 3)
            .containsEntry("reason", "migrations pending");
    }

    @Test
    @DisplayName("DOWN when the schema history cannot be read at all")
    void downWhenFlywayThrows() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException("connection refused"));

        Health health = new FlywayHealthIndicator(flyway).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Reports 'none' rather than failing on an empty schema history")
    void currentVersionIsNoneOnAnEmptyDatabase() {
        Health health = new FlywayHealthIndicator(flywayWith(0, 2, null)).health();

        assertThat(health.getDetails()).containsEntry("current", "none");
    }

    private static Flyway flywayWith(int applied, int pending, MigrationVersion current) {
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(info.applied()).thenReturn(new MigrationInfo[applied]);
        when(info.pending()).thenReturn(new MigrationInfo[pending]);
        if (current == null) {
            when(info.current()).thenReturn(null);
        } else {
            MigrationInfo currentInfo = mock(MigrationInfo.class);
            when(currentInfo.getVersion()).thenReturn(current);
            when(info.current()).thenReturn(currentInfo);
        }

        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenReturn(info);
        return flyway;
    }
}
