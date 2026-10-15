package finance.trove.ai.chat;

import finance.trove.app.YamlPropertySourceFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Makes {@code prompts/coach.yml} a property source so {@link CoachPromptsProperties} can bind
 * from it (collapse step 13).
 *
 * <p>The annotation is here rather than on the properties record itself because
 * {@code @PropertySource} is processed while configuration classes are parsed — which happens
 * before any {@code @ConfigurationProperties} bean is bound — and a record registered by
 * {@code @ConfigurationPropertiesScan} is never parsed as a configuration class. On the record the
 * annotation would be ignored, and every field would silently take its default.
 *
 * <p>The file is deliberately not in {@code application.yml}: prompt text is content that belongs
 * beside the code that sends it, an operator has no reason to tune it, and the source's attempt to
 * keep half of it in each place is what produced the duplicate {@code max-history-messages} key
 * (inventory model-manager §12).
 */
@Configuration(proxyBeanMethods = false)
@PropertySource(value = "classpath:prompts/coach.yml", factory = YamlPropertySourceFactory.class)
public class CoachPromptsConfig {
}
