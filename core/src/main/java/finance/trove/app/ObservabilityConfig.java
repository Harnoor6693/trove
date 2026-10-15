package finance.trove.app;

import finance.trove.common.observability.RequestIdMdcFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Request correlation and metrics wiring ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.9).
 *
 * <p><strong>One registration per filter.</strong> {@link RequestIdMdcFilter} is deliberately not
 * a {@code @Component}: being both a Spring bean and a servlet {@code Filter} is what registered
 * every filter twice across seven services (§9 risk 8), so the filter is constructed here and
 * registered exactly once. {@code HIGHEST_PRECEDENCE} puts it ahead of the security chain, which
 * is what gives a 401 written by {@code TroveAuthenticationEntryPoint} the same {@code requestId}
 * as a 200 written by a controller. {@code ERROR} is in the dispatcher set so an error-page
 * dispatch does not lose the id.
 *
 * <p><strong>Common tags.</strong> {@code application=trove-core} on every meter, so the
 * Prometheus scrape is attributable when the optional AI container is also scraped.
 *
 * <p><strong>One custom meter, and only one honest one.</strong> {@code trove.build.info} is a
 * gauge fixed at 1 whose tags carry the version — the standard build-info pattern, which lets a
 * dashboard annotate a deploy and lets an operator confirm which jar is answering. The three
 * meters §5.9 asks for ({@code trove.receipt.stage}, {@code trove.ai.invocation},
 * {@code trove.notification.sent}) belong to collapse steps 14, 12 and 11: registering them here
 * would publish three meters that never record a sample, which is precisely the "the Grafana JSON
 * lies" problem §5.9 exists to fix. HTTP request timing is already published by Boot as
 * {@code http.server.requests} and is not duplicated.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<RequestIdMdcFilter> requestIdMdcFilterRegistration() {
        FilterRegistrationBean<RequestIdMdcFilter> registration =
            new FilterRegistrationBean<>(new RequestIdMdcFilter());
        registration.setName("requestIdMdcFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST,
            jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR);
        return registration;
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> troveCommonTags() {
        return registry -> registry.config().commonTags("application", "trove-core");
    }

    @Bean
    public MeterBinder troveBuildInfoMetrics(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "unknown";
        return registry -> Gauge.builder("trove.build.info", () -> 1)
            .description("Always 1; the tags carry the build identity of the running jar")
            .tag("version", version)
            .strongReference(true)
            .register(registry);
    }
}
