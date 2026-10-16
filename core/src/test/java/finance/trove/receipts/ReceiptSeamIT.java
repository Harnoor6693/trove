package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Wire five: a receipt finishing its pipeline becomes an inbox row for the uploader and a queued
 * webhook delivery for their subscriptions (collapse step 18; ⚙ 28, specified by collapse step 16
 * and deferred to a step that had both {@code finance.trove.receipts} and
 * {@code finance.trove.notifications}).
 *
 * <p><strong>This class exists to catch one specific silent no-op.</strong>
 * {@code ReceiptEventPublisher.publishAfterCommit} branches on
 * {@code TransactionSynchronizationManager.isSynchronizationActive()}: with no synchronization
 * active it publishes IMMEDIATELY, and that is the ordinary case, because
 * {@code ReceiptPipelineOrchestrator} calls it outside any transaction after its final save has
 * already committed. A plain {@code @TransactionalEventListener(AFTER_COMMIT)} never fires for
 * such an event — Spring drops it with no log and no error — so both handlers carry
 * {@code fallbackExecution = true}. Every case below starts at a REAL upload through the real
 * route — which on an install with no vision model publishes from
 * {@code ReceiptUploadService.upload}, outside any transaction, taking exactly that branch — so
 * removing the flag from either listener makes these assertions fail rather than pass for the
 * wrong reason.
 *
 * <p>The webhook target is an unroutable {@code 2d00::/8} literal, exactly as {@code WebhookSeamIT}
 * uses: the listener SCHEDULES and nothing dials out.
 */
class ReceiptSeamIT extends BudgetTestSupport {

    /** A public IPv6 literal the outbound policy accepts and nothing ever reaches. */
    private static final String TARGET = "https://[2d00::1]/trove-receipt-hook";

    private static final String SECRET = "a-long-enough-webhook-secret";

    @Autowired
    private ReceiptPipelineOrchestrator pipeline;

    @Autowired
    private ReceiptEventPublisher publisher;

    private Session member;

    @BeforeEach
    void setUp() {
        member = freshHousehold("receipt-seam");
    }

    private UUID subscribe(List<String> eventTypes) {
        HttpResponse<String> created = postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", TARGET, "secret", SECRET, "eventTypes", eventTypes),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(Json.data(created.body()).path("id").asString());
    }

    /**
     * Uploads a real image through the real route.
     *
     * <p>That upload IS the seam under test. On an install with no vision model — which is what
     * the IT suite is — {@code ReceiptUploadService.upload} takes the AI-off path: it stores the
     * image, marks the receipt {@code reviewRequired} and calls
     * {@code ReceiptEventPublisher.publishAfterCommit} itself, outside any transaction. That is
     * the branch the whole class exists to cover, because it is the one a plain
     * {@code @TransactionalEventListener(AFTER_COMMIT)} silently drops. Nothing is stubbed and no
     * event is hand-published here.
     */
    private UUID processReceipt() {
        HttpResponse<String> uploaded = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(600, 400))),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(uploaded.statusCode()).as("%s", uploaded.body()).isEqualTo(202);
        return UUID.fromString(Json.data(uploaded.body()).path("id").asString());
    }

    private List<Map<String, Object>> inboxRowsFor(UUID receiptId) {
        return jdbcTemplate.queryForList(
            "SELECT type, title, action_url FROM in_app_notification "
                + "WHERE user_id = ? AND type = 'RECEIPT_PROCESSED' AND action_url = ?",
            member.userId(), "/receipts/" + receiptId);
    }

    private List<Map<String, Object>> deliveries(UUID subscriptionId) {
        return jdbcTemplate.queryForList(
            "SELECT event_type, payload, status, attempt_count FROM webhook_delivery_log "
                + "WHERE subscription_id = ? ORDER BY created_at", subscriptionId);
    }

    @Test
    @DisplayName("A receipt that needs review reaches BOTH the inbox and the webhook queue")
    void aReviewRequiredOutcomeReachesBothConsumers() {
        UUID subscription = subscribe(List.of("receipt.processed"));

        UUID receiptId = processReceipt();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT review_required FROM receipt WHERE id = ?", Boolean.class, receiptId))
            .as("with no vision model configured the pipeline ends REVIEW_REQUIRED, which is the "
                + "ordinary outcome this suite exercises")
            .isTrue();
        assertThat(inboxRowsFor(receiptId))
            .as("the uploader is told their photograph needs them — and if fallbackExecution were "
                + "dropped from NotificationEventListener.onReceiptProcessed this list is empty")
            .hasSize(1);
        assertThat(deliveries(subscription))
            .as("and if it were dropped from WebhookEventListener.onReceiptProcessed this one is")
            .singleElement()
            .satisfies(row -> {
                assertThat(row.get("event_type")).isEqualTo("receipt.processed");
                assertThat(row.get("status"))
                    .as("the listener schedules; the retry job with its delivery-time URL "
                        + "re-validation and pinned resolver is the only thing that dials out")
                    .isEqualTo("PENDING");
                assertThat(row.get("attempt_count")).isEqualTo(0);
            });
    }

    @Test
    @DisplayName("The webhook payload is ids and enum names, never the extracted merchant")
    void thePayloadCarriesNoContent() {
        UUID subscription = subscribe(List.of("receipt.processed"));

        UUID receiptId = processReceipt();

        assertThat(deliveries(subscription)).singleElement().satisfies(row -> {
            String payload = (String) row.get("payload");
            assertThat(payload)
                .contains("\"event\":\"receipt.processed\"")
                .contains("\"receiptId\":\"" + receiptId + "\"")
                .contains("\"householdId\":\"" + member.householdId() + "\"")
                .contains("\"outcome\":\"REVIEW_REQUIRED\"");
            assertThat(payload)
                .as("a delivery is an outbound request to an address a member typed in, and this "
                    + "dataset is photographs of somebody's shopping")
                .doesNotContain("ocr")
                .doesNotContain("receipts/" + member.householdId());
        });
    }

    @Test
    @DisplayName("A receipt that processed cleanly reaches the webhook and NOT the inbox")
    void aCleanOutcomeIsDeliveredButNotNotified() {
        UUID subscription = subscribe(List.of());
        UUID receiptId = processReceipt();
        // The IT suite runs with no vision model, so the pipeline cannot REACH a COMPLETE outcome
        // end to end — every real upload here ends REVIEW_REQUIRED, which is the ordinary outcome
        // and the one the test above covers. The COMPLETE case is driven through the REAL
        // publisher instead, which is the seam under test: publishAfterCommit with no transaction
        // synchronization active takes exactly the branch a plain AFTER_COMMIT listener drops.
        jdbcTemplate.update("DELETE FROM in_app_notification WHERE user_id = ?", member.userId());
        jdbcTemplate.update("DELETE FROM notification_log WHERE user_id = ?", member.userId());
        jdbcTemplate.update("DELETE FROM webhook_delivery_log WHERE subscription_id = ?",
            subscription);

        publisher.publishAfterCommit(member.householdId(), member.userId(), receiptId,
            ReceiptProcessedEvent.Outcome.COMPLETE, UUID.randomUUID());

        assertThat(inboxRowsFor(receiptId))
            .as("step 11's standing rule: a silent success earns no inbox row. An inbox that says "
                + "'nothing happened' after every upload is one people stop opening")
            .isEmpty();
        assertThat(deliveries(subscription))
            .as("the webhook is not the inbox: a subscriber that named receipt.processed asked "
                + "for the fact, and outcome is in the payload for it to switch on")
            .isNotEmpty();
        assertThat(deliveries(subscription).get(0).get("payload").toString())
            .contains("\"outcome\":\"COMPLETE\"");
    }

    @Test
    @DisplayName("Only the uploader is told, not the whole household")
    void onlyTheUploaderIsTold() {
        Session mate = inviteAndRegister(member, "receipt-seam-mate");

        UUID receiptId = processReceipt();

        assertThat(inboxRowsFor(receiptId)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM in_app_notification WHERE user_id = ? "
                + "AND type = 'RECEIPT_PROCESSED'", Long.class, mate.userId()))
            .as("a receipt is a shared ledger row, but 'the photograph YOU took could not be "
                + "read' is one person's problem: fanning it out puts N copies of it in a "
                + "household with one camera")
            .isZero();
    }

    @Test
    @DisplayName("A second run over the same receipt does not put a second row in the inbox")
    void theInboxRowIsDeduplicatedByTheReceiptId() {
        UUID receiptId = processReceipt();
        assertThat(inboxRowsFor(receiptId)).hasSize(1);

        // The orphan sweep really does re-run the pipeline over a receipt it finds stuck, and this
        // is that path: the real orchestrator, synchronously, publishing a second real event.
        pipeline.run(member.householdId(), receiptId);

        assertThat(inboxRowsFor(receiptId))
            .as("the dedup key is the receipt id ALONE: a receipt is processed once, and a re-run "
                + "of the pipeline over the same image — which the orphan sweep does — is the "
                + "same fact about the same photograph")
            .hasSize(1);
    }
}
