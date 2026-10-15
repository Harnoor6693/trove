package finance.trove.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The audit write itself: what reaches the database, and what happens when it cannot.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock
    private JdbcTemplate jdbc;

    private AuditRecord record(String path) {
        return new AuditRecord("POST", path, 200, UUID.randomUUID(), UUID.randomUUID(),
            "198.18.0.7", UUID.randomUUID(), 12L);
    }

    @Test
    @DisplayName("A row is inserted with the captured values, in the column order of the INSERT")
    void theRowIsInserted() {
        AuditRecord captured = record("/api/v1/me");

        new AuditLogWriter(jdbc).save(captured);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        // Each parameter is captured in its own position, because the previous version of this
        // test asserted only that SOME arguments were passed — which would still have passed if
        // save() had swapped the user for the household, nulled the address or dropped the
        // status. The INSERT's column list is the contract this asserts against.
        verify(jdbc).update(sql.capture(), eq(captured.userId()), eq(captured.householdId()),
            eq(captured.method()), eq(captured.path()), eq(captured.status()),
            eq(captured.durationMs()), eq(captured.clientIp()), eq(captured.requestId()));

        assertThat(sql.getValue())
            .as("the placeholders have to be in the same order as the values above, and the "
                + "address has to be cast: client_ip is a Postgres inet")
            .contains("(user_id, household_id, method, path, status, duration_ms, client_ip, "
                + "request_id)")
            .contains("VALUES (?, ?, ?, ?, ?, ?, CAST(? AS inet), ?)");
    }

    @Test
    @DisplayName("The retention sweep deletes past its cutoff and answers how many rows went")
    void theRetentionSweepDeletes() {
        Instant cutoff = Instant.parse("2025-09-04T00:00:00Z");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(7);

        assertThat(new AuditLogWriter(jdbc).purgeBefore(cutoff)).isEqualTo(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),
            eq(OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC)));
        assertThat(sql.getValue())
            .as("without this sweep audit_log is the fifth append-only table on the install that "
                + "grows for ever — the exact defect PurgeJobs exists to fix")
            .isEqualTo("DELETE FROM audit_log WHERE created_at < ?");
    }

    @Test
    @DisplayName("A path longer than the column is truncated, not dropped")
    void anOverlongPathIsTruncated() {
        new AuditLogWriter(jdbc).save(record("/api/" + "x".repeat(1000)));

        ArgumentCaptor<Object> path = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), any(), any(), any(), path.capture(), any(), any(),
            any(), any());
        assertThat((String) path.getValue())
            .as("audit_log.path is VARCHAR(500); losing the row entirely to keep a long path "
                + "would be the wrong trade for an audit table")
            .hasSize(500);
    }

    @Test
    @DisplayName("A null path becomes a placeholder rather than a NOT NULL violation")
    void aNullPathIsReplaced() {
        new AuditLogWriter(jdbc).save(new AuditRecord("POST", null, 200, null, null, null,
            UUID.randomUUID(), 1L));

        ArgumentCaptor<Object> path = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(anyString(), any(), any(), any(), path.capture(), any(), any(),
            any(), any());
        assertThat(path.getValue()).isEqualTo("-");
    }

    @Test
    @DisplayName("A database failure is logged and swallowed — the response has already been sent")
    void aDatabaseFailureIsSwallowed() {
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new QueryTimeoutException("the audit table is unavailable"));

        assertThatCode(() -> new AuditLogWriter(jdbc).save(record("/api/v1/me")))
            .as("there is no caller left to tell: an audit outage is its own incident, not a "
                + "reason to fail a request that already succeeded")
            .doesNotThrowAnyException();
    }
}
