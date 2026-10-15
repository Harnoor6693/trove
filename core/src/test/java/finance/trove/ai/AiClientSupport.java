package finance.trove.ai;

import static org.mockito.Mockito.mock;

import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.app.HttpClientConfig;
import finance.trove.app.HttpClientProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds a real {@link OpenAiCompatibleClient} — the production one, through the production
 * {@link HttpClientConfig} — for tests that talk to {@link AiStubServer}.
 *
 * <p>Going through {@code HttpClientConfig} rather than constructing a {@code RestClient} here is
 * the point: the pinned DNS resolver, the disabled redirects and the timeout wiring are the
 * behaviour under test as much as the JSON handling is, and a test that built its own client would
 * prove nothing about the one that ships. It is also the only way to build one at all —
 * {@code ArchitectureTest} fails the build if any class outside {@code HttpClientConfig} calls
 * {@code RestClient.builder()}.
 */
final class AiClientSupport {

    private AiClientSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** A client with generous install-wide limits, so a test's own timeouts are what bite. */
    static OpenAiCompatibleClient client() {
        return client(new HttpClientProperties(2_000, 30_000));
    }

    static OpenAiCompatibleClient client(HttpClientProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);
        return new OpenAiCompatibleClient(
            new HttpClientConfig(properties, noBuildInfo).pinnedRestClientFactory(),
            JsonMapper.builder().build());
    }
}
