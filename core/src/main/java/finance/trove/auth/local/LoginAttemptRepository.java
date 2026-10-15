package finance.trove.auth.local;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only access to {@code login_attempt} (schema V8; gap P2-5).
 *
 * <p><strong>The address is hashed, not stored.</strong> Every failed login is recorded, including
 * ones for addresses that exist nowhere on this install. Storing them verbatim would turn the
 * brute-force log into a list of every address anyone has ever guessed at this server — a file
 * worth stealing that the feature does not need. SHA-256 of the lower-cased address is enough to
 * count attempts per account, which is the only question asked of it.
 *
 * <p><strong>JdbcTemplate, not JPA</strong>: the table is written once per attempt, read only as
 * an aggregate, and its {@code ip} column is a Postgres {@code inet} that has no clean Hibernate
 * mapping surviving {@code ddl-auto: validate}. See {@code SecurityEventRecorder} for the same
 * reasoning.
 *
 * <p>The IP always arrives from {@code request.getRemoteAddr()} after the trusted-proxy filter —
 * never parsed by hand out of {@code X-Forwarded-For}, which any client can extend to move itself
 * into a different bucket (§1.5, §5.1).
 */
@Repository
public class LoginAttemptRepository {

    private static final String INSERT = """
        INSERT INTO login_attempt (email_hash, ip, success, at)
        VALUES (?, CAST(? AS inet), ?, ?)
        """;

    private static final String COUNT_FAILURES_SINCE = """
        SELECT count(*) FROM login_attempt
        WHERE email_hash = ? AND success = false AND at >= ?
        """;

    private static final String FAILURES_FROM_SINCE = """
        SELECT at FROM login_attempt
        WHERE email_hash = ? AND ip = CAST(? AS inet) AND success = false AND at >= ?
        ORDER BY at
        """;

    private static final String HAS_SUCCEEDED_FROM = """
        SELECT EXISTS (SELECT 1 FROM login_attempt
                        WHERE email_hash = ? AND ip = CAST(? AS inet) AND success)
        """;

    private static final String COUNT_IP_FAILURES_SINCE = """
        SELECT count(*) FROM login_attempt
        WHERE ip = CAST(? AS inet) AND success = false AND at >= ?
        """;

    private final JdbcTemplate jdbc;

    public LoginAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String email, String ip, boolean success, Instant at) {
        jdbc.update(INSERT, hashEmail(email), ip, success, Timestamp.from(at));
    }

    /** Failed attempts against one account, from every address, since {@code since}. */
    public long countFailuresSince(String email, Instant since) {
        Long count = jdbc.queryForObject(COUNT_FAILURES_SINCE, Long.class,
            hashEmail(email), Timestamp.from(since));
        return count == null ? 0L : count;
    }

    /**
     * When each failed attempt against one account from one address since {@code since} was made,
     * oldest first. A list rather than a count because the throttle is judged from the gaps
     * between failures; attempts made while throttled are never recorded, so it stays short.
     */
    public List<Instant> failuresFromSince(String email, String ip, Instant since) {
        if (ip == null) {
            return List.of();
        }
        return jdbc.query(FAILURES_FROM_SINCE,
            (row, n) -> row.getTimestamp(1).toInstant(), hashEmail(email), ip,
            Timestamp.from(since));
    }

    /**
     * Whether this account has ever been signed in to from this address, as far back as the
     * table goes ({@code PurgeJobs}' retention).
     */
    public boolean hasSucceededFrom(String email, String ip) {
        if (ip == null) {
            return false;
        }
        return Boolean.TRUE.equals(
            jdbc.queryForObject(HAS_SUCCEEDED_FROM, Boolean.class, hashEmail(email), ip));
    }

    /**
     * Forgets every failed attempt against one account — an administrator's unlock, or a password
     * reset. The successes stay, so the addresses the owner signs in from are still recognised.
     */
    public int forgetFailures(String email) {
        return jdbc.update("DELETE FROM login_attempt WHERE email_hash = ? AND success = false",
            hashEmail(email));
    }

    /**
     * Failed attempts from one address since {@code since}. Collapse step 4's per-IP bucket is
     * the enforcement point; this is the query it will read, and having it here means the
     * evidence table has one owner rather than two.
     */
    public long countIpFailuresSince(String ip, Instant since) {
        if (ip == null) {
            return 0L;
        }
        Long count = jdbc.queryForObject(COUNT_IP_FAILURES_SINCE, Long.class,
            ip, Timestamp.from(since));
        return count == null ? 0L : count;
    }

    /** Daily purge horizon (§5.6 {@code login-attempt-purge}); returns rows removed. */
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM login_attempt WHERE at < ?", Timestamp.from(cutoff));
    }

    /** SHA-256 hex of the lower-cased address — the only form this table ever holds. */
    public static String hashEmail(String email) {
        String normalised = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(normalised.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }
}
