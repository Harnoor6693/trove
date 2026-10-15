package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * The happy path of {@code GET /v1/status} is covered end to end by {@code SkeletonIT}; what it
 * cannot reach is the unhealthy aggregate, since the test stack is healthy by construction. The
 * mapping is therefore asserted directly — {@code HealthDescriptor} is a sealed type with
 * package-private implementations, so neither mocking nor subclassing it is possible.
 */
class HealthControllerTest {

    @Test
    @DisplayName("Only UP is UP")
    void upStaysUp() {
        assertThat(HealthController.summarise(Status.UP)).isEqualTo("UP");
    }

    @Test
    @DisplayName("Every other aggregate is reported as DEGRADED, never as the raw status")
    void anythingElseIsDegraded() {
        assertThat(HealthController.summarise(Status.DOWN)).isEqualTo("DEGRADED");
        assertThat(HealthController.summarise(Status.OUT_OF_SERVICE)).isEqualTo("DEGRADED");
        assertThat(HealthController.summarise(Status.UNKNOWN)).isEqualTo("DEGRADED");
        assertThat(HealthController.summarise(new Status("MIGRATING"))).isEqualTo("DEGRADED");
    }
}
