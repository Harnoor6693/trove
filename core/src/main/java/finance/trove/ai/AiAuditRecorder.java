package finance.trove.ai;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends rows to {@code ai_audit_log} (schema V17; owner decision 9 — every member is entitled to
 * see what left this server on their behalf).
 *
 * <p>Written with {@code JdbcTemplate} rather than an entity for the same reason
 * {@code SecurityEventRecorder} is: the table is append-only, is never updated, and buying
 * dirty-checking and a first-level cache for a row written once is the wrong trade.
 *
 * <p><strong>{@code REQUIRES_NEW}, and that is the point.</strong> The row that matters most is the
 * one describing a call that failed — a refused key, an unreachable host, a payload the privacy
 * tier blocked. If the row shared the caller's transaction, the rollback that followed the failure
 * would take the evidence with it, and the log would keep working while quietly recording only the
 * successes.
 *
 * <p><strong>What is not in this table.</strong> The prompt, the completion, the image, the API key
 * and the endpoint's own response body. {@code errorMessage} carries the slug and a sentence this
 * server composed, never anything the endpoint said: an OpenAI-compatible server routinely echoes
 * the request inside its errors.
 */
@Component
public class AiAuditRecorder {

    /** Column is TEXT, but an unbounded message from a caller is still worth clipping. */
    private static final int MESSAGE_MAX = 500;

    private static final String INSERT = """
        INSERT INTO ai_audit_log (user_id, operation, endpoint_host, model_id, privacy_tier,
                                  prompt_tokens, output_tokens, latency_ms, success, error_message)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final Logger LOG = LoggerFactory.getLogger(AiAuditRecorder.class);

    private final JdbcTemplate jdbc;

    public AiAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One row per outbound call.
     *
     * @param operation    one of the V17 CHECK values: CHAT, CHAT_STREAM, VISION, MODELS, TEST
     * @param endpointHost the host contacted — never the full URL, whose query can carry a token
     * @param errorMessage the slug and a short reason on failure, null on success
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String operation, String endpointHost, String modelId,
                       PrivacyTier privacyTier, Integer promptTokens, Integer outputTokens,
                       long latencyMs, boolean success, String errorMessage) {
        try {
            jdbc.update(INSERT, userId, operation, endpointHost, modelId,
                privacyTier == null ? PrivacyTier.CLOUD_OK.name() : privacyTier.name(),
                promptTokens, outputTokens, latencyMs, success, clip(errorMessage));
        } catch (org.springframework.dao.DataAccessException e) {
            // swallow-ok: an unavailable audit table must not turn a working AI call into a 500,
            // and must not turn a failed one into a success either. The call's outcome is already
            // decided; this row is evidence about it. The failure is logged at ERROR in its own
            // right so it is visible rather than silent.
            LOG.error("Could not append ai_audit_log operation={} host={}", operation, endpointHost,
                e);
        }
    }

    private static String clip(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX ? message : message.substring(0, MESSAGE_MAX);
    }
}
