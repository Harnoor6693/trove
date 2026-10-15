package finance.trove.app.audit;

import finance.trove.common.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends rows to {@code security_event} (gap P2-17).
 *
 * <p><strong>Why JdbcTemplate and not JPA.</strong> The table is append-only by design — the
 * application's database role is granted INSERT and SELECT only — and it carries two Postgres
 * types with no clean Hibernate mapping that survives {@code ddl-auto: validate}: {@code inet} and
 * {@code jsonb}. An entity would buy dirty-checking and a first-level cache for rows that are
 * written once and never updated, at the cost of either weakening the column types or carrying a
 * custom {@code JdbcType} for each. Writing the two casts here is the smaller commitment.
 *
 * <p><strong>Why {@code REQUIRES_NEW}.</strong> A security event must survive the failure of the
 * thing it describes. A login that fails on the last step, a rollback in the calling service, a
 * constraint violation later in the same transaction — every one of those would erase the
 * evidence if the row shared the caller's transaction. It does not.
 *
 * <p><strong>What must never appear in {@code metadata}.</strong> No password, token, TOTP secret,
 * recovery code, or raw request URI. §2.1 is explicit that audit rows store route templates
 * because a raw URI can carry an OIDC authorization code in its query string.
 */
@Component
public class SecurityEventRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEventRecorder.class);

    private static final String INSERT = """
        INSERT INTO security_event (user_id, actor_id, type, ip, ua_summary, metadata)
        VALUES (?, ?, ?, CAST(? AS inet), ?, CAST(? AS jsonb))
        """;

    /** Enough of a user agent to recognise a device; never the whole fingerprintable string. */
    private static final int UA_SUMMARY_MAX = 200;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SecurityEventRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Records an event about {@code userId} caused by {@code userId} themselves. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityEventType type, UUID userId, Map<String, Object> metadata) {
        record(type, userId, userId, metadata);
    }

    /**
     * Records an event whose subject and actor differ — an admin clearing a member's TOTP, or a
     * failed login for an address that resolves to no user at all ({@code userId} null).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(SecurityEventType type, UUID userId, UUID actorId,
                       Map<String, Object> metadata) {
        HttpServletRequest request = currentRequest();
        String ip = ClientIpResolver.resolve(request);
        String ua = request == null ? null : truncate(request.getHeader("User-Agent"));
        String json = metadata == null || metadata.isEmpty()
            ? null
            : objectMapper.writeValueAsString(metadata);
        try {
            jdbc.update(INSERT, userId, actorId, type.name(), ip, ua, json);
        } catch (org.springframework.dao.DataAccessException e) {
            // swallow-ok: an unavailable audit table must not turn a successful login into a
            // 500 for the user, and must not turn a *failed* login into a success either. The
            // caller's decision has already been made; this row is evidence about it. The
            // failure is logged at ERROR so it is visible in its own right.
            LOG.error("Could not append security_event type={} userId={}", type, userId, e);
        }
    }

    /**
     * Records an event whose subject may not exist in the database yet.
     *
     * <p>{@link #record} opens its own transaction so the evidence survives the failure of the
     * thing it describes. That is right for a refusal — a rejected login, a lockout, a detected
     * token replay — and exactly wrong for an event about a row the CALLER has just inserted and
     * not yet committed: the new transaction cannot see that row, the foreign key to
     * {@code users} fails, and because this class swallows database errors (it must, or an audit
     * outage would turn a successful registration into a 500) the row is silently lost. That is
     * the worst possible failure mode for an append-only security log — it keeps working and
     * quietly stops recording.
     *
     * <p>So: when a transaction is in progress, the write is deferred until it commits, at which
     * point the subject exists and the foreign key holds. With no transaction in progress there is
     * nothing to wait for and this is exactly {@link #record}.
     *
     * <p>The trade is deliberate and narrow: an event registered this way is lost if the caller
     * rolls back. That is correct here — {@code INVITE_ACCEPTED} describes a registration that,
     * on a rollback, did not happen.
     */
    public void recordAfterCommit(SecurityEventType type, UUID userId, UUID actorId,
                                  Map<String, Object> metadata) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(type, userId, actorId, metadata);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record(type, userId, actorId, metadata);
                }
            });
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() <= UA_SUMMARY_MAX
            ? userAgent
            : userAgent.substring(0, UA_SUMMARY_MAX);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes()
            instanceof ServletRequestAttributes attributes
            ? attributes.getRequest()
            : null;
    }
}
