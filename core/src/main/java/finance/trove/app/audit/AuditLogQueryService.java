package finance.trove.app.audit;

import finance.trove.common.domain.PagedResult;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code audit_log} back for one household ({@code GET /v1/audit}, §4.2).
 *
 * <p><strong>Scoped to a household, always.</strong> The parameter comes from the authenticated
 * principal at the controller and never from a path, a query parameter or a header. There is no
 * method here that reads the table without a household, which is the same structural protection
 * {@code UserScopedRepository} gives the JPA repositories — this class is JdbcTemplate-based for
 * the {@code inet} column, so the protection has to be the absence of the unscoped method rather
 * than an inherited base.
 *
 * <p>Two queries per page rather than a window function: the count is what {@code meta.total}
 * needs, and Postgres plans {@code count(*)} over the composite index
 * {@code (household_id, created_at DESC)} as an index-only scan.
 */
@Service
public class AuditLogQueryService {

    /** Matches the spec's {@code LimitQuery} maximum; see {@code openapi/trove-core.yaml}. */
    public static final int MAX_LIMIT = 200;

    /** What a client gets when it asks for no particular page size. */
    public static final int DEFAULT_LIMIT = 50;

    private static final String COUNT =
        "SELECT count(*) FROM audit_log WHERE household_id = ?";

    private static final String SELECT = """
        SELECT id, method, path, status, user_id, client_ip, request_id, duration_ms, created_at
        FROM audit_log
        WHERE household_id = ?
        ORDER BY created_at DESC, id DESC
        LIMIT ? OFFSET ?
        """;

    /**
     * {@code created_at DESC, id DESC}: a tie on the timestamp — two requests inside the same
     * microsecond, which a burst produces — would otherwise order arbitrarily, and an arbitrary
     * order across pages means a row can appear on two of them or on neither.
     */
    private static final RowMapper<AuditLogEntry> MAPPER = (ResultSet rs, int row) ->
        new AuditLogEntry(
            rs.getObject("id", UUID.class),
            rs.getString("method"),
            rs.getString("path"),
            rs.getInt("status"),
            rs.getObject("user_id", UUID.class),
            rs.getString("client_ip"),
            rs.getObject("request_id", UUID.class),
            rs.getLong("duration_ms"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)));

    private final JdbcTemplate jdbc;

    public AuditLogQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One page of {@code householdId}'s audit log, newest first.
     *
     * @param page  zero-based
     * @param limit rows per page; the caller has already validated the range
     */
    @Transactional(readOnly = true)
    public PagedResult<AuditLogEntry> page(UUID householdId, int page, int limit) {
        Long total = jdbc.queryForObject(COUNT, Long.class, householdId);
        long matching = total == null ? 0L : total;
        if (matching == 0) {
            return PagedResult.empty(page, limit);
        }
        List<AuditLogEntry> rows =
            jdbc.query(SELECT, MAPPER, householdId, limit, (long) page * limit);
        return new PagedResult<>(rows, matching, page, limit);
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
