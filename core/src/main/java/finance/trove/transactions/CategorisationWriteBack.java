package finance.trove.transactions;

import finance.trove.categorisation.CategorisationResult;
import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.events.TransactionsIngestedEvent;
import jakarta.persistence.EntityManager;
import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short write transaction at the end of the ingest categorisation seam
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4; collapse step 16).
 *
 * <h2>Why the write is a separate bean from the listener</h2>
 * {@link CategorisationListener} may not hold a transaction while it asks
 * {@code BatchCategoriser} for answers — step 17's own javadoc spells out why: each of that
 * class's internal calls opens and closes its own short transaction, and an AI round-trip can take
 * seconds, so a caller that wrapped the batch in one transaction would pin a pooled database
 * connection for the length of every model call. That is the same defect §6 D3 records against the
 * old webhook {@code deliver()}. So the listener method carries NO {@code @Transactional} at all,
 * calls the categoriser with nothing held, and then calls this bean — a distinct Spring bean, so
 * the {@code REQUIRES_NEW} proxy is genuinely in play rather than being a self-invocation that
 * silently does nothing, which is the D4 mistake in its other form.
 *
 * <p>{@code REQUIRES_NEW} rather than a plain {@code @Transactional}: the listener runs
 * {@code AFTER_COMMIT}, where the publisher's transaction is finished and there is nothing left to
 * join, and Spring would quietly give a {@code REQUIRED} method no transaction of its own.
 *
 * <h2>The event is published from INSIDE this transaction, deliberately</h2>
 * {@link TransactionCategorisedEvent}'s consumer, {@code EnvelopeSpendListener}, is itself
 * {@code AFTER_COMMIT}. Publishing from here means the commit that makes {@code category_id}
 * durable is the same commit that releases the envelope recomputation, so the aggregate is never
 * computed from a category that a rollback then took away — and, just as important, the listener
 * is actually invoked: an {@code AFTER_COMMIT} listener for an event published with no
 * transaction active and no {@code fallbackExecution} is dropped silently.
 *
 * <h2>The rows are re-read here, household-scoped, rather than carried in</h2>
 * The entities the listener read to build its batch were loaded by a different, read-only
 * transaction and are detached by the time the answers come back. Re-reading them inside this
 * transaction is what makes {@code setCategoryId} a real UPDATE. The query names
 * {@code householdId} as well as the ids: the ids came from an event this server published, but a
 * finder that trusted them alone would be the unscoped read {@code ArchitectureTest} exists to
 * forbid, and the scope costs one indexed predicate.
 */
@Component
public class CategorisationWriteBack {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationWriteBack.class);

    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public CategorisationWriteBack(EntityManager entityManager,
                                   ApplicationEventPublisher events) {
        this.entityManager = entityManager;
        this.events = events;
    }

    /**
     * Writes {@code transaction.category_id} for every answered row and publishes one
     * {@link TransactionCategorisedEvent} per row.
     *
     * <p>An event is published for a row whose category did not change as well as for one whose
     * did. That is what makes budgets move for freshly ingested rows: {@code EnvelopeSpendListener}
     * recomputes the aggregate as a rewrite, so a "changed from X to X" event costs one recompute
     * that produces the number it already held, and the alternative — publishing only on a change
     * — would leave an ingested row that the rules already classified at insert invisible to every
     * envelope.
     *
     * @param event   the ingestion that produced these rows; its household and member are the only
     *                identities used here, and neither is ever taken from a request
     * @param answers what {@code BatchCategoriser} resolved, keyed by transaction id
     * @return how many rows this transaction actually changed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int apply(TransactionsIngestedEvent event, Map<UUID, CategorisationResult> answers) {
        if (answers.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Transaction row : reread(event.householdId(), answers.keySet())) {
            CategorisationResult answer = answers.get(row.getId());
            if (answer == null) {
                continue;
            }
            UUID previousCategoryId = row.getCategoryId();
            UUID resolved = answer.categoryId();
            if (!resolved.equals(previousCategoryId)) {
                row.setCategoryId(resolved);
                changed++;
            }
            events.publishEvent(new TransactionCategorisedEvent(event.householdId(),
                event.userId(), row.getId(), previousCategoryId, resolved,
                YearMonth.from(row.getDate()), row.getCurrency()));
        }
        LOG.debug("ingest categorisation write-back: {} of {} rows changed category in household "
            + "{}", changed, answers.size(), event.householdId());
        return changed;
    }

    /**
     * The rows, managed by this transaction and scoped to the household the event names.
     *
     * <p>An empty id collection is answered without a query: {@code in ()} is not valid SQL on
     * every dialect and a batch with nothing to write should cost nothing.
     */
    private List<Transaction> reread(UUID householdId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId and t.id in :ids",
                Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("ids", ids)
            .getResultList();
    }
}
