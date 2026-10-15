package finance.trove.app;

import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.PinnedDnsResolver;
import finance.trove.common.net.ResolvedTarget;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * The only place in the monolith that constructs an HTTP client
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5; {@code ArchitectureTest} enforces it, §9
 * risk 9).
 *
 * <p>Two things come out of here:
 * <ul>
 *   <li>A {@link RestClientCustomizer} that gives <em>every</em> injected
 *       {@code RestClient.Builder} the timeouts of {@code trove.http.*} (5 s / 30 s by default),
 *       no automatic redirects, and a {@code User-Agent} that identifies the build. The source
 *       services had two {@code RestClientConfig} classes and several bare
 *       {@code RestClient.create()} calls with no timeout at all, which is how a hung SimpleFIN
 *       bridge could pin a request thread indefinitely.</li>
 *   <li>{@link PinnedRestClientFactory}, which builds a client bound to one
 *       {@link ResolvedTarget}. This is the connect-time half of the DNS-rebinding defence: the
 *       client resolves through {@link PinnedDnsResolver}, so it connects to the address
 *       {@link OutboundUrlPolicy} approved and to nothing else, while still sending the real
 *       host name in {@code Host} and in the TLS SNI.</li>
 * </ul>
 *
 * <p>Redirects are off on both. A 302 is the cheapest way around a URL allowlist — the
 * validated URL is public, the redirect target is {@code 169.254.169.254} — and following one
 * would also mean resolving a second name that no policy ever saw.
 *
 * <p>Named per-purpose builders (AI at 120 s for streaming, webhooks at 10 s, SimpleFIN at
 * 5 s/30 s) arrive with collapse steps 7, 11 and 12; they are overloads of
 * {@link PinnedRestClientFactory#builderFor(ResolvedTarget, Duration, Duration)}, not new client
 * constructions elsewhere.
 */
@Configuration
public class HttpClientConfig {

    private final HttpClientProperties properties;
    private final String userAgent;

    public HttpClientConfig(HttpClientProperties properties,
                            ObjectProvider<BuildProperties> buildProperties) {
        this.properties = properties;
        BuildProperties build = buildProperties.getIfAvailable();
        this.userAgent = "trove-core/" + (build != null ? build.getVersion() : "unknown");
    }

    /** Applied to every {@code RestClient.Builder} Spring injects anywhere in the application. */
    @Bean
    public RestClientCustomizer troveRestClientCustomizer() {
        return builder -> builder
            .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                .build(baseSettings(properties.connectTimeout(), properties.readTimeout())))
            .defaultHeader(HttpHeaders.USER_AGENT, userAgent);
    }

    /** The factory for clients pinned to an already-validated destination. */
    @Bean
    public PinnedRestClientFactory pinnedRestClientFactory() {
        return new PinnedRestClientFactory(properties, userAgent);
    }

    private static HttpClientSettings baseSettings(Duration connectTimeout, Duration readTimeout) {
        return HttpClientSettings.defaults()
            .withRedirects(HttpRedirects.DONT_FOLLOW)
            .withTimeouts(connectTimeout, readTimeout);
    }

    /**
     * Builds a {@link RestClient} that can only reach one {@link ResolvedTarget}.
     *
     * <p>Nested inside {@code HttpClientConfig} so that the "no {@code RestClient.builder()}
     * outside {@code HttpClientConfig}" rule of §9 risk 9 stays a single, exact rule rather than
     * a list of exempted class names.
     */
    public static final class PinnedRestClientFactory {

        private final HttpClientProperties properties;
        private final String userAgent;

        private PinnedRestClientFactory(HttpClientProperties properties, String userAgent) {
            this.properties = properties;
            this.userAgent = userAgent;
        }

        /** A builder for {@code target} with the default {@code trove.http.*} timeouts. */
        public RestClient.Builder builderFor(ResolvedTarget target) {
            return builderFor(target, properties.connectTimeout(), properties.readTimeout());
        }

        /**
         * A builder for {@code target} with explicit timeouts — the AI client's 120 s read and
         * the webhook deliverer's 10 s both come through here rather than building their own.
         */
        public RestClient.Builder builderFor(ResolvedTarget target, Duration connectTimeout,
                                             Duration readTimeout) {
            return RestClient.builder()
                .baseUrl(target.uri().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.httpComponents()
                    .withDnsResolver(new PinnedDnsResolver(target))
                    .build(baseSettings(connectTimeout, readTimeout)))
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent);
        }

        /** A ready-to-use client for {@code target} with the default timeouts. */
        public RestClient clientFor(ResolvedTarget target) {
            return builderFor(target).build();
        }
    }
}
