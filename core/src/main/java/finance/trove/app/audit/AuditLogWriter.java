package finance.trove.app.audit;

import finance.trove.app.AsyncConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Appends rows to {@code audit_log} (V11), off the request thread.
 *
 * <h2>Why asynchronous, and why bounded</h2>
 * §4.2 requires the row to be written after the response has been sent and never to delay it. The
 * executor is {@link AsyncConfig#AUDIT_EXECUTOR}, which is bounded and discards the oldest queued
 * record when it is full: under a flood, losing the oldest audit rows is a far better outcome than
 * either blocking the request threads behind a saturated queue or running a database insert on the
 * HTTP thread — the {@code CallerRunsPolicy} trap §5.7 names for the receipt executor is the same
 * trap here.
 *
 * <h2>Why JdbcTemplate</h2>
 * The same reasons as {@code SecurityEventRecorder} and {@code LoginAttemptRepository}: the table
 * is append-only, never updated, and {@code client_ip} is a Postgres {@code inet} with no clean
 * Hibernate mapping that survives {@code ddl-auto: validate}.
 *
 * <h2>Why no {@code @Transactional}</h2>
 * A single autocommitted {@code INSERT} needs none, and adding one here would be actively risky:
 * {@code @Async} and {@code @Transactional} on the same method are two proxies whose relative
 * order Spring does not guarantee, so the transaction can end up opened on the CALLING thread —
 * the request thread — which is the one thing this class exists to keep out of. There is no caller
 * transaction to join either: the request's committed before the response was sent.
 */
@Component
public class AuditLogWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogWriter.class);

    private static final String INSERT = """
        INSERT INTO audit_log
            (user_id, household_id, method, path, status, duration_ms, client_ip, request_id)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS inet), ?)
        """;

    private static final String DELETE_BEFORE = "DELETE FROM audit_log WHERE created_at < ?";

    /** {@code audit_log.path} is VARCHAR(500); a longer value is truncated rather than lost. */
    private static final int PATH_MAX = 500;

    private final JdbcTemplate jdbc;

    public AuditLogWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes one row on the audit executor. Never throws back at the filter. */
    @Async(AsyncConfig.AUDIT_EXECUTOR)
    public void write(AuditRecord record) {
        save(record);
    }

    /**
     * The write itself, callable synchronously.
     *
     * <p>Exists so that a test can prove what lands in the table without racing the executor, and
     * so that {@link #write} is a single annotated line whose only job is the thread hop.
     */
    void save(AuditRecord record) {
        try {
            jdbc.update(INSERT,
                record.userId(),
                record.householdId(),
                record.method(),
                truncate(record.path()),
                record.status(),
                record.durationMs(),
                record.clientIp(),
                record.requestId());
        } catch (org.springframework.dao.DataAccessException e) {
            // swallow-ok: the response has already been sent. There is no caller left to tell and
            // nothing useful to do but say so loudly — an audit outage is its own incident, not a
            // reason to fail a request that already succeeded.
            LOG.error("Could not append audit_log for requestId={} {} {}", record.requestId(),
                record.method(), record.path(), e);
        }
    }

    /**
     * Deletes every row older than {@code cutoff}, and answers how many.
     *
     * <p>Called by {@code PurgeJobs}, on a ShedLock-guarded schedule, for the reason that class
     * exists at all: this table takes a row for every mutating request the install ever handles
     * and nothing else ever removes one. Adding a fifth append-only table to an install while
     * writing the job that sweeps the other four would have been the same defect in a new place
     * (confirmed review finding 6).
     *
     * <p>It lives on the writer rather than on {@code AuditLogQueryService} because it is a write:
     * the query service is what {@code GET /v1/audit} reads through, and it is household-scoped by
     * construction, which a retention sweep must not be.
     */
    public int purgeBefore(Instant cutoff) {
        // OffsetDateTime rather than java.sql.Timestamp: pgjdbc binds it straight to timestamptz,
        // and ArchitectureTest names the three classes that are allowed to touch a legacy date
        // type — deliberately, so that a fourth is a decision somebody makes rather than an
        // import somebody added (gap P2-15).
        return jdbc.update(DELETE_BEFORE, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    private static String truncate(String path) {
        if (path == null) {
            return "-";
        }
        return path.length() <= PATH_MAX ? path : path.substring(0, PATH_MAX);
    }
}
