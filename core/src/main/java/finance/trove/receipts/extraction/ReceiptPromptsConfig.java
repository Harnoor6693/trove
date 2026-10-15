package finance.trove.receipts.extraction;

import finance.trove.app.YamlPropertySourceFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Makes {@code prompts/receipt.yml} a property source so {@link ReceiptPromptsProperties} can bind
 * from it (collapse step 14; the shape {@code CoachPromptsConfig} set in step 13).
 *
 * <p>The annotation is here rather than on the properties record because {@code @PropertySource} is
 * processed while configuration classes are parsed, which happens before any
 * {@code @ConfigurationProperties} bean is bound — and a record registered by
 * {@code @ConfigurationPropertiesScan} is never parsed as a configuration class. On the record the
 * annotation would be silently ignored and the prompt would take its default.
 */
@Configuration(proxyBeanMethods = false)
@PropertySource(value = "classpath:prompts/receipt.yml",
    factory = YamlPropertySourceFactory.class)
public class ReceiptPromptsConfig {
}
