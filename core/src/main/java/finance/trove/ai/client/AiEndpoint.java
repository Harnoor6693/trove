package finance.trove.ai.client;

import finance.trove.common.net.ResolvedTarget;
import java.time.Duration;
import java.util.Objects;

/**
 * Everything one outbound AI call needs, assembled fresh for that call.
 *
 * <p>It is a per-call value rather than a cached client for one reason: {@link #target} carries the
 * IP {@code OutboundUrlPolicy} approved <em>just now</em>. Holding a configured client from the
 * moment the settings were saved would mean the egress policy ran once, at save time, and the
 * address it validated could be a different one by the time anything connected — which is the DNS
 * rebinding hole the pinning exists to close (§2.8/§5.5, §9 risk 9). Building this record on every
 * call is what makes "the policy is re-evaluated on every call" true rather than aspirational.
 *
 * <p>{@link #apiKey} is plaintext and lives only as long as the call. Nothing in this package logs
 * it, puts it in an exception, or writes it anywhere.
 *
 * @param target         the approved destination, with its pinned address
 * @param apiKey         the decrypted key, or null for an endpoint that needs none
 * @param model          the chat model id
 * @param visionModel    the vision model id, or null when none is configured
 * @param connectTimeout the effective connect timeout, already capped against the install's limit
 * @param readTimeout    the effective read timeout, already capped against the install's limit
 */
public record AiEndpoint(ResolvedTarget target, String apiKey, String model, String visionModel,
                         Duration connectTimeout, Duration readTimeout) {

    public AiEndpoint {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout cannot be null");
        Objects.requireNonNull(readTimeout, "readTimeout cannot be null");
    }

    /** The host alone — what may appear in a log line, an audit row or an error message. */
    public String host() {
        return target.host();
    }
}
