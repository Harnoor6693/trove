package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.categorisation.BatchCategoriser;
import finance.trove.categorisation.CategorisationResult;
import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.events.TransactionsIngestedEvent;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.TestBankSource;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Wire one, end to end: an ingested batch really gets a category, and the AI round-trip really
 * happens outside the write transaction (collapse step 16; §6 D4).
 *
 * <h2>How each case is made to fail if {@code CategorisationListener} is removed</h2>
 * Simply asserting "an ingested row has a category" would prove nothing: {@code
 * IngestionBatchWriter} already sets one at INSERT from the free offline tiers, and that would
 * still pass with this step reverted. So every case here creates the household rule that resolves
 * the merchant <strong>after</strong> the rows are already in the ledger, and then publishes the
 * ingestion event. The insert pass cannot have used a rule that did not exist when it ran, so the
 * only thing in the tree that can move {@code category_id} to the rule's category is the listener
 * this step adds. With the listener gone every one of these rows stays {@code Uncategorised} and
 * the assertions fail.
 *
 * <p>The event is published through the real {@code ApplicationEventPublisher} inside a real
 * transaction, which is what {@code NotificationDedupIT} does for the same reason: it is a REAL
 * published event reaching the real {@code AFTER_COMMIT} listener, and the alternative — calling
 * the listener method by hand — would prove the method body and not the wiring.
 *
 * <h2>The one case that is not driven by a published event, and why it still guards something</h2>
 * {@link #theRoundTripHappensOutsideTheWriteTransaction()} invokes the listener directly, because
 * the property it is about — that nothing is held while {@code BatchCategoriser} runs — is only
 * legible from a known-clean starting state, and inside an {@code AFTER_COMMIT} callback the
 * committing transaction's synchronization is still bound to the thread. What makes that case a
 * regression guard rather than a shape-shaped comment is that the object it calls is wrapped in the
 * container's own {@link org.springframework.transaction.interceptor.TransactionInterceptor}: a
 * {@code @Transactional} added to the listener method would be honoured by that proxy and the case
 * would go red. {@link #theProxyUnderThisTestReallyOpensTransactions()} is its positive control and
 * {@link #theSplitIsWhatSpringItselfResolves()} asserts the same split structurally, against the
 * resolver the running container uses.
 */
class CategorisationSeamIT extends LedgerSupport {

    private static final String GROCERIES_SLUG = "food.grocery";
    private static final String RESTAURANTS_SLUG = "food.restaurants";
    private static final String UNCATEGORISED_SLUG = "system.uncategorised";

    @Autowired
    private TestBankSource source;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionQueryService queries;

    @Autowired
    private TransactionCategoriser categoriser;

    @Autowired
    private BatchCategoriser batchCategoriser;

    @Autowired
    private CategorisationWriteBack writeBack;

    @Autowired
    private EntityManager entityManager;

    /** The very interceptor Spring wraps every {@code @Transactional} bean in. */
    @Autowired
    private TransactionInterceptor transactionInterceptor;

    /** The very resolver that interceptor asks whether a method wants a transaction. */
    @Autowired
    private TransactionAttributeSource transactionAttributeSource;

    private Session member;
    private String accountId;
    private String groceries;
    private String restaurants;
    private String uncategorised;

    @BeforeEach
    void setUp() {
        source.reset();
        member = inviteAndRegister(loginBootstrapAdmin(), "cat-seam");
        accountId = createSyncableAccount(member, "Seam bank " + UUID.randomUUID());
        groceries = systemCategoryId(member, GROCERIES_SLUG);
        restaurants = systemCategoryId(member, RESTAURANTS_SLUG);
        uncategorised = systemCategoryId(member, UNCATEGORISED_SLUG);
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    // ------------------------------------------------------------------ fixtures

    private static RawTransaction row(String providerId, String merchant, String amount,
                                      LocalDate date) {
        return RawTransaction.of(providerId, "external-1", new BigDecimal(amount), "CAD",
            "Seam row", merchant, date, Instant.now(), TransactionType.DEBIT, false);
    }

    /** Syncs {@code rows} through the real endpoint and returns the ids that were inserted. */
    private List<UUID> ingest(List<RawTransaction> rows) {
        source.returning(rows);
        HttpResponse<String> synced = postJson(appUrl("/v1/accounts/" + accountId + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);
        List<UUID> ids = new ArrayList<>();
        for (RawTransaction raw : rows) {
            ids.add(jdbcTemplate.queryForObject(
                "SELECT id FROM transaction WHERE account_id = ? AND provider_transaction_id = ?",
                UUID.class, UUID.fromString(accountId), raw.providerTransactionId()));
        }
        return ids;
    }

    /** Creates the household rule that resolves {@code merchant}, through the real endpoint. */
    private void ruleFor(String merchant, String categoryId) {
        HttpResponse<String> created = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", merchant, "matchType", "EXACT", "categoryId", categoryId,
                "confidence", "0.95"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    }

    /** Publishes the ingestion event the way {@code IngestionBatchWriter} does, after a commit. */
    private void publishIngested(List<UUID> newIds) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            events.publishEvent(new TransactionsIngestedEvent(member.householdId(),
                member.userId(), UUID.fromString(accountId), newIds, List.of())));
    }

    private UUID storedCategory(UUID transactionId) {
        return jdbcTemplate.queryForObject("SELECT category_id FROM transaction WHERE id = ?",
            UUID.class, transactionId);
    }

    // ------------------------------------------------------------------ the wire itself

    @Test
    @DisplayName("every ingested row gets category_id from the listener, not just the insert pass")
    void theListenerWritesTheCategoryForEveryRow() {
        String merchant = "SEAM GROCER " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("seam-a", merchant, "40.00", LocalDate.of(2026, 9, 14)),
            row("seam-b", merchant, "60.00", LocalDate.of(2026, 9, 15)),
            row("seam-c", merchant, "12.50", LocalDate.of(2026, 9, 16))));

        assertThat(ids).allSatisfy(id -> assertThat(storedCategory(id))
            .as("no rule existed when the rows were inserted, so the offline pass could only "
                + "reach the Uncategorised fallback")
            .isEqualTo(UUID.fromString(uncategorised)));

        ruleFor(merchant, groceries);
        publishIngested(ids);

        assertThat(ids).allSatisfy(id -> assertThat(storedCategory(id))
            .as("this is the column §6 D4 says nothing wrote on the ingest path; the listener "
                + "this step adds is the only thing that can have moved it")
            .isEqualTo(UUID.fromString(groceries)));
    }

    @Test
    @DisplayName("a pinned row is left exactly as the member set it")
    void aPinnedRowIsNotReclassified() {
        String merchant = "SEAM PINNED " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(row("seam-pin", merchant, "25.00",
            LocalDate.of(2026, 9, 17))));
        UUID pinned = ids.get(0);

        HttpResponse<String> patched = patchJson(appUrl("/v1/transactions/" + pinned),
            Json.object("categoryId", restaurants),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);

        ruleFor(merchant, groceries);
        publishIngested(ids);

        assertThat(storedCategory(pinned))
            .as("a re-sync must never silently reclassify a row somebody has already corrected — "
                + "the whole purpose of category_override")
            .isEqualTo(UUID.fromString(restaurants));
    }

    @Test
    @DisplayName("only the rows the event names are touched")
    void anUnnamedRowIsLeftAlone() {
        String merchant = "SEAM SCOPED " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("seam-named", merchant, "30.00", LocalDate.of(2026, 9, 18)),
            row("seam-unnamed", merchant, "31.00", LocalDate.of(2026, 9, 19))));

        ruleFor(merchant, groceries);
        publishIngested(List.of(ids.get(0)));

        assertThat(storedCategory(ids.get(0))).isEqualTo(UUID.fromString(groceries));
        assertThat(storedCategory(ids.get(1)))
            .as("a listener takes its ids from its event and from nowhere else")
            .isEqualTo(UUID.fromString(uncategorised));
    }

    // ------------------------------------------------------------------ the transactional shape

    /**
     * The regression this guards is the one §2 calls "the single most common way this exact wiring
     * is written wrong": somebody tidies a {@code @Transactional} onto the listener method, and
     * every AI round-trip in the batch then pins a pooled connection.
     *
     * <p>So the listener is <strong>not</strong> called as a plain object here — a method call on a
     * hand-built instance never passes through an AOP proxy, so it would read {@code false}
     * whether the annotation were there or not, which is a test shaped like a guard and empty
     * inside. It is called through a proxy carrying the container's own {@link
     * TransactionInterceptor}, resolving against the container's own {@link
     * TransactionAttributeSource}: exactly the advice Spring applies to the {@code @Component}. Add
     * {@code @Transactional} to {@code onTransactionsIngested} and the proxy opens a transaction
     * around the whole batch, {@code transactionWasHeld} reads true, and this goes red.
     *
     * <p>{@link #theProxyUnderThisTestReallyOpensTransactions()} is the positive control that keeps
     * that meaningful: it proves the same proxy wiring DOES open one when the annotation is
     * present, so a {@code false} here is the annotation's absence and not a dead proxy.
     *
     * <p>Both halves of the regression were run, and this is what each does. A bare {@code
     * @Transactional} never reaches a test at all: Spring refuses to start the context with
     * "@TransactionalEventListener method must not be annotated with @Transactional unless when
     * declared as REQUIRES_NEW or NOT_SUPPORTED". The variant Spring <em>accepts</em> — {@code
     * @Transactional(propagation = REQUIRES_NEW)}, which is exactly what "tidying" this listener
     * to match every other one in the step would produce, and which really would hold a
     * transaction across the whole batch — is the one this case catches: with it applied, this
     * case fails on "Expecting value to be false but was true", and {@link
     * #theSplitIsWhatSpringItselfResolves()} fails on "expected: null but was:
     * PROPAGATION_REQUIRES_NEW".
     */
    @Test
    @DisplayName("the categoriser is called with NO transaction held, through the production "
        + "transaction advice, and the write still lands")
    void theRoundTripHappensOutsideTheWriteTransaction() throws Exception {
        String merchant = "SEAM SHAPE " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(row("seam-shape", merchant, "18.00",
            LocalDate.of(2026, 9, 20))));
        ruleFor(merchant, groceries);

        AtomicBoolean transactionWasHeld = new AtomicBoolean(true);
        AtomicBoolean synchronizationWasActive = new AtomicBoolean(true);
        BatchCategoriser probe = spy(batchCategoriser);
        doAnswer(invocation -> {
            transactionWasHeld.set(TransactionSynchronizationManager.isActualTransactionActive());
            synchronizationWasActive.set(TransactionSynchronizationManager
                .isSynchronizationActive());
            return invocation.callRealMethod();
        }).when(probe).categorise(any(), any(), anyList());

        // The real query service, the real pin check and the real REQUIRES_NEW write-back bean;
        // only the categoriser is wrapped, so what runs is the production listener's own logic —
        // under the production transaction advice, which is the half that makes this a guard.
        CategorisationListener advised = adviseAsSpringWould(
            new CategorisationListener(queries, categoriser, probe, writeBack));
        advised.onTransactionsIngested(new TransactionsIngestedEvent(member.householdId(),
            member.userId(), UUID.fromString(accountId), ids, List.of()));

        assertThat(transactionWasHeld.get())
            .as("step 17's hazard: BatchCategoriser opens its own short transactions and an AI "
                + "round-trip can take seconds, so a caller holding one would pin a pooled "
                + "connection for the whole batch — §6 D3's defect in a new place")
            .isFalse();
        assertThat(synchronizationWasActive.get())
            .as("and no synchronization either, so nothing it does can be deferred to a commit "
                + "that is not coming")
            .isFalse();
        assertThat(storedCategory(ids.get(0)))
            .as("and the write-back's own REQUIRES_NEW transaction still committed the column")
            .isEqualTo(UUID.fromString(groceries));
    }

    @Test
    @DisplayName("Spring's own resolver says: no transaction for the listener, REQUIRES_NEW for "
        + "the write-back")
    void theSplitIsWhatSpringItselfResolves() throws Exception {
        Method listener = CategorisationListener.class.getMethod("onTransactionsIngested",
            TransactionsIngestedEvent.class);
        assertThat(transactionAttributeSource.getTransactionAttribute(listener,
            CategorisationListener.class))
            .as("this is the object that decides, at runtime, whether the proxy starts a "
                + "transaction before the batch of model round-trips; null means it does not")
            .isNull();

        Method apply = CategorisationWriteBack.class.getMethod("apply",
            TransactionsIngestedEvent.class, Map.class);
        TransactionAttribute writeBackAttribute =
            transactionAttributeSource.getTransactionAttribute(apply, CategorisationWriteBack.class);
        assertThat(writeBackAttribute)
            .as("and the other half of the split really is transactional, or the column would be "
                + "written outside one")
            .isNotNull();
        assertThat(writeBackAttribute.getPropagationBehavior())
            .as("REQUIRES_NEW, because after the commit there is no transaction left to join")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("positive control: the proxy wiring used above does open a transaction when the "
        + "annotation is there")
    void theProxyUnderThisTestReallyOpensTransactions() {
        TransactionalProbe target = new TransactionalProbe();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(transactionInterceptor);
        ((TransactionalProbe) factory.getProxy()).run();

        assertThat(target.transactionWasHeld)
            .as("without this, theRoundTripHappensOutsideTheWriteTransaction could pass because "
                + "the advice never fired rather than because the listener carries none")
            .isTrue();
    }

    /** Wraps {@code listener} in the container's own transaction advice, as the container does. */
    private CategorisationListener adviseAsSpringWould(CategorisationListener listener) {
        ProxyFactory factory = new ProxyFactory(listener);
        factory.setProxyTargetClass(true);
        factory.addAdvice(transactionInterceptor);
        return (CategorisationListener) factory.getProxy();
    }

    /** A method that DOES want a transaction, to prove the advice above is live. */
    public static class TransactionalProbe {

        private boolean transactionWasHeld;

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void run() {
            this.transactionWasHeld = TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    @Test
    @DisplayName("the write-back publishes one TransactionCategorisedEvent per answered row, "
        + "changed or not")
    void oneEventPerRow() {
        String merchant = "SEAM EVENTS " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(
            row("seam-e1", merchant, "11.00", LocalDate.of(2026, 9, 21)),
            row("seam-e2", merchant, "12.00", LocalDate.of(2026, 9, 22))));

        List<TransactionCategorisedEvent> published = new ArrayList<>();
        CategorisationWriteBack recording = new CategorisationWriteBack(entityManager,
            event -> {
                if (event instanceof TransactionCategorisedEvent categorised) {
                    published.add(categorised);
                }
            });

        // One row is answered with the category it already carries, the other with a new one.
        Map<UUID, CategorisationResult> answers = new HashMap<>();
        answers.put(ids.get(0), new CategorisationResult(UUID.fromString(uncategorised),
            "Uncategorised", new BigDecimal("0.100"), null));
        answers.put(ids.get(1), new CategorisationResult(UUID.fromString(groceries), "Groceries",
            new BigDecimal("0.950"), null));

        int changed = new TransactionTemplate(transactionManager).execute(status ->
            recording.apply(new TransactionsIngestedEvent(member.householdId(), member.userId(),
                UUID.fromString(accountId), ids, List.of()), answers));

        assertThat(changed).as("only the second row's column actually moved").isEqualTo(1);
        assertThat(published).hasSize(2);
        assertThat(published).extracting(TransactionCategorisedEvent::transactionId)
            .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(published).allSatisfy(event -> {
            assertThat(event.householdId()).isEqualTo(member.householdId());
            assertThat(event.month()).isEqualTo(java.time.YearMonth.of(2026, 9));
        });
        assertThat(published)
            .filteredOn(event -> event.transactionId().equals(ids.get(1)))
            .singleElement()
            .satisfies(event -> {
                assertThat(event.previousCategoryId())
                    .as("the event carries what the row LEFT, because after the write it is gone")
                    .isEqualTo(UUID.fromString(uncategorised));
                assertThat(event.categoryId()).isEqualTo(UUID.fromString(groceries));
            });
        assertThat(storedCategory(ids.get(1))).isEqualTo(UUID.fromString(groceries));
    }

    @Test
    @DisplayName("the write-back never reaches another household's row")
    void theWriteBackIsHouseholdScoped() {
        String merchant = "SEAM TENANT " + UUID.randomUUID();
        List<UUID> ids = ingest(List.of(row("seam-tenant", merchant, "9.00",
            LocalDate.of(2026, 9, 23))));
        ruleFor(merchant, groceries);

        Map<UUID, CategorisationResult> answers = Map.of(ids.get(0),
            new CategorisationResult(UUID.fromString(groceries), "Groceries",
                new BigDecimal("0.950"), null));

        int changed = new TransactionTemplate(transactionManager).execute(status ->
            writeBack.apply(new TransactionsIngestedEvent(UUID.randomUUID(), member.userId(),
                UUID.fromString(accountId), ids, List.of()), answers));

        assertThat(changed)
            .as("the ids are real, but they belong to a household the event does not name; the "
                + "scope is a predicate in the query and not a comment")
            .isZero();
        assertThat(storedCategory(ids.get(0))).isEqualTo(UUID.fromString(uncategorised));
    }

    // ------------------------------------------------------------------ the ordinary path

    @Test
    @DisplayName("a plain sync, with the rule already in place, categorises through the real "
        + "chain")
    void anOrdinarySyncStillCategorises() {
        String merchant = "SEAM ORDINARY " + UUID.randomUUID();
        ruleFor(merchant, groceries);

        List<UUID> ids = ingest(List.of(row("seam-ord", merchant, "77.00",
            LocalDate.of(2026, 9, 24))));

        assertThat(storedCategory(ids.get(0))).isEqualTo(UUID.fromString(groceries));
        JsonNode read = getTransaction(member, ids.get(0).toString());
        assertThat(read.path("categoryId").asString()).isEqualTo(groceries);
    }
}
