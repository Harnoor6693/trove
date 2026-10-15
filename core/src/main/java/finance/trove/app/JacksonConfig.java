package finance.trove.app;

import finance.trove.common.json.TroveJsonModule;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * The monolith's one Jackson strategy ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.4).
 *
 * <p>Boot 4 builds its {@code JsonMapper} from a {@code JsonMapper.Builder} that every
 * {@link JsonMapperBuilderCustomizer} bean gets to touch, so this is the whole configuration —
 * there is no second {@code ObjectMapper} anywhere, and {@code libs/common}'s
 * {@code JacksonConfig} (SNAKE_CASE, never actually active outside the gateway) is deleted at
 * step 24 rather than moved (§2.11).
 *
 * <p>Three of the four settings below are also expressed in {@code application.yml}
 * ({@code spring.jackson.*}). They are repeated here on purpose: the YAML keys are what an
 * operator reads, and this class is what the contract tests assert against, so a future edit that
 * removes one of them fails a test instead of silently changing the wire format. The fourth —
 * the {@link TroveJsonModule} — has no YAML equivalent.
 *
 * <ul>
 *   <li><strong>camelCase.</strong> Jackson's default, stated explicitly because the gateway is
 *       the one place SNAKE_CASE actually runs today and this is the change that renames
 *       {@code error.field_errors} to {@code error.fieldErrors} and the auth DTOs'
 *       {@code access_token}/{@code refresh_token} to camelCase (§4.13).</li>
 *   <li><strong>ISO-8601 dates, not epoch numbers.</strong> In Jackson 3 this moved out of
 *       {@code SerializationFeature} into {@link DateTimeFeature}; the old
 *       {@code spring.jackson.serialization.write-dates-as-timestamps} key no longer binds and
 *       fails the context outright — collapse step 1 found this the hard way.</li>
 *   <li><strong>Unknown properties are ignored on the way in.</strong> A client that is one
 *       release ahead of the server must not 400 on a field the server does not know yet.</li>
 *   <li><strong>Every {@code BigDecimal} leaves as a plain decimal string</strong>, via
 *       {@link TroveJsonModule}.</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer troveJsonMapperCustomizer() {
        return builder -> builder
            .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new TroveJsonModule());
    }
}
