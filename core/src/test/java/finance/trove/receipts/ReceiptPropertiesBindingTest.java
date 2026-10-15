package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * That the operator's environment variable actually reaches this record.
 *
 * <p><strong>The defect this pins was invisible and total.</strong> Spring's relaxed environment
 * binding maps a property path to an environment variable by uppercasing each dot-separated
 * element and joining them with {@code _}, so {@code trove.receipts.quota-mb} could only ever be
 * set by {@code TROVE_RECEIPTS_QUOTA_MB} — while {@code deploy/.env.example},
 * {@code deploy/docker-compose.yml} and {@code deploy/README.md} all define and pass through
 * {@code TROVE_RECEIPT_QUOTA_MB}, singular. An operator following the deployment documentation
 * would have edited that variable, restarted, seen no error anywhere, and still had the built-in
 * 2 GB allowance. The prefix is singular for that reason and this test is why it stays singular:
 * it reads the prefix off the annotation, so renaming it to the plural of the package name fails
 * here rather than in somebody's install.
 */
class ReceiptPropertiesBindingTest {

    private static ReceiptProperties bind(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        String prefix = ReceiptProperties.class
            .getAnnotation(ConfigurationProperties.class).value();
        return Binder.get(environment).bind(prefix, ReceiptProperties.class)
            .orElseGet(() -> new ReceiptProperties(null, null, null, null, null, null, null, null));
    }

    @Test
    @DisplayName("TROVE_RECEIPT_QUOTA_MB, the variable deploy/ documents, really binds")
    void theDocumentedEnvironmentVariableBinds() {
        ReceiptProperties bound = bind(Map.of("TROVE_RECEIPT_QUOTA_MB", "64"));

        assertThat(bound.quotaMb())
            .as("an operator who edits the documented variable must get the value they typed, "
                + "not the built-in default and no error")
            .isEqualTo(64);
        assertThat(bound.quotaBytes()).isEqualTo(64L * 1024 * 1024);
    }

    @Test
    @DisplayName("the rest of the knobs bind from their documented variables too")
    void theOtherKnobsBind() {
        ReceiptProperties bound = bind(Map.of(
            "TROVE_RECEIPT_MAX_FILE_BYTES", "1048576",
            "TROVE_RECEIPT_MAX_MEGAPIXELS", "12",
            "TROVE_RECEIPT_MAX_LONG_EDGE_PX", "1200",
            "TROVE_RECEIPT_THUMBNAIL_PX", "200",
            "TROVE_RECEIPT_MAX_CONCURRENT_RENDERS", "3",
            "TROVE_RECEIPT_STUCK_AFTER", "PT2H"));

        assertThat(bound.maxFileBytes()).isEqualTo(1_048_576L);
        assertThat(bound.maxMegapixels()).isEqualTo(12);
        assertThat(bound.maxLongEdgePx()).isEqualTo(1_200);
        assertThat(bound.thumbnailPx()).isEqualTo(200);
        assertThat(bound.maxConcurrentRenders()).isEqualTo(3);
        assertThat(bound.stuckAfter()).hasHours(2);
    }

    @Test
    @DisplayName("nothing set is every default, and the defaults are the documented ones")
    void anEmptyEnvironmentIsAllDefaults() {
        ReceiptProperties bound = bind(Map.of("SOMETHING_ELSE", "1"));

        assertThat(bound.quotaMb()).isEqualTo(ReceiptProperties.DEFAULT_QUOTA_MB);
        assertThat(bound.maxFileBytes()).isEqualTo(ReceiptProperties.DEFAULT_MAX_FILE_BYTES);
        assertThat(bound.maxMegapixels()).isEqualTo(ReceiptProperties.DEFAULT_MAX_MEGAPIXELS);
        assertThat(bound.maxConcurrentRenders())
            .isEqualTo(ReceiptProperties.DEFAULT_MAX_CONCURRENT_RENDERS);
    }
}
