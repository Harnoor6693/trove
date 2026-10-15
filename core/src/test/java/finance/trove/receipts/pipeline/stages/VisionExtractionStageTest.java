package finance.trove.receipts.pipeline.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiDisabledException;
import finance.trove.ai.AiEndpointUnauthorizedException;
import finance.trove.ai.AiException;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiModelMissingException;
import finance.trove.ai.AiPolicyBlockedException;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.AiTimeoutException;
import finance.trove.ai.AiUnreachableException;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.receipts.extraction.ReceiptPromptsProperties;
import finance.trove.receipts.extraction.VisionAnswerParser;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.receipt.ReceiptImageKind;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.receipt.ReceiptStatus;
import finance.trove.receipts.upload.MultipartFileValidator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * The vision stage: the sensitivity it sends, and every failure it must absorb
 * (collapse step 14 scope item 4; {@code docs/recovery/07-READINESS-GAPS.md} row 44).
 */
class VisionExtractionStageTest {

    private static final String KEY = "receipts/h/i.jpg";

    private AiGateway gateway;
    private AiHandle handle;
    private LocalDiskStorage storage;
    private VisionExtractionStage stage;

    @BeforeEach
    void setUp() {
        gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        storage = mock(LocalDiskStorage.class);
        when(gateway.forUser(any())).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(handle.visionModel()).thenReturn("qwen2.5-vl:7b");
        when(storage.readAllBytes(KEY)).thenReturn(new byte[] {1, 2, 3});
        stage = new VisionExtractionStage(gateway, storage, new VisionAnswerParser(),
            new ReceiptPromptsProperties("Read this receipt."),
            tools.jackson.databind.json.JsonMapper.builder().build());
    }

    private static Receipt receipt() {
        Receipt receipt = new Receipt(UUID.randomUUID(), UUID.randomUUID(), ReceiptSource.UPLOAD,
            100L);
        receipt.addImage(new ReceiptImage(ReceiptImageKind.ORIGINAL, 0, KEY,
            MultipartFileValidator.IMAGE_JPEG, 3L, null));
        return receipt;
    }

    private static ReceiptProcessingContext context() {
        return new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD);
    }

    @Test
    @DisplayName("the stage is named EXTRACTING, which the status CHECK constraint requires")
    void theStageNameIsAnEnumValue() {
        // ReceiptPipelineOrchestrator does ReceiptStatus.valueOf(stage.stageName()) and the column
        // carries a CHECK over the enum's nine values, so any other name would fail every AI-path
        // receipt on the write rather than at compile time (plan section 2.6).
        assertThat(stage.stageName()).isEqualTo("EXTRACTING");
        assertThatCode(() -> ReceiptStatus.valueOf(stage.stageName())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the payload is SENSITIVE, captured from the call rather than read off the code")
    void thePayloadIsAlwaysSensitive() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("{\"merchant\":\"Shop\"}", "m", null, null));

        stage.process(receipt(), context());

        ArgumentCaptor<AiSensitivity> sensitivity = ArgumentCaptor.forClass(AiSensitivity.class);
        ArgumentCaptor<String> contentType = ArgumentCaptor.forClass(String.class);
        verify(handle).vision(anyString(), any(), contentType.capture(), sensitivity.capture());

        assertThat(sensitivity.getValue())
            .as("a receipt image is the household's ledger with a photograph attached; it is "
                + "SENSITIVE always, never conditionally")
            .isEqualTo(AiSensitivity.SENSITIVE);
        assertThat(contentType.getValue()).isEqualTo(MultipartFileValidator.IMAGE_JPEG);
    }

    @Test
    @DisplayName("an answer claiming confidence 1.0 with no corroboration still lands on review")
    void theModelCannotClearTheReviewThresholdOnItsOwnSaySo() throws Exception {
        // Everything else taken from the answer is checked against something outside it. This
        // field decides whether a human is asked at all, so it is the one field a compromised or
        // simply over-eager endpoint could use to push its own reading of somebody's ledger
        // through unseen: no merchant, no total, no date, nothing the server can re-read — and a
        // flat claim that it is certain.
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("{\"confidence\":1.0}", "m", null, null));

        Receipt receipt = receipt();
        ReceiptProcessingContext context = context();
        stage.process(receipt, context);

        assertThat(context.getExtractionConfidence())
            .as("the model's self-report is capped below the review threshold")
            .isLessThanOrEqualTo(VisionExtractionStage.MODEL_CONFIDENCE_CEILING);
        assertThat(VisionExtractionStage.MODEL_CONFIDENCE_CEILING)
            .isLessThan(Receipt.REVIEW_THRESHOLD);

        // And the consequence, end to end through the stage that actually decides: a receipt the
        // server could not corroborate is reviewed by its member.
        new ScoringStage(tools.jackson.databind.json.JsonMapper.builder().build())
            .process(receipt, context);
        assertThat(receipt.isReviewRequired())
            .as("a model does not get to decide that nobody needs to look at this")
            .isTrue();
    }

    @Test
    @DisplayName("a model that says it could not read the receipt is still believed downwards")
    void theModelCanStillLowerItsOwnScore() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("{\"merchant\":\"Shop\",\"confidence\":0.10}", "m",
                null, null));

        ReceiptProcessingContext context = context();
        stage.process(receipt(), context);

        assertThat(context.getExtractionConfidence())
            .as("the cap is a ceiling, not a floor: a low self-report is taken as given")
            .isEqualByComparingTo(new java.math.BigDecimal("0.10"));
    }

    @Test
    @DisplayName("the prompt comes from the resource, never inline")
    void thePromptComesFromTheResource() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("{}", "m", null, null));

        stage.process(receipt(), context());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(handle).vision(prompt.capture(), any(), anyString(), any());
        assertThat(prompt.getValue()).isEqualTo("Read this receipt.");
    }

    static List<Supplier<RuntimeException>> everyAiFailure() {
        return List.of(
            () -> new AiDisabledException("off"),
            () -> new AiPolicyBlockedException("tier refuses this payload"),
            () -> new AiUnreachableException("no route"),
            () -> new AiEndpointUnauthorizedException("bad key"),
            () -> new AiModelMissingException("no such model"),
            () -> new AiTimeoutException("too slow"));
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("everyAiFailure")
    @DisplayName("each of the six AiException subclasses becomes review, with nothing rethrown")
    void everyAiFailureBecomesReview(Supplier<RuntimeException> failure) {
        when(handle.vision(anyString(), any(), anyString(), any())).thenThrow(failure.get());
        Receipt receipt = receipt();
        ReceiptProcessingContext context = context();

        assertThatCode(() -> stage.process(receipt, context))
            .as("an AI failure is never rethrown from this stage: the receipt is fine, the image "
                + "is stored, and a member fills the fields in")
            .doesNotThrowAnyException();

        assertThat(receipt.isReviewRequired()).isTrue();
        assertThat(receipt.getStatus()).isNotEqualTo(ReceiptStatus.FAILED);
        assertThat(receipt.getExtractedMerchant()).isNull();
        assertThat(receipt.getExtractedAmount()).isNull();
        assertThat(receipt.getOcrText()).isNull();
    }

    @Test
    @DisplayName("AiException is caught as the base, so a seventh subclass needs no edit here")
    void theBaseClassIsWhatIsCaught() {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenThrow(new AiException("a future failure",
                org.springframework.http.HttpStatus.BAD_GATEWAY, "some-slug") { });
        Receipt receipt = receipt();

        assertThatCode(() -> stage.process(receipt, context())).doesNotThrowAnyException();
        assertThat(receipt.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("an endpoint that stopped passing the egress policy becomes review, not a failure")
    void anOutboundPolicyRefusalBecomesReview() {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenThrow(new OutboundUrlPolicyException("the saved endpoint is no longer legal"));
        Receipt receipt = receipt();

        assertThatCode(() -> stage.process(receipt, context())).doesNotThrowAnyException();
        assertThat(receipt.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("an unusable answer becomes review with BLANK fields, not a partial write")
    void anUnusableAnswerLeavesTheFieldsBlank() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("I could not read that.", "m", null, null));
        Receipt receipt = receipt();

        stage.process(receipt, context());

        assertThat(receipt.isReviewRequired()).isTrue();
        assertThat(receipt.getExtractedMerchant()).isNull();
        assertThat(receipt.getExtractedAmount()).isNull();
    }

    @Test
    @DisplayName("AI switched off between upload and run opens no connection at all")
    void aiOffOpensNoConnection() throws Exception {
        when(handle.isEnabled()).thenReturn(false);
        Receipt receipt = receipt();

        stage.process(receipt, context());

        verify(handle, never()).vision(anyString(), any(), anyString(), any());
        verify(storage, never()).readAllBytes(anyString());
        assertThat(receipt.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("a member with no vision model opens no connection either")
    void noVisionModelOpensNoConnection() throws Exception {
        when(handle.visionModel()).thenReturn(null);
        Receipt receipt = receipt();

        stage.process(receipt, context());

        verify(handle, never()).vision(anyString(), any(), anyString(), any());
        assertThat(receipt.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("the model's date is parsed with the UPLOADER's country, not as written")
    void theDateIsParsedInTheMembersOwnOrder() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any())).thenReturn(
            new AiCompletion("{\"merchant\":\"S\",\"date\":\"05/06/2026\"}", "m", null,
                null));

        Receipt canadian = receipt();
        stage.process(canadian,
            new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD));
        assertThat(canadian.getExtractedDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 5));

        Receipt american = receipt();
        stage.process(american,
            new ReceiptProcessingContext(Jurisdiction.US, null, CurrencyCode.USD));
        assertThat(american.getExtractedDate())
            .as("the model copies the date as printed because it does not know whose receipt this "
                + "is; the order that turns it into a day is the member's (inventory D2)")
            .isEqualTo(java.time.LocalDate.of(2026, 5, 6));
    }

    @Test
    @DisplayName("the model's line items are kept for the scoring stage to promote")
    void theLineItemsAreKept() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any())).thenReturn(
            new AiCompletion("""
                {"merchant":"S","lineItems":[
                  {"description":"Latte","quantity":1,"unitPrice":5.75,"totalPrice":5.75,
                   "taxable":true}]}""", "m", null, null));

        Receipt receipt = receipt();
        stage.process(receipt, context());

        assertThat(receipt.getExtractedLineItems())
            .as("without this the whole lineItems half of the answer was discarded and only the "
                + "regex parser's two-space-separated lines ever survived")
            .isNotNull()
            .contains("Latte");
        // Still only a suggestion: the scoring stage decides whether they become rows.
        assertThat(receipt.getLineItems()).isEmpty();
    }

    @Test
    @DisplayName("the currency is set only from an ISO code the RECEIPT printed")
    void theCurrencyComesFromTheReceiptOrIsLeftForTheParser() throws Exception {
        when(handle.vision(anyString(), any(), anyString(), any())).thenReturn(
            new AiCompletion("{\"merchant\":\"S\",\"currency\":\"USD\"}", "m", null, null));
        Receipt withCode = receipt();
        stage.process(withCode, context());
        assertThat(withCode.getExtractedCurrency()).isEqualTo(CurrencyCode.USD);

        when(handle.vision(anyString(), any(), anyString(), any()))
            .thenReturn(new AiCompletion("{\"merchant\":\"S\"}", "m", null, null));
        Receipt withoutCode = receipt();
        stage.process(withoutCode, context());
        assertThat(withoutCode.getExtractedCurrency())
            .as("no ISO code on the receipt leaves the currency for the parsing stage to resolve "
                + "from the member's own row (D3); it is never defaulted here")
            .isNull();
    }

    @Test
    @DisplayName("A receipt whose uploader has been purged degrades like an AI-off install")
    void aNullUploaderDoesNotThrow() {
        // V43 makes receipt.user_id nullable with ON DELETE SET NULL, because the receipt is a
        // shared household ledger row and the ledger outlives its author (owner decision 17). The
        // orphan sweep re-runs the pipeline over such a row, so this is a real state and not a
        // hypothetical: ai_settings is per USER, so there is no member whose model, key or egress
        // policy could be used, and borrowing another member's would spend their budget on
        // somebody else's photograph.
        Receipt orphaned = new Receipt(UUID.randomUUID(), null, ReceiptSource.UPLOAD, 100L);
        orphaned.addImage(new ReceiptImage(ReceiptImageKind.ORIGINAL, 0, KEY,
            MultipartFileValidator.IMAGE_JPEG, 3L, null));

        assertThatCode(() -> stage.process(orphaned, context()))
            .as("throwing here would be a 500 out of a scheduled sweep, over a row the household "
                + "still owns and still wants")
            .doesNotThrowAnyException();

        assertThat(orphaned.isReviewRequired())
            .as("exactly the outcome an install with no vision model already produces")
            .isTrue();
        verify(gateway, never()).forUser(any());
    }
}
