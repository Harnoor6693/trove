package finance.trove.app;

import java.io.IOException;
import java.util.Properties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertySourceFactory;

/**
 * Lets {@code @PropertySource} read a YAML file ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.10,
 * moved from {@code services/model-manager/config}).
 *
 * <p>Spring's own {@code @PropertySource} understands {@code .properties} and nothing else; the
 * annotation predates YAML support and has never been extended, so a YAML file named there is
 * parsed as properties and silently yields one nonsense key per line. This factory is the standard
 * three-line answer, and it is here rather than in a domain package because it is content-free
 * plumbing that any package may need.
 *
 * <p>It is used by exactly one thing today: the coach's prompt file
 * ({@code classpath:prompts/coach.yml}, collapse step 13). That file is prompt <em>content</em> —
 * a system prompt, a context template, suggestion lists — rather than deployment configuration,
 * which is why it lives beside the code it belongs to instead of in {@code application.yml}, where
 * an operator would be invited to edit it and where the source's duplicate
 * {@code trove.coach.max-history-messages} key came from (inventory §12: two property sources
 * bound one field, and which won depended on load order).
 */
public class YamlPropertySourceFactory implements PropertySourceFactory {

    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource)
        throws IOException {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        Properties properties = factory.getObject();
        if (properties == null) {
            throw new IOException("Could not read YAML property source from "
                + resource.getResource().getDescription());
        }
        String sourceName = name != null ? name : resource.getResource().getFilename();
        return new PropertiesPropertySource(
            sourceName == null ? "yaml" : sourceName, properties);
    }
}
