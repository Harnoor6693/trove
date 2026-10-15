package finance.trove.app.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Forces the interleaving a race test needs, using a real Postgres row lock as the latch.
 *
 * <p>A start latch alone only makes two requests <em>begin</em> together; whether both reach the
 * check before either reaches the write is then down to scheduling, and a test that depends on
 * that passes on a fast machine for the wrong reason. This holds a {@code FOR UPDATE} lock on a
 * row both requests must write, from a connection of its own, and lets go only once two other
 * sessions are queued behind a lock. By then each request has done every read it does before
 * that write, so the check-then-act window is held open for both at once — deterministically.
 *
 * <p>Without a fix both requests are parked on this lock with their checks already passed, and
 * both go on to succeed. With a fix that takes its own lock (or claims the row atomically) the
 * second request queues on the first instead, and after release it sees the first one's write.
 * The gate counts lock waits of either kind, so the same test drives both outcomes.
 *
 * <p>The count is read through {@code jdbc}, not the gate's own connection:
 * {@code pg_stat_activity} is snapshotted once per transaction, so polling it from inside the
 * transaction that holds the lock would see the same stale answer forever.
 */
public final class RowLockGate implements AutoCloseable {

    private static final String WAITERS = """
        SELECT count(*) FROM pg_stat_activity
         WHERE datname = current_database()
           AND wait_event_type = 'Lock'
           AND pid <> ?
        """;

    private final Connection connection;
    private final JdbcTemplate jdbc;
    private final int pid;

    private RowLockGate(Connection connection, JdbcTemplate jdbc, int pid) {
        this.connection = connection;
        this.jdbc = jdbc;
        this.pid = pid;
    }

    /**
     * Opens a transaction and runs {@code lockingSql} in it, typically
     * {@code SELECT 1 FROM t WHERE id = ? FOR UPDATE}.
     */
    public static RowLockGate hold(JdbcTemplate jdbc, String lockingSql, Object... args)
        throws SQLException {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("The JdbcTemplate has no DataSource");
        }
        Connection connection = dataSource.getConnection();
        try {
            connection.setAutoCommit(false);
            int pid;
            try (PreparedStatement statement =
                     connection.prepareStatement("SELECT pg_backend_pid()");
                 var result = statement.executeQuery()) {
                result.next();
                pid = result.getInt(1);
            }
            try (PreparedStatement statement = connection.prepareStatement(lockingSql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                statement.execute();
            }
            return new RowLockGate(connection, jdbc, pid);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /** Blocks until {@code waiters} other sessions are waiting on a lock, or fails. */
    public void awaitWaiters(int waiters, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Long waiting = jdbc.queryForObject(WAITERS, Long.class, pid);
            if (waiting != null && waiting >= waiters) {
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Only " + waiting + " of " + waiters
                    + " sessions reached the lock within " + timeout);
            }
            Thread.sleep(20);
        }
    }

    /** Releases the lock by ending the transaction. */
    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }
}
