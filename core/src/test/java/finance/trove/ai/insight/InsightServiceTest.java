package finance.trove.ai.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.chat.CoachAudience;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Whose week, whose currency, whose jurisdiction
 * ({@code docs/recovery/07-READINESS-GAPS.md} MUST row 15; inventory model-manager D5, finding F1).
 *
 * <p>The source's {@code WeeklyInsightSchedulerTest} is not ported: it asserted
 * {@code generateInsightForUser(userId, "CA")} for every user, encoding the hard-coded-jurisdiction
 * bug as a passing test. These cases assert the opposite of that assertion.
 */
class InsightServiceTest {

    /** A Monday, 06:00 UTC: Toronto is already Monday; Vancouver is still Sunday. */
    private static final Instant RUN_AT = Instant.parse("2026-09-07T06:00:00Z");

    private final AiGateway gateway = mock(AiGateway.class);
    private final AiHandle handle = mock(AiHandle.class);
    private final CoachContextBuilder context = mock(CoachContextBuilder.class);
    private final CoachInsightRepository insights = mock(CoachInsightRepository.class);
    private final FieldEncryptionService encryption =
        new FieldEncryptionService("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private InsightService service;

    @BeforeEach
    void wire() {
        service = new InsightService(gateway, context,
            new CoachPromptsProperties("You are Trove.", "{{month_summary}}", 20, null),
            new CoachProperties(Duration.ofSeconds(5), 100),
            insights, encryption);
        when(gateway.forUser(any())).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(context.build(any(), any())).thenReturn("--- context ---");
        when(handle.chat(any(), any()))
            .thenReturn(new AiCompletion("A short note.", "qwen2.5:14b", null, null));
        when(insights.insertIfAbsent(any(), any(), anyString())).thenReturn(1);
    }

    @Test
    @DisplayName("the week is the recipient's own, so two zones give two boundaries")
    void theWeekBelongsToTheRecipient() {
        assertThat(InsightService.weekStartFor(audience("America/Toronto"), RUN_AT))
            .as("06:00 UTC on a Monday is already Monday in Toronto, so the week that just ended "
                + "began the Monday before")
            .isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(InsightService.weekStartFor(audience("America/Vancouver"), RUN_AT))
            .as("and still Sunday in Vancouver, so their completed week began a week earlier — "
                + "readiness gap row 15, and a container-clock LocalDate.now() cannot produce it")
            .isEqualTo(LocalDate.of(2026, 8, 24));
    }

    @Test
    @DisplayName("the week described is the one that ended, never the one in progress")
    void theWeekIsTheCompletedOne() {
        CoachAudience audience = audience("UTC");

        assertThat(InsightService.weekStartFor(audience, Instant.parse("2026-09-09T12:00:00Z")))
            .as("a note about three days of a week is a note about nothing")
            .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("the instruction names the member's own currency and jurisdiction, not CA or CAD")
    void theJurisdictionAndCurrencyAreTheMembers() {
        CoachAudience american = new CoachAudience(UUID.randomUUID(), UUID.randomUUID(), "Sam",
            CurrencyCode.USD, Jurisdiction.US, ZoneId.of("America/New_York"));

        service.generateFor(american, RUN_AT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMessage>> prompt = ArgumentCaptor.forClass(List.class);
        verify(handle).chat(prompt.capture(), eq(AiSensitivity.SENSITIVE));
        String instruction = prompt.getValue().get(1).content();
        assertThat(instruction)
            .as("the source sent the literal \"CA\" for every user on earth (inventory D5) and "
                + "fell back to the literal \"CAD\" (finding F1)")
            .contains("in USD and they file in US.")
            .doesNotContain("CAD");
        assertThat(instruction).contains("2026-08-31").contains("2026-09-06");
    }

    @Test
    @DisplayName("a weekly note is SENSITIVE, like every other coach payload")
    void theCallIsSensitive() {
        service.generateFor(audience("UTC"), RUN_AT);

        ArgumentCaptor<AiSensitivity> captor = ArgumentCaptor.forClass(AiSensitivity.class);
        verify(handle).chat(any(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(AiSensitivity.SENSITIVE);
    }

    @Test
    @DisplayName("the note is stored as ciphertext, never as plaintext")
    void theNoteIsEncrypted() {
        service.generateFor(audience("UTC"), RUN_AT);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(insights).insertIfAbsent(any(), eq(LocalDate.of(2026, 8, 31)), stored.capture());
        assertThat(stored.getValue())
            .startsWith(FieldEncryptionService.CURRENT_VERSION + ":")
            .doesNotContain("A short note.");
        assertThat(encryption.decrypt(stored.getValue())).isEqualTo("A short note.");
    }

    @Test
    @DisplayName("a member with AI off is skipped without a call")
    void aiOffIsSkipped() {
        when(handle.isEnabled()).thenReturn(false);

        assertThat(service.generateFor(audience("UTC"), RUN_AT)).isFalse();

        verify(handle, never()).chat(any(), any());
        verify(insights, never()).insertIfAbsent(any(), any(), anyString());
    }

    @Test
    @DisplayName("an empty answer writes nothing rather than an empty note")
    void anEmptyAnswerIsNotStored() {
        when(handle.chat(any(), any())).thenReturn(new AiCompletion("   ", "m", null, null));

        assertThat(service.generateFor(audience("UTC"), RUN_AT)).isFalse();

        verify(insights, never()).insertIfAbsent(any(), any(), anyString());
    }

    @Test
    @DisplayName("a member who already has a note for that week is reported as unwritten")
    void aDuplicateIsNotAWrite() {
        when(insights.insertIfAbsent(any(), any(), anyString())).thenReturn(0);

        assertThat(service.generateFor(audience("UTC"), RUN_AT))
            .as("ON CONFLICT DO NOTHING returned 0, so the caller counts it as skipped")
            .isFalse();
    }

    @Test
    @DisplayName("the note is control-character-stripped and capped like a chat answer")
    void theNoteIsSanitised() {
        when(handle.chat(any(), any()))
            .thenReturn(new AiCompletion("z".repeat(500), "m", null, null));

        service.generateFor(audience("UTC"), RUN_AT);

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(insights).insertIfAbsent(any(), any(), stored.capture());
        assertThat(encryption.decrypt(stored.getValue()))
            .as("the cap is 100 characters in this test's properties")
            .hasSize(100);
    }

    private static CoachAudience audience(String zone) {
        return new CoachAudience(UUID.randomUUID(), UUID.randomUUID(), "Sam", CurrencyCode.CAD,
            Jurisdiction.CA, ZoneId.of(zone));
    }
}
