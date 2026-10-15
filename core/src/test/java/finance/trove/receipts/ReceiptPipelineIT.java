package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.AiSensitivity;
import finance.trove.ai.ReceiptVisionStub;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.receipts.extraction.HeuristicExtractor;
import finance.trove.receipts.extraction.ReceiptPromptsProperties;
import finance.trove.receipts.extraction.VisionAnswerParser;
import finance.trove.receipts.matching.ReceiptMatcher;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import finance.trove.receipts.pipeline.stages.MatchingStage;
import finance.trove.receipts.pipeline.stages.ParsingStage;
import finance.trove.receipts.pipeline.stages.ScoringStage;
import finance.trove.receipts.pipeline.stages.TaxSplitStage;
import finance.trove.receipts.pipeline.stages.ValidationStage;
import finance.trove.receipts.pipeline.stages.VisionExtractionStage;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptLinkService;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.receipt.ReceiptStatus;
import finance.trove.receipts.taxsplit.TaxSplitService;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.users.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole stage chain against a stub vision endpoint — §9 risk 12 names this class.
 *
 * <p>Vision OCR is the unproven part of this step: nothing in the tree had ever sent an image to a
 * model and read the answer back. What is real here is everything except the model itself — the
 * production {@code OpenAiCompatibleClient} against a real HTTP server, real Flyway-migrated
 * Postgres, real {@code LocalDiskStorage}, the real six stages in their real order, and the real
 * link service writing both halves.
 *
 * <p><strong>Why the gateway is assembled here rather than autowired.</strong> The stub binds to
 * {@code 127.0.0.1} and {@code OutboundUrlPolicy} refuses loopback unless both keys of the two-key
 * opt-in are set; the install-wide half is false for the whole integration suite and a class cannot
 * flip it without forking a second Spring context, which is banned. {@code AiCategorisationIT}
 * (step 17) and {@code CoachStubGateway} (step 13) established the answer and
 * {@link ReceiptVisionStub} is step 14's version of it.
 */
class ReceiptPipelineIT extends AuthFlowSupport {

    @Autowired
    private ReceiptRepository receipts;

    @Autowired
    private UserRepository users;

    @Autowired
    private ReceiptEventPublisher publisher;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private LocalDiskStorage storage;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionQueryService transactions;

    @Autowired
    private ReceiptLinkService links;

    private ReceiptVisionStub stub;
    private Session member;

    @BeforeEach
    void signIn() {
        stub = ReceiptVisionStub.start();
        member = inviteAndRegister(loginBootstrapAdmin(), "receipt-pipeline");
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    /** The production orchestrator, with only the gateway replaced. */
    private ReceiptPipelineOrchestrator orchestrator() {
        return new ReceiptPipelineOrchestrator(receipts, users, publisher, meters,
            new ValidationStage(storage),
            new VisionExtractionStage(stub, storage, new VisionAnswerParser(),
                new ReceiptPromptsProperties("Read this receipt."), objectMapper),
            new ParsingStage(new HeuristicExtractor(), objectMapper),
            new MatchingStage(new ReceiptMatcher(transactions, links)),
            new TaxSplitStage(new TaxSplitService()),
            new ScoringStage(objectMapper));
    }

    private UUID upload() {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(1_400, 1_000))),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);
        return UUID.fromString(Json.data(response.body()).path("id").asString());
    }

    @Test
    @DisplayName("the full chain reads a receipt, matches a transaction and scores it")
    void theFullChainRuns() {
        // A one-of-a-kind amount and a date no other test uses. Household A is the whole suite's
        // bootstrap household and several other classes seed a 12.34 debit into it, so a
        // round-number fixture makes the matcher's candidate set ambiguous and this test asserts
        // on whichever of them happened to be closest — which is a flake, not a finding.
        String amount = "873.41";
        LocalDate date = LocalDate.of(2024, 3, 7);
        UUID transactionId = createManualTransaction(amount, date);
        UUID receiptId = upload();

        stub.answering("""
            {"merchant":"Blue Bottle Coffee","amount":873.41,"date":"2024-03-07",
             "text":"BLUE BOTTLE COFFEE\\n2024-03-07\\nLatte  436.70\\nCroissant  436.71\\nTOTAL  873.41",
             "confidence":0.98,
             "lineItems":[{"description":"Latte","quantity":1,"unitPrice":436.70,
                           "totalPrice":436.70,"taxable":true},
                          {"description":"Croissant","quantity":1,"unitPrice":436.71,
                           "totalPrice":436.71,"taxable":true}]}""");

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ReceiptStatus.COMPLETE);
        assertThat(finished.getExtractedMerchant()).isEqualTo("Blue Bottle Coffee");
        assertThat(finished.getExtractedAmount()).isEqualByComparingTo(new BigDecimal(amount));
        assertThat(finished.getExtractedDate()).isEqualTo(date);
        assertThat(finished.getOcrText()).contains("BLUE BOTTLE COFFEE");
        assertThat(finished.getProcessingStartedAt()).isNotNull();
        assertThat(finished.getProcessingCompletedAt()).isNotNull();

        // The member registered as CA/Toronto, so the split runs; the province is null on their
        // row, which yields zeros rather than another province's figures.
        assertThat(finished.getGstAmount()).isNotNull();

        assertThat(finished.getMatchedTransactionId()).isEqualTo(transactionId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT receipt_id FROM transaction WHERE id = ?", UUID.class, transactionId))
            .as("the matching stage links through ReceiptLinkService, which writes both halves")
            .isEqualTo(receiptId);

        assertThat(finished.getConfidence()).isNotNull();
        assertThat(finished.isReviewRequired()).isFalse();
        assertThat(finished.getLineItems()).hasSize(2);
    }

    @Test
    @DisplayName("the image is sent as SENSITIVE, and as the JPEG that is really on disk")
    void theImageIsSentAsSensitive() {
        UUID receiptId = upload();
        stub.answering("{\"merchant\":\"Shop\",\"amount\":1.00}");

        orchestrator().run(member.householdId(), receiptId);

        assertThat(stub.callCount()).isOne();
        assertThat(stub.lastSensitivity())
            .as("a receipt image is the household's ledger with a photograph attached")
            .isEqualTo(AiSensitivity.SENSITIVE);
        assertThat(stub.lastContentType()).isEqualTo("image/jpeg");
        // The client base64s the bytes into a data: URI content part, which is what actually
        // reaches an OpenAI-compatible vision endpoint.
        assertThat(stub.lastRequestBody()).contains("data:image/jpeg;base64,");
    }

    @Test
    @DisplayName("a member on CLOUD_OK has the receipt held back, and it degrades to review")
    void aCloudOnlyTierDegradesToReview() {
        UUID receiptId = upload();
        stub.cloudOnly().answering("{\"merchant\":\"Shop\"}");

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        // The member's own privacy setting held their receipt back, which is what they asked it
        // to do. It is not a 403, not a FAILED row, and ai-policy-blocked stays unrouted.
        assertThat(finished.getStatus()).isEqualTo(ReceiptStatus.COMPLETE);
        assertThat(finished.isReviewRequired()).isTrue();
        assertThat(finished.getExtractedMerchant()).isNull();
        assertThat(stub.requestCount())
            .as("the refusal happens before any connection is opened")
            .isZero();
    }

    @Test
    @DisplayName("an endpoint that answers 500 degrades to review, not to a FAILED receipt")
    void anEndpointFailureDegradesToReview() {
        UUID receiptId = upload();
        stub.failingWith(500);

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ReceiptStatus.COMPLETE);
        assertThat(finished.isReviewRequired()).isTrue();
        assertThat(finished.getExtractedMerchant()).isNull();
    }

    @Test
    @DisplayName("prose from the model degrades to review with blank fields")
    void proseDegradesToReview() {
        UUID receiptId = upload();
        stub.answering("I'm afraid I can't read that receipt.");

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        assertThat(finished.isReviewRequired()).isTrue();
        assertThat(finished.getExtractedMerchant()).isNull();
        assertThat(finished.getExtractedAmount()).isNull();
    }

    @Test
    @DisplayName("D1: an unmatched receipt with a clean extraction still clears review")
    void anUnmatchedCleanReceiptClearsReview() {
        UUID receiptId = upload();
        // A date and an amount no transaction of this household has, so nothing matches.
        // The transcription corroborates every field, which is what the score is now made of: the
        // model's own "confidence": 1.0 is capped below the review threshold on purpose (a model
        // does not get to decide that nobody needs to look at this), so a receipt clears review on
        // what the server could re-read for itself — merchant, total, date and line items.
        stub.answering("""
            {"merchant":"Corner Store","amount":999.99,"date":"2019-01-02",
             "text":"CORNER STORE\\n2019-01-02\\nSpanner  500.00\\nBucket  499.99\\nTOTAL  999.99",
             "confidence":1.0}""");

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        assertThat(finished.getMatchedTransactionId()).isNull();
        assertThat(finished.getConfidence())
            .as("D1: with the source's fixed denominator an unmatched receipt had a ceiling of "
                + "0.80 against a 0.92 threshold and could never clear it")
            .isGreaterThanOrEqualTo(Receipt.REVIEW_THRESHOLD);
        assertThat(finished.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("the status walks forward and the trove.receipt.stage timer records every stage")
    void everyStageIsTimed() {
        UUID receiptId = upload();
        stub.answering("{\"merchant\":\"Shop\",\"amount\":1.00}");

        orchestrator().run(member.householdId(), receiptId);

        for (String stage : List.of("VALIDATING", "EXTRACTING", "PARSING", "MATCHING",
            "TAX_SPLIT", "SCORING")) {
            assertThat(meters.find(ReceiptPipelineOrchestrator.STAGE_TIMER)
                .tag("stage", stage).timer())
                .as("§5 item 9 names trove.receipt.stage, tagged stage=%s", stage)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("a receipt deleted mid-run is not re-inserted by the next stage's save")
    void aDeletedReceiptIsNotResurrected() {
        UUID receiptId = upload();
        stub.answering("{\"merchant\":\"Shop\"}");

        // Deleting before the run starts is the same code path the mid-run re-read protects:
        // the source held one detached entity for the whole run and its save() would merge a
        // deleted row straight back into the table, orphaning the files with it.
        jdbcTemplate.update("DELETE FROM receipt_image WHERE receipt_id = ?", receiptId);
        jdbcTemplate.update("DELETE FROM receipt WHERE id = ?", receiptId);

        orchestrator().run(member.householdId(), receiptId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt WHERE id = ?", Integer.class, receiptId)).isZero();
    }

    @Test
    @DisplayName("a receipt whose stored file has gone FAILS rather than scoring an empty one")
    void aMissingFileFailsTheReceipt() {
        UUID receiptId = upload();
        for (String key : jdbcTemplate.queryForList(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ?", String.class, receiptId)) {
            storage.delete(key);
        }

        orchestrator().run(member.householdId(), receiptId);

        Receipt finished = receipts.findByIdAndHouseholdId(receiptId, member.householdId())
            .orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(ReceiptStatus.FAILED);
        assertThat(finished.getErrorMessage()).isNotBlank();
        // One sentence, and never a key or a path: a member cannot address the store and an error
        // body that names a key tells an attacker about one.
        assertThat(finished.getErrorMessage()).doesNotContain("receipts/");
        assertThat(stub.requestCount()).isZero();
    }

    @Test
    @DisplayName("running the pipeline twice replaces the line items rather than doubling them")
    void aSecondRunReplacesTheLineItems() {
        UUID receiptId = upload();
        // The transcription has to corroborate the reading for the receipt to clear review and
        // promote its lines at all: the model's own "confidence": 1.0 is capped below the
        // threshold, deliberately. The lines PROMOTED are still the model's structured ones — the
        // vision stage writes them first and the parsing stage does not overwrite them — which is
        // what makes "one row, not two" the thing this test measures.
        stub.answering("""
            {"merchant":"Shop","amount":10.00,"date":"2026-06-05","confidence":1.0,
             "text":"SHOP\\n2026-06-05\\nThing  5.00\\nOther  5.00\\nTOTAL  10.00",
             "lineItems":[{"description":"Thing","totalPrice":10.00}]}""");

        orchestrator().run(member.householdId(), receiptId);
        orchestrator().run(member.householdId(), receiptId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM line_item WHERE receipt_id = ?", Integer.class, receiptId))
            .isOne();
    }

    private UUID createManualTransaction(String amount, LocalDate date) {
        HttpResponse<String> account = postJson(appUrl("/v1/accounts"),
            Json.object("displayName", "Wallet " + UUID.randomUUID(), "sourceKind", "MANUAL",
                "accountType", "OTHER", "currency", "CAD"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(account.statusCode())
            .as("creating a manual account failed: %s", account.body())
            .isEqualTo(201);

        HttpResponse<String> transaction = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", Json.data(account.body()).path("id").asString(),
                "amount", amount, "type", "DEBIT", "date", date.toString(),
                "merchant", "Blue Bottle Coffee"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(transaction.statusCode())
            .as("creating a manual transaction failed: %s", transaction.body())
            .isEqualTo(201);
        return UUID.fromString(Json.data(transaction.body()).path("id").asString());
    }
}
