package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The egress log's two defensive properties: it never grows without bound, and it never turns an
 * AI call's outcome into a different one by failing itself.
 */
class AiAuditRecorderTest {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000cc");

    private JdbcTemplate jdbc;
    private AiAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        recorder = new AiAuditRecorder(jdbc);
    }

    @Test
    @DisplayName("the row carries the host, the operation and the tier, in that column order")
    void writesTheExpectedColumns() {
        recorder.record(USER, "CHAT", "ai.example.test", "llama3", PrivacyTier.LOCAL_ONLY, 41, 7,
            123L, true, null);

        assertThat(boundParameters()).containsExactly(USER, "CHAT", "ai.example.test", "llama3",
            "LOCAL_ONLY", 41, 7, 123L, true, null);
    }

    @Test
    @DisplayName("a null privacy tier is written as the safe one rather than violating NOT NULL")
    void defaultsAMissingTier() {
        recorder.record(USER, "MODELS", "ai.example.test", null, null, null, null, 5L, true, null);

        assertThat(boundParameters()[4]).isEqualTo("CLOUD_OK");
    }

    @Test
    @DisplayName("an over-long error message is clipped, so one bad call cannot fill the table")
    void clipsALongMessage() {
        recorder.record(USER, "CHAT", "ai.example.test", "llama3", PrivacyTier.CLOUD_OK, null,
            null, 9L, false, "x".repeat(5_000));

        assertThat((String) boundParameters()[9])
            .as("the column is TEXT, but an unbounded message from a caller is still clipped")
            .hasSize(500);
    }

    @Test
    @DisplayName("an unavailable audit table does not change the outcome of the call it describes")
    void swallowsADatabaseFailure() {
        doThrow(new DataAccessResourceFailureException("the audit table is gone"))
            .when(jdbc).update(anyString(), org.mockito.ArgumentMatchers.<Object>any());

        assertThatCode(() -> recorder.record(USER, "CHAT", "ai.example.test", "llama3",
            PrivacyTier.CLOUD_OK, null, null, 9L, true, null))
            .as("a failed audit write must not turn a working AI call into a 500, nor a failed "
                + "one into a success")
            .doesNotThrowAnyException();
    }

    /**
     * The ten bound parameters of the single INSERT.
     *
     * <p>Captured as one {@code Object[]} rather than as ten captures: {@code JdbcTemplate.update}
     * takes varargs, and a per-element captor matches only a one-element call.
     */
    private Object[] boundParameters() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        return args.getValue();
    }
}
