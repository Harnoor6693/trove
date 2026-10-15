package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The metrics-surface grant. Both assertions here are about failing closed.
 */
class ManagementAccessPropertiesTest {

    @Test
    @DisplayName("A blank token means the header route is CLOSED, not open to everyone")
    void aBlankTokenIsNotACredential() {
        assertThat(new ManagementAccessProperties(null, null).hasToken()).isFalse();
        assertThat(new ManagementAccessProperties(null, "   ").hasToken())
            .as("an empty configured secret matching an empty presented one is the classic way "
                + "an optional credential becomes no credential")
            .isFalse();
        assertThat(new ManagementAccessProperties(null, " s3cret ").hasToken()).isTrue();
        assertThat(new ManagementAccessProperties(null, " s3cret ").token()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("An unset network list falls back to the compose network, never to everything")
    void networksDefaultToTheComposeNetwork() {
        assertThat(new ManagementAccessProperties(null, null).allowedNetworks())
            .isEqualTo(ManagementAccessProperties.DEFAULT_NETWORKS)
            .contains("127.0.0.0/8", "172.16.0.0/12");
        assertThat(new ManagementAccessProperties(List.of(), null).allowedNetworks())
            .as("an empty list is a configuration mistake, not an instruction to allow nothing "
                + "and lock the operator out of their own metrics")
            .isEqualTo(ManagementAccessProperties.DEFAULT_NETWORKS);
    }

    @Test
    @DisplayName("A configured list replaces the default outright")
    void aConfiguredListWins() {
        assertThat(new ManagementAccessProperties(List.of("10.1.2.0/24"), null).allowedNetworks())
            .containsExactly("10.1.2.0/24");
    }
}
