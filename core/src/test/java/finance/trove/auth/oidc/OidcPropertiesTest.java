package finance.trove.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OidcPropertiesTest {

    @Test
    @DisplayName("Disabled is the default and needs nothing else set")
    void disabledNeedsNothing() {
        OidcProperties properties = new OidcProperties(false, null, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.issuerUri()).isNull();
        assertThat(properties.displayName()).isEqualTo("Single sign-on");
        assertThat(properties.scopes()).isEqualTo("openid,profile,email");
    }

    @Test
    @DisplayName("Empty strings from an unset ${VAR:} bind as absent, not as a configured value")
    void blankStringsAreAbsent() {
        OidcProperties properties = new OidcProperties(false, "", "  ", "", "", null);

        assertThat(properties.issuerUri()).isNull();
        assertThat(properties.clientId()).isNull();
        assertThat(properties.clientSecret()).isNull();
    }

    @Test
    @DisplayName("Enabled with an incomplete block fails the boot, naming all three variables")
    void enabledDemandsTheWholeBlock() {
        assertThatThrownBy(() ->
            new OidcProperties(true, "https://idp.example.com", null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TROVE_OIDC_ISSUER_URI")
            .hasMessageContaining("TROVE_OIDC_CLIENT_ID")
            .hasMessageContaining("TROVE_OIDC_CLIENT_SECRET");

        assertThatThrownBy(() ->
            new OidcProperties(true, null, "client", "secret", null, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A complete block is accepted and exposes the generic authorization path")
    void completeBlockIsAccepted() {
        OidcProperties properties = new OidcProperties(true, "https://idp.example.com", "client",
            "secret", "Company SSO", "openid,email");

        assertThatCode(properties::authorizationRequestPath).doesNotThrowAnyException();
        assertThat(properties.authorizationRequestPath())
            .as("the registration id is the generic 'oidc', not a vendor name baked into every "
                + "client's redirect URI")
            .isEqualTo("/api/oauth2/authorization/oidc");
        assertThat(OidcProperties.REGISTRATION_ID).isEqualTo("oidc");
        assertThat(properties.displayName()).isEqualTo("Company SSO");
    }
}
