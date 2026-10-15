package finance.trove.ingestion.simplefin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.simplefin.*} — the timeouts and the response ceiling for calls to a SimpleFIN
 * bridge ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5; gap P2-10).
 *
 * <p><strong>These were decorative before.</strong> The pre-collapse {@code SimplefinProperties}
 * declared {@code timeoutSeconds} and {@code connectTimeoutSeconds}, and
 * {@code SimplefinClient} used them only to phrase an error message — the {@code RestClient} it
 * built had no timeouts at all (plugins.md §3, config-keys table), so a bridge that accepted a
 * connection and never answered pinned a request thread indefinitely. They are wired into the
 * pinned client here.
 *
 * <p>{@link #maxResponseBytes()} is new and is gap P2-10's response cap. A bridge is a third party
 * on the public internet; without a ceiling, an answer that never ends is an out-of-memory error
 * in a process that also holds the household's ledger.
 *
 * @param connectTimeoutMs milliseconds to establish a connection to the bridge
 * @param readTimeoutMs    milliseconds to wait for its answer
 * @param maxResponseBytes largest account-set body that will be read
 */
@ConfigurationProperties(prefix = "trove.simplefin")
public record SimplefinProperties(int connectTimeoutMs, int readTimeoutMs, long maxResponseBytes) {

    static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    /** 8 MB. A bridge answer is JSON describing a few accounts and a few thousand transactions. */
    static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    public SimplefinProperties {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        }
        if (maxResponseBytes <= 0) {
            maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
        }
    }

    public Duration connectTimeout() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration readTimeout() {
        return Duration.ofMillis(readTimeoutMs);
    }

    /** The production defaults, for a unit test that needs them without a Spring context. */
    public static SimplefinProperties defaults() {
        return new SimplefinProperties(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS,
            DEFAULT_MAX_RESPONSE_BYTES);
    }
}
