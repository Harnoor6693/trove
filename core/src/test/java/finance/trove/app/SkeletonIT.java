package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import finance.trove.app.support.BaseIT;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the collapse step-1 skeleton end to end: the monolith boots against a real Postgres,
 * serves one public endpoint under {@code /api}, refuses everything else with JSON, exposes
 * working probes on the management port, and has applied exactly the migrations that exist.
 */
class SkeletonIT extends BaseIT {

    @Test
    @DisplayName("GET /api/v1/status is public and reports status, version and time")
    void statusIsPublic() {
        HttpResponse<String> response = get(appUrl("/v1/status"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/json");

        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<String>read("$.status")).isEqualTo("UP");
        assertThat(body.<String>read("$.version")).isNotBlank();
        assertThat(Instant.parse(body.read("$.time"))).isBefore(Instant.now().plusSeconds(60));
        // Detail-free by contract (gap P1-1): no component map may leak onto the public port.
        assertThat(response.body()).doesNotContain("components").doesNotContain("diskSpace");
    }

    @Test
    @DisplayName("Any other /api/v1 route answers 401 JSON — no redirect, no basic-auth prompt")
    void protectedRoutesReturnJsonUnauthorized() {
        HttpResponse<String> response = outsideTheContract(
            "no controller serves /v1/households; the subject is what the security chain answers "
                + "for ANY unmatched /api/v1 path, which openapi/trove-core.yaml describes in "
                + "its error-model prose because it belongs to no operation",
            () -> get(appUrl("/v1/households")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/json");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse(""))
            .doesNotStartWith("Basic");

        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<Boolean>read("$.success")).isFalse();
        assertThat(body.<Integer>read("$.error.status")).isEqualTo(401);
        assertThat(body.<String>read("$.error.title")).isEqualTo("Unauthorized");
    }

    @Test
    @DisplayName("Readiness on the management port is UP and covers db, flyway and diskSpace")
    void readinessProbeReportsComponents() {
        HttpResponse<String> response = get(managementUrl("/actuator/health/readiness"));

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<String>read("$.status")).isEqualTo("UP");

        Map<String, Object> components = body.read("$.components");
        assertThat(components).containsKeys("db", "flyway", "diskSpace", "readinessState");
        assertThat(body.<String>read("$.components.db.status")).isEqualTo("UP");
        assertThat(body.<String>read("$.components.flyway.status")).isEqualTo("UP");
        // Not an exact count: later collapse steps add migrations, and this test is about the
        // probe reporting a healthy Flyway, not about how many migrations exist this week.
        assertThat(body.<Integer>read("$.components.flyway.details.applied"))
            .isGreaterThanOrEqualTo(2);
        assertThat(body.<Integer>read("$.components.flyway.details.pending")).isZero();
        assertThat(body.<String>read("$.components.diskSpace.status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("Liveness on the management port is UP")
    void livenessProbeIsUp() {
        HttpResponse<String> response = get(managementUrl("/actuator/health/liveness"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JsonPath.parse(response.body()).<String>read("$.status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("The history starts at V1 and V2, is in order, and every migration succeeded")
    void flywayHistoryStartsAtTheSkeleton() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

        // The skeleton's two migrations are asserted by position; the rest are asserted by the
        // properties that must hold for all of them however many later steps add. Pinning an
        // exact count here would mean every future step edits this test to say a bigger number,
        // which is churn that proves nothing.
        assertThat(history).hasSizeGreaterThanOrEqualTo(2);
        assertThat(history).extracting(row -> row.get("version")).startsWith("1", "2");
        assertThat(history).extracting(row -> row.get("description"))
            .startsWith("extensions", "households");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    @DisplayName("V1 installed the extensions and V2 created the households table")
    void schemaMatchesTheMigrations() {
        List<String> extensions = jdbcTemplate.queryForList(
            "SELECT extname FROM pg_extension ORDER BY extname", String.class);
        assertThat(extensions).contains("pgcrypto", "pg_trgm");

        List<String> columns = jdbcTemplate.queryForList("""
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'households'
            ORDER BY ordinal_position
            """, String.class);
        // containsExactly would break the moment a later step adds a column, which V3 already
        // does (households.registration_open). What V2 owns is these three, in this order.
        assertThat(columns).startsWith("id", "name", "created_at");

        // gen_random_uuid()/now() defaults mean an insert needs the name only.
        Long before = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM households WHERE id IS NOT NULL AND created_at IS NOT NULL",
            Long.class);
        jdbcTemplate.update("INSERT INTO households (name) VALUES ('Skeleton household')");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM households WHERE id IS NOT NULL AND created_at IS NOT NULL",
            Long.class))
            .as("the defaults fill id and created_at without the insert naming either")
            .isEqualTo(before + 1);
        jdbcTemplate.update("DELETE FROM households WHERE name = 'Skeleton household'");
    }
}
