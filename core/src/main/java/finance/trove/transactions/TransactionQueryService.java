package finance.trove.transactions;

import finance.trove.common.domain.PagedResult;
import finance.trove.common.plugin.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single read seam over the household's ledger
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4, §6 D1/D2; gap P2-15).
 *
 * <p>Collapse steps 8 (budgets), 10 (subscriptions), 14 (receipts), 15 (tax export) and 18
 * (data export) all read transactions, and every one of them needs the same three things: the
 * household filter, the transfer-leg exclusion, and a date window computed in somebody's own
 * timezone. Putting the queries here rather than letting each step write its own is what keeps
 * those three from drifting apart — a step that forgot the transfer exclusion would double-count
 * a credit-card payment as both spending and income, and the mistake is invisible until somebody
 * reconciles a statement by hand.
 *
 * <h2>Why native SQL, and why that is not a hole</h2>
 * The free-text {@code q} has to reach the {@code pg_trgm} GIN index, and it can only do that if
 * the expression in the {@code WHERE} clause is byte-for-byte the expression the index was built
 * over (V19). JPQL cannot express it. So the statement is assembled here — but assembled out of
 * <strong>constant fragments</strong>: every caller-supplied value is a bound parameter, and the
 * only caller-influenced piece of syntax is the {@code ORDER BY}, which comes from
 * {@link TransactionSort}, an enum whose clauses are compile-time constants. There is no string
 * from the query string anywhere in the SQL.
 */
@Service
public class TransactionQueryService {

    /**
     * The searchable text, exactly as V19's GIN index defines it.
     *
     * <p>It must stay a character-for-character match with the index expression: change the
     * coalesce, the separator or the column order here and Postgres silently stops using the
     * index and starts sequentially scanning the whole ledger on every search.
     */
    private static final String SEARCH_EXPRESSION =
        "(coalesce(t.merchant_name, '') || ' ' || coalesce(t.description, '') || ' ' "
            + "|| coalesce(t.notes, ''))";

    private final EntityManager entityManager;
    private final TransactionRepository transactions;

    public TransactionQueryService(EntityManager entityManager,
                                   TransactionRepository transactions) {
        this.entityManager = entityManager;
        this.transactions = transactions;
    }

    /** One transaction of this household, or empty — the caller answers 404, never 403. */
    @Transactional(readOnly = true)
    public Optional<Transaction> findOne(UUID householdId, UUID transactionId) {
        return transactions.findByIdAndHouseholdId(transactionId, householdId);
    }

    /**
     * One page of the household's ledger.
     *
     * <p>Two statements, not one: the page and its total. A window function would fetch both in a
     * single round trip, but it computes the count over the whole matching set for every row of
     * the page, which on a ledger of any size is slower than counting once.
     */
    @Transactional(readOnly = true)
    public PagedResult<Transaction> search(UUID householdId, TransactionQuery query) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = whereClause(householdId, query, parameters);

        Number total = (Number) bind(
            entityManager.createNativeQuery("SELECT count(*) FROM transaction t " + where),
            parameters).getSingleResult();
        if (total.longValue() == 0) {
            return PagedResult.empty(query.page(), query.limit());
        }

        @SuppressWarnings("unchecked")
        List<Transaction> rows = bind(entityManager.createNativeQuery(
            "SELECT t.* FROM transaction t " + where + " ORDER BY " + query.sort().orderByClause()
                + " LIMIT :pageLimit OFFSET :pageOffset", Transaction.class), parameters)
            .setParameter("pageLimit", query.limit())
            .setParameter("pageOffset", query.offset())
            .getResultList();

        return new PagedResult<>(rows, total.longValue(), query.page(), query.limit());
    }

    /**
     * Every transaction of this household whose {@code date} falls in the inclusive window, with
     * transfer legs excluded.
     *
     * <p>This is what the dashboard aggregates over, and what steps 8 and 15 will. The exclusion
     * is not optional and there is no flag to turn it off: gap P2-14 exists because moving money
     * between two accounts the household already owns is neither spending nor income, and every
     * aggregate in the product has to agree about that or two screens will disagree.
     */
    @Transactional(readOnly = true)
    public List<Transaction> findSpendableInRange(UUID householdId, LocalDate from, LocalDate to) {
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId "
                    + "and t.date >= :fromDate and t.date <= :toDate "
                    + "and t.transferGroupId is null "
                    + "order by t.date asc", Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("fromDate", from)
            .setParameter("toDate", to)
            .getResultList();
    }

    /**
     * Every transaction of this household in the inclusive window, transfer legs and pending
     * rows included. Only the full data export uses it: a user leaving with their data needs the
     * whole ledger, not the spending view (B2-03).
     */
    @Transactional(readOnly = true)
    public List<Transaction> findAllInRange(UUID householdId, LocalDate from, LocalDate to) {
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId "
                    + "and t.date >= :fromDate and t.date <= :toDate "
                    + "order by t.date asc, t.createdAt asc", Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("fromDate", from)
            .setParameter("toDate", to)
            .getResultList();
    }

    /**
     * The household's rows with these ids — the read collapse step 8's ingest listener makes
     * (§6 D1).
     *
     * <p>{@code TransactionsIngestedEvent} carries ids and nothing else, and the envelope spend
     * writer needs the {@code (category_id, date)} of each one to know which aggregates a sync
     * moved. It lives here rather than in {@code finance.trove.budgets} for the reason the class
     * javadoc gives: every package that reads the ledger reads it through this seam, so the
     * household filter is applied in one place. The filter is not a formality here either — the
     * ids arrive in an event, and an event is a message, not a capability.
     *
     * <p>Transfer legs are deliberately <em>not</em> excluded. The caller only needs to know which
     * envelopes to recompute; the recomputation itself then reads {@link #findSpendableInRange},
     * which does exclude them. Filtering twice would let a leg paired after ingestion skip the
     * very recompute that takes it back out of an envelope.
     *
     * <p>An empty {@code ids} answers an empty list without touching the database.
     */
    @Transactional(readOnly = true)
    public List<Transaction> findAllByIds(UUID householdId, Collection<UUID> ids) {
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

    /**
     * The household's debits since a date, transfer legs excluded — the read
     * {@code SubscriptionDetectionService} is specified to make (§6 D2). It exists here rather
     * than in step 10 so that step 10 inherits the transfer exclusion instead of re-deriving it.
     */
    @Transactional(readOnly = true)
    public List<Transaction> debitsSince(UUID householdId, LocalDate since) {
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId "
                    + "and t.type = :debit and t.date >= :since and t.transferGroupId is null "
                    + "order by t.date asc", Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("debit", TransactionType.DEBIT)
            .setParameter("since", since)
            .getResultList();
    }

    /**
     * Candidate transactions for a receipt: the household's debits within an amount and a date
     * window (collapse step 14, §2.6, §6 "Receipts").
     *
     * <p>Added here rather than in {@code finance.trove.receipts} for the reason the class javadoc
     * gives — every package that reads the ledger reads it through this seam, so the household
     * filter is applied in one place. It replaces an HTTP call the pre-collapse receipt processor
     * made to another service with an {@code X-User-Id} header, which is the identity defect the
     * whole collapse removes.
     *
     * <p>Only {@code DEBIT} rows are candidates: a receipt is evidence of money going out, and a
     * refund or an interest credit that happened to be for a similar amount on a similar day is a
     * match nobody wants. Transfer legs are excluded for the same reason every other aggregate
     * excludes them (gap P2-14) — money moved between the household's own accounts bought nothing.
     *
     * <p>The window is the caller's, and stated by {@code ReceiptMatcher}: this method holds no
     * tolerance of its own, because a default here would be a second opinion about how close is
     * close enough.
     *
     * @param householdId      the ledger to search — never a user id
     * @param amount           the receipt total
     * @param amountTolerance  how far either side of {@code amount} to look; must not be negative
     * @param date             the receipt date
     * @param dateToleranceDays how many days either side to look; must not be negative
     * @return matching rows, newest first, never null
     */
    @Transactional(readOnly = true)
    public List<Transaction> findCandidates(UUID householdId, java.math.BigDecimal amount,
                                            java.math.BigDecimal amountTolerance, LocalDate date,
                                            int dateToleranceDays) {
        if (amount == null || date == null) {
            return List.of();
        }
        java.math.BigDecimal tolerance =
            amountTolerance == null ? java.math.BigDecimal.ZERO : amountTolerance.abs();
        int days = Math.abs(dateToleranceDays);
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId "
                    + "and t.type = :debit and t.transferGroupId is null "
                    + "and t.date >= :fromDate and t.date <= :toDate "
                    + "and t.amount >= :minAmount and t.amount <= :maxAmount "
                    + "order by t.date desc", Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("debit", TransactionType.DEBIT)
            .setParameter("fromDate", date.minusDays(days))
            .setParameter("toDate", date.plusDays(days))
            .setParameter("minAmount", amount.subtract(tolerance))
            .setParameter("maxAmount", amount.add(tolerance))
            .getResultList();
    }

    /**
     * The same row as {@link #findOne}, read with {@code SELECT ... FOR UPDATE} (collapse step 14).
     *
     * <p>It exists for exactly one caller — {@code ReceiptLinkService}, the only writer of either
     * half of the receipt/transaction link — and for exactly one reason. Writing both halves
     * inside one transaction makes them commit together; it does not make two concurrent writers
     * take turns. At {@code READ COMMITTED} two household mates attaching two different receipts
     * to the same transaction both read {@code receipt_id} as null, neither clears the other's
     * half, and the row ends up naming one receipt while both receipts name it. There is no
     * {@code UNIQUE} constraint on {@code transaction.receipt_id} to catch that (a partial unique
     * index cannot be deferred, and the link service legitimately holds two rows in a
     * half-swapped state mid-flush), so the row lock is what serialises them: the second writer
     * blocks until the first commits and then sees the receipt it has to detach.
     *
     * <p>Every caller that <em>writes</em> a transaction's {@code receipt_id} must read it through
     * this method rather than {@link #findOne}; a plain read is fine for anything that only
     * displays the value.
     *
     * @param householdId   the caller's household — never a user id
     * @param transactionId the row to lock
     * @return the locked row, or empty when it is not this household's
     */
    @Transactional
    public Optional<Transaction> findOneForUpdate(UUID householdId, UUID transactionId) {
        return entityManager.createQuery(
                "select t from Transaction t where t.householdId = :householdId and t.id = :id",
                Transaction.class)
            .setParameter("householdId", householdId)
            .setParameter("id", transactionId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .setMaxResults(1)
            .getResultList().stream()
            .findFirst();
    }

    /**
     * Persists a transaction whose {@code receiptId} has just been written (collapse step 14).
     *
     * <p>It exists because {@code ReceiptLinkService} must write both halves of the
     * receipt/transaction link inside one transaction, and the receipts package has no repository
     * over {@code transaction} — nor should it. The name says what it is for so that it does not
     * become a general-purpose save: the only field any caller of this method changes is
     * {@code receipt_id}.
     */
    @Transactional
    public Transaction saveLinked(Transaction transaction) {
        return transactions.save(transaction);
    }

    /**
     * Builds the shared {@code WHERE} out of constant fragments, collecting the bound values.
     *
     * <p>{@code household_id} is first and unconditional, which is why an {@code accountId} or
     * {@code categoryId} belonging to somebody else simply matches nothing instead of needing its
     * own ownership check: the tenancy predicate has already excluded every row it could name.
     */
    static String whereClause(UUID householdId, TransactionQuery query,
                              Map<String, Object> parameters) {
        List<String> predicates = new ArrayList<>();
        predicates.add("t.household_id = :householdId");
        parameters.put("householdId", householdId);

        if (query.from() != null) {
            predicates.add("t.date >= :fromDate");
            parameters.put("fromDate", query.from());
        }
        if (query.to() != null) {
            predicates.add("t.date <= :toDate");
            parameters.put("toDate", query.to());
        }
        if (query.type() != null) {
            predicates.add("t.type = :type");
            parameters.put("type", query.type().name());
        }
        if (query.accountId() != null) {
            predicates.add("t.account_id = :accountId");
            parameters.put("accountId", query.accountId());
        }
        if (query.categoryId() != null) {
            predicates.add("t.category_id = :categoryId");
            parameters.put("categoryId", query.categoryId());
        }
        if (query.hasSearch()) {
            // ILIKE with leading and trailing wildcards is what the trigram index accelerates;
            // the pattern is a bound parameter, so a caller's % or _ is data, not syntax.
            predicates.add(SEARCH_EXPRESSION + " ILIKE :searchPattern");
            parameters.put("searchPattern", "%" + escapeLike(query.q()) + "%");
        }
        return "WHERE " + String.join(" AND ", predicates);
    }

    private static Query bind(Query query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
        return query;
    }

    /**
     * Neutralises the caller's own wildcards.
     *
     * <p>Without it, {@code q=%} matches every transaction the household has and {@code q=_} every
     * one whose searchable text is a single character: the search would answer a question nobody
     * asked, and on a large ledger it would do so slowly. Backslash is Postgres' default
     * {@code LIKE} escape character, so it is escaped first or it would escape the escapes.
     */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
