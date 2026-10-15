package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiUnreachableException;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.app.support.LogCapture;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.receipts.extraction.HeuristicExtractor;
import finance.trove.receipts.extraction.ReceiptPromptsProperties;
import finance.trove.receipts.extraction.VisionAnswerParser;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.pipeline.stages.ParsingStage;
import finance.trove.receipts.pipeline.stages.VisionExtractionStage;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.receipt.ReceiptImageKind;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.upload.MultipartFileValidator;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nothing about a receipt reaches a log line
 * (collapse step 14 scope item 4; the shape {@code AiSecrecyTest} and
 * {@code AiCategorisationSecrecyTest} set).
 *
 * <p>Worth its own class because the failure is silent and permanent. A merchant name is the
 * household's ledger; the extracted text is a transcription of what they bought; the prompt and the
 * answer are the payload of somebody's own model. None of them shows up as a broken feature — they
 * show up months later in a log aggregator, on a shared screen, or in a support bundle.
 *
 * <p>The level is forced to TRACE for the duration, which is the point: at the shipped
 * {@code finance.trove: INFO} several of these paths log nothing at all and the assertions would
 * pass without proving anything. Both the success path and the failure path are exercised, because
 * a well-meaning "log what went wrong" is where this tends to appear.
 */
class ReceiptSecrecyTest {

    private static final String MERCHANT = "Dr Chen Fertility Clinic";
    private static final String EXTRACTED_TEXT =
        MERCHANT + "\n2026-06-05\nConsultation  450.00\nTOTAL  508.50";
    private static final String PROMPT = "Read this receipt and do-not-log-this-prompt-7f2a";
    private static final String ANSWER =
        "{\"merchant\":\"" + MERCHANT + "\",\"amount\":508.50,\"text\":\"" + "TRANSCRIPT-4b71"
            + "\"}";
    private static final String KEY = "receipts/household/do-not-log-this-key.jpg";

    private Logger troveLogger;
    private Level previousLevel;
    private AiHandle handle;
    private LocalDiskStorage storage;
    private VisionExtractionStage vision;

    @BeforeEach
    void raiseTheLogLevel() {
        troveLogger = (Logger) LoggerFactory.getLogger("finance.trove");
        previousLevel = troveLogger.getLevel();
        troveLogger.setLevel(Level.TRACE);

        AiGateway gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        storage = mock(LocalDiskStorage.class);
        when(gateway.forUser(any())).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(handle.visionModel()).thenReturn("qwen2.5-vl:7b");
        when(storage.readAllBytes(KEY)).thenReturn(new byte[] {1, 2, 3});
        vision = new VisionExtractionStage(gateway, storage, new VisionAnswerParser(),
            new ReceiptPromptsProperties(PROMPT), JsonMapper.builder().build());
    }

    @AfterEach
    void restoreTheLogLevel() {
        troveLogger.setLevel(previousLevel);
    }

    /**
     * A receipt with a REAL id.
     *
     * <p>{@code Receipt.id} is {@code @GeneratedValue}, so outside a persistence context it is
     * null — and an assertion that the log "contains the receipt id" then degenerates into an
     * assertion that it contains nothing, which every log line satisfies. The id is set here so
     * that the operator-usefulness half of this test can actually fail.
     */
    private static Receipt receipt() {
        Receipt receipt = new Receipt(UUID.randomUUID(), UUID.randomUUID(), ReceiptSource.UPLOAD,
            100L);
        ReflectionTestUtils.setField(receipt, "id", UUID.randomUUID());
        receipt.addImage(new ReceiptImage(ReceiptImageKind.ORIGINAL, 0, KEY,
            MultipartFileValidator.IMAGE_JPEG, 3L, null));
        return receipt;
    }

    private static ReceiptProcessingContext context() {
        return new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD);
    }

    @Test
    @DisplayName("a successful vision call logs neither the prompt, the answer nor the merchant")
    void nothingSecretIsLoggedOnSuccess() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion(ANSWER, "qwen2.5-vl:7b", null, null));

        try (LogCapture logs = LogCapture.start()) {
            vision.process(receipt(), context());

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(MERCHANT)
                .doesNotContain(PROMPT)
                .doesNotContain(ANSWER)
                .doesNotContain("TRANSCRIPT-4b71")
                .doesNotContain(KEY)
                .doesNotContain("do-not-log");
        }
    }

    @Test
    @DisplayName("a failed vision call logs the slug and the receipt id, and nothing else")
    void nothingSecretIsLoggedOnFailure() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenThrow(new AiUnreachableException(
                "connect timed out to model-host.example do-not-log-this-host"));

        Receipt receipt = receipt();
        try (LogCapture logs = LogCapture.start()) {
            vision.process(receipt, context());

            String captured = String.join("\n", logs.lines());
            assertThat(captured)
                .doesNotContain(MERCHANT)
                .doesNotContain(PROMPT)
                .doesNotContain(KEY)
                .doesNotContain("model-host.example")
                .doesNotContain("do-not-log");
            assertThat(receipt.getId())
                .as("the id must be real, or the next assertion proves nothing")
                .isNotNull();
            assertThat(captured)
                .as("the slug and the receipt id are what an operator needs, and are all they get")
                .contains("ai-unreachable")
                .contains(receipt.getId().toString());
        }
    }

    @Test
    @DisplayName("the parsing stage logs neither the extracted text nor the merchant it read")
    void theParserLogsNothingAboutTheReceipt() throws Exception {
        ParsingStage parsing = new ParsingStage(new HeuristicExtractor(),
            JsonMapper.builder().build());
        Receipt receipt = receipt();
        receipt.setOcrText(EXTRACTED_TEXT);

        try (LogCapture logs = LogCapture.start()) {
            parsing.process(receipt, context());

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(MERCHANT)
                .doesNotContain(EXTRACTED_TEXT)
                .doesNotContain("Consultation");
        }
        // The field was really read; the test would otherwise pass on a stage that did nothing.
        assertThat(receipt.getExtractedMerchant()).isEqualTo(MERCHANT);
    }
}
