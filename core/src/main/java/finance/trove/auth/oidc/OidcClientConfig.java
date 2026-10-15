package finance.trove.auth.oidc;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers the one OAuth2 client, and only when the operator has configured one.
 *
 * <p>The condition is what makes OIDC genuinely optional rather than merely off. Without a
 * {@link ClientRegistrationRepository} bean Spring Boot's OAuth2 client auto-configuration backs
 * off entirely, {@code SecurityConfig} skips {@code oauth2Login}, and {@code /oauth2/**} and
 * {@code /login/oauth2/**} are not routed at all. On the majority of installs that never set
 * {@code TROVE_OIDC_ISSUER_URI}, no class in this package is instantiated.
 *
 * <p>The registration id is the generic {@code oidc}, not the pre-collapse {@code authentik}:
 * Authentik is one possible provider, and baking a vendor name into the callback URL means
 * changing the provider means changing every client's redirect URI.
 *
 * <p>Discovery happens at boot ({@code ClientRegistrations.fromIssuerLocation} fetches
 * {@code /.well-known/openid-configuration}), so an unreachable or wrong issuer fails the boot
 * with a clear message rather than producing a login button that 500s. That is the right trade
 * for an opt-in feature: the operator who just configured it is present to read the failure.
 */
@Configuration
@ConditionalOnProperty(prefix = "trove.auth.oidc", name = "enabled", havingValue = "true")
public class OidcClientConfig {

    private static final Logger LOG = LoggerFactory.getLogger(OidcClientConfig.class);

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OidcProperties properties) {
        List<String> scopes = Arrays.stream(properties.scopes().split(","))
            .map(String::strip)
            .filter(scope -> !scope.isEmpty())
            .toList();
        ClientRegistration registration =
            ClientRegistrations.fromIssuerLocation(properties.issuerUri())
                .registrationId(OidcProperties.REGISTRATION_ID)
                .clientId(properties.clientId())
                .clientSecret(properties.clientSecret())
                .clientName(properties.displayName())
                .scope(scopes)
                .build();
        LOG.info("OIDC enabled: issuer {}, scopes {}", properties.issuerUri(), scopes);
        return new InMemoryClientRegistrationRepository(registration);
    }
}
