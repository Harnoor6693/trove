package finance.trove.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.http.*} — the timeouts every outbound HTTP call inherits
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5).
 *
 * <p>Defaults are 5 s to connect and 30 s to read. They are properties rather than constants
 * because a self-hoster running Ollama on a Raspberry Pi legitimately needs a longer read than a
 * self-hoster calling a cloud endpoint, and because §5.5's named builders (AI at 120 s, webhooks
 * at 10 s) derive from this pair rather than repeating literals.
 *
 * @param connectTimeoutMs milliseconds to establish a TCP connection
 * @param readTimeoutMs    milliseconds to wait for a response
 */
@ConfigurationProperties(prefix = "trove.http")
public record HttpClientProperties(int connectTimeoutMs, int readTimeoutMs) {

    static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    public HttpClientProperties {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        }
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }
}
