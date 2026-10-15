package finance.trove.app.audit;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code security_event} back for the account it belongs to (gap P2-17).
 *
 * <p>{@code JdbcTemplate} for the same reasons {@link SecurityEventRecorder} writes that way: the
 * table is append-only, carries {@code inet} and {@code jsonb}, and an entity would buy
 * dirty-checking for rows that are never updated.
 *
 * <p><strong>Scoped to one user, always.</strong> The parameter is taken from the authenticated
 * principal at the controller and never from a path or a query string. A security log is a list of
 * where somebody has signed in from; handing one household member another's would be a worse leak
 * than most of the ledger data this application protects.
 */
@Service
public class SecurityEventQueryService {

    /**
     * Bounded, and not by a client-supplied page size. This is a "recent activity" panel, not a
     * report; an unbounded read of an append-only table grows without limit and the endpoint would
     * get slower for every user, for ever.
     */
    public static final int MAX_ROWS = 100;

    private static final String SELECT = """
        SELECT id, type, ip, ua_summary, created_at
        FROM security_event
        WHERE user_id = ?
        ORDER BY created_at DESC
        LIMIT ?
        """;

    /**
     * {@code OffsetDateTime} rather than {@code getTimestamp}: pgjdbc maps {@code timestamptz} to
     * it natively, and {@code java.sql.Timestamp} would drag a legacy date type into a class that
     * has no need of one — which {@code ArchitectureTest} refuses outside the two boundaries that
     * genuinely cannot avoid it (gap P2-15).
     */
    private static final RowMapper<SecurityEventView> MAPPER = (ResultSet rs, int row) ->
        new SecurityEventView(
            rs.getObject("id", UUID.class),
            rs.getString("type"),
            toInstant(rs.getObject("created_at", OffsetDateTime.class)),
            rs.getString("ip"),
            rs.getString("ua_summary"));

    private final JdbcTemplate jdbc;

    public SecurityEventQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The caller's own recent activity, newest first. */
    @Transactional(readOnly = true)
    public List<SecurityEventView> recentFor(UUID userId) {
        return jdbc.query(SELECT, MAPPER, userId, MAX_ROWS);
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
