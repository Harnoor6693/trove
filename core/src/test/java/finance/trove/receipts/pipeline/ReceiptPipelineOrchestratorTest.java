package finance.trove.receipts.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.receipts.ReceiptEventPublisher;
import finance.trove.receipts.pipeline.stages.MatchingStage;
import finance.trove.receipts.pipeline.stages.ParsingStage;
import finance.trove.receipts.pipeline.stages.ScoringStage;
import finance.trove.receipts.pipeline.stages.TaxSplitStage;
import finance.trove.receipts.pipeline.stages.ValidationStage;
import finance.trove.receipts.pipeline.stages.VisionExtractionStage;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.receipt.ReceiptStatus;
import finance.trove.users.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The orchestrator's failure path, which has to work when the entity is what failed.
 *
 * <p>The scenario is the one that wedges a receipt for ever: a stage puts a value on the receipt
 * that the column will not hold — a total wider than {@code NUMERIC(19,4)}, read out of a vision
 * model's own transcription — and the {@code save} that follows the stage throws. The failure path
 * catches that, sets {@code FAILED} and saves the same entity again, with the same value still on
 * it, so the second save throws too; the exception escapes {@code runStage}, {@code run} and the
 * {@code @Async} method where nobody is waiting for it, the row keeps whatever non-terminal status
 * it last committed, and {@code ReceiptOrphanSweep} re-enqueues it every hour for ever, spending
 * one of its twenty slots per pass on a receipt that can never finish.
 *
 * <p>The fix is that the fallback writes the terminal columns with a targeted {@code UPDATE} that
 * carries none of the entity's other values. This test fails without it: the {@code run} call
 * throws instead of returning.
 */
class ReceiptPipelineOrchestratorTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID UPLOADER = UUID.randomUUID();

    /** Wider than {@code NUMERIC(19,4)}, which tops out just under 10^15. */
    private static final BigDecimal UNSTORABLE = new BigDecimal("12345678901234567.89");

    private ReceiptRepository receipts;
    private Receipt receipt;
    private ParsingStage parsing;
    private ReceiptPipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        receipts = mock(ReceiptRepository.class);
        UserRepository users = mock(UserRepository.class);
        ReceiptEventPublisher publisher = mock(ReceiptEventPublisher.class);

        receipt = new Receipt(HOUSEHOLD, UPLOADER, ReceiptSource.UPLOAD, 100L);
        when(users.findAccount(any())).thenReturn(Optional.empty());
        when(receipts.findByIdAndHouseholdId(any(), eq(HOUSEHOLD)))
            .thenReturn(Optional.of(receipt));
        // The database refuses the entity precisely while the poisoned value is on it — which is
        // what a NUMERIC(19,4) column does, and what makes saving the same entity again useless.
        when(receipts.save(any())).thenAnswer(invocation -> {
            Receipt saved = invocation.getArgument(0);
            if (saved.getExtractedAmount() != null
                && saved.getExtractedAmount().compareTo(new BigDecimal("1000000000000000")) > 0) {
                throw new DataIntegrityViolationException(
                    "numeric field overflow: extracted_amount");
            }
            return saved;
        });

        ValidationStage validation = stage(mock(ValidationStage.class), "VALIDATING");
        VisionExtractionStage vision = stage(mock(VisionExtractionStage.class), "EXTRACTING");
        parsing = stage(mock(ParsingStage.class), "PARSING");
        MatchingStage matching = stage(mock(MatchingStage.class), "MATCHING");
        TaxSplitStage taxSplit = stage(mock(TaxSplitStage.class), "TAX_SPLIT");
        ScoringStage scoring = stage(mock(ScoringStage.class), "SCORING");

        orchestrator = new ReceiptPipelineOrchestrator(receipts, users, publisher,
            new SimpleMeterRegistry(), validation, vision, parsing, matching, taxSplit, scoring);
    }

    private static <T extends PipelineStage> T stage(T mocked, String name) {
        when(mocked.stageName()).thenReturn(name);
        return mocked;
    }

    @Test
    @DisplayName("a receipt whose own value the database refuses still reaches a terminal status")
    void aPoisonedReceiptStillReachesFailed() throws Exception {
        // The stage writes a total no column can hold. Both extractors bound what they produce now
        // (ExtractionLimits), so this is the "should not happen" branch — and the "should not
        // happen" branch is exactly the one that used to loop for ever.
        org.mockito.Mockito.doAnswer(invocation -> {
            Receipt current = invocation.getArgument(0);
            current.setExtractedAmount(UNSTORABLE);
            return null;
        }).when(parsing).process(any(), any());

        assertThatCode(() -> orchestrator.run(HOUSEHOLD, UUID.randomUUID()))
            .as("the async pipeline must not throw out of its own failure path")
            .doesNotThrowAnyException();

        verify(receipts).markFailed(any(), eq(HOUSEHOLD), anyString(), any(Instant.class));
        assertThat(receipt.getStatus())
            .as("the in-memory entity is marked too; the UPDATE is what makes it stick")
            .isEqualTo(ReceiptStatus.FAILED);
    }

    @Test
    @DisplayName("an ordinary run never reaches for the targeted UPDATE")
    void theFallbackIsOnlyForTheFailurePath() {
        orchestrator.run(HOUSEHOLD, UUID.randomUUID());

        assertThat(receipt.getStatus()).isEqualTo(ReceiptStatus.COMPLETE);
        verify(receipts, never()).markFailed(any(), any(), anyString(), any(Instant.class));
    }
}
