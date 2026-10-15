package finance.trove.receipts;

import finance.trove.common.events.ReceiptProcessedEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes {@link ReceiptProcessedEvent} <strong>after the commit of the terminal state</strong>
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3; collapse step 14 scope item 6).
 *
 * <p>After the commit rather than inside the transaction, and that is not a detail. A listener
 * that reacts to this event — collapse step 11's notification consumer, collapse step 16's webhook
 * consumer — reads the receipt back to build what it sends. Publishing before the commit means the
 * listener either reads a row nobody else can see yet or, on a rollback, sends a notification about
 * a receipt that does not exist. The status is COMPLETE or FAILED by the time anybody hears about
 * it.
 *
 * <p><strong>This step writes the publisher and no listener.</strong> The consumers are step 11's
 * and step 16's (§6 D3, §7 row 16), and step 16 may be running in another worktree right now, so a
 * listener here would be a duplicate delivery nobody could see until merge.
 *
 * <h2>What a consumer needs to know</h2>
 * There are exactly two call sites, both of which have already committed the terminal status when
 * the event reaches a listener:
 * <ul>
 *   <li>{@code ReceiptUploadService#upload} — the AI-off path, where the receipt goes straight to
 *       {@code COMPLETE} with {@code reviewRequired = true} and the outcome is
 *       {@link ReceiptProcessedEvent.Outcome#REVIEW_REQUIRED}. This is the <em>ordinary</em>
 *       outcome on an install with no vision model, exactly as the event record's own javadoc
 *       says, and a consumer that words it as a failure is wrong.</li>
 *   <li>{@code ReceiptPipelineOrchestrator#run} — the AI path, at the end of the pipeline, with
 *       {@code COMPLETE}, {@code REVIEW_REQUIRED} or {@code FAILED} according to the final row.</li>
 * </ul>
 * The event is published at most once per receipt per processing run, and
 * {@code matchedTransactionId} is whatever the row carries at that moment — null on the AI-off
 * path, since nothing has matched anything.
 */
@Component
public class ReceiptEventPublisher {

    private final ApplicationEventPublisher events;

    public ReceiptEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Registers the publish for after the current transaction commits, or publishes immediately
     * when there is no transaction to wait for.
     *
     * <p>The no-transaction branch matters for the pipeline, whose stages each commit separately:
     * the orchestrator calls this outside a transaction of its own, after the final save has
     * already committed, so "immediately" is still after the commit.
     */
    public void publishAfterCommit(UUID householdId, UUID userId, UUID receiptId,
                                   ReceiptProcessedEvent.Outcome outcome,
                                   UUID matchedTransactionId) {
        ReceiptProcessedEvent event =
            new ReceiptProcessedEvent(householdId, userId, receiptId, outcome,
                matchedTransactionId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                events.publishEvent(event);
            }
        });
    }
}
