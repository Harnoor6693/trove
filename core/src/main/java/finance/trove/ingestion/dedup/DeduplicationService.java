package finance.trove.ingestion.dedup;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.Instants;
import finance.trove.ingestion.RawTransactionRejectedException;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Decides whether an incoming row is a transaction Trove already holds
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0; {@code docs/recovery/01-FINDINGS.md} O7, F4).
 *
 * <h2>The exact pass</h2>
 * {@code findByAccountIdAndProviderTransactionId}. That is the same query the pre-collapse service
 * ran — but against a column every normaliser filled with the PLUGIN'S NAME rather than the bank's
 * transaction id (O7/M0). With the unique index on {@code (account_id, source_id)} in place, the
 * consequence was that an account could hold at most one transaction, for ever: the second row of
 * the very first sync matched the first and was discarded as a duplicate. Renaming the field to
 * {@code providerTransactionId} and deleting {@code RawTransaction}'s unused second id slot is
 * what makes that mistake unrepeatable.
 *
 * <h2>The fuzzy pass, and its two defects</h2>
 * The old fuzzy pass matched <em>any</em> pending row on the account within ±2 days and ±$0.50.
 * Two defects, both named in {@code 06-COLLAPSE-PLAN.md} §2.2 and §6 M0 — not in
 * {@code 01-FINDINGS.md}, which names the constant-{@code sourceId} bug and the currency
 * fallbacks:
 * <ol>
 *   <li><strong>It ignored the candidate's own identity.</strong> An incoming row that carried a
 *       perfectly good provider id of its own still fell through to the window whenever the exact
 *       lookup missed, and swallowed a stored row that belonged to a different transaction. Two
 *       distinct same-day, same-amount pending charges collapsed into one and a member lost a
 *       transaction they had actually made. <em>Fixed:</em> the fuzzy pass runs ONLY when the
 *       incoming row has no provider id at all, and only against a candidate this run is not
 *       already reconciling (see {@link DedupBatch}).</li>
 *   <li><strong>It required {@code pending = true} on the candidate</strong> while a CSV row
 *       arrives already posted. So a re-imported file matched nothing, and every one of its rows
 *       was inserted a second time. <em>Fixed:</em> eligibility is decided here, where the
 *       incoming row's own state is known, rather than in the query: a posted incoming row that
 *       finds a posted candidate is the same transaction arriving twice and is
 *       {@link DeduplicationResult#SKIPPED}, not inserted again.</li>
 * </ol>
 * A source that gives every row a deterministic id — which step 7's CSV normaliser does, hashing
 * account, date, amount, normalised description and row ordinal — never reaches this pass at all,
 * which is the real fix for a re-imported file. The pass remains for sources that genuinely offer
 * no stable identifier.
 *
 * <h2>No literal fallbacks</h2>
 * Finding F4: the old code wrote {@code "CAD"} when a row had no currency and {@code DEBIT} when
 * it had no type. Both are silent corruption — a US account's transactions became Canadian
 * dollars, and an unlabelled credit became spending. Currency comes from the row or from the
 * account; a direction the source did not state is a rejected row, counted and reported, not
 * guessed.
 */
@Service
public class DeduplicationService {

    /** How far apart two postings of one transaction may be dated. */
    public static final int DATE_TOLERANCE_DAYS = 2;

    /**
     * How far an amount may move between authorisation and settlement — a restaurant tip, a
     * fuel-pump pre-authorisation. Wide enough for those and narrow enough that two ordinary
     * purchases are not confused; the residual risk of two genuinely similar small charges is why
     * the pass is now reachable only by a source with no ids at all.
     */
    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.50");

    /**
     * How many provider ids one {@code IN (…)} statement carries in {@link #exactMatchIndex}.
     *
     * <p>Not the whole file: a bind list of fifty thousand parameters is refused by the driver and
     * would defeat the query plan long before that. Five hundred is comfortably inside every
     * limit and turns the largest file this server accepts into a hundred statements.
     */
    static final int CHUNK = 500;

    private final TransactionRepository transactions;

    public DeduplicationService(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * Reconciles one incoming row against the account's stored ledger.
     *
     * @param account the account being synced; its household, currency and id scope the write
     * @param userId  the member whose action produced the run, stored as created-by
     * @param incoming the source's row
     * @param batch    what this run already knows about itself
     * @return what happened
     * @throws RawTransactionRejectedException if the row cannot be accepted at all. Thrown before
     *                                          any write, so the caller counts it and the batch
     *                                          transaction stays usable
     */
    public DeduplicationResult deduplicate(ConnectedAccount account, UUID userId,
                                           RawTransaction incoming, DedupBatch batch) {
        return reconcileOne(account, userId, incoming, batch).result();
    }

    /**
     * The same decision, with the row it applied to.
     *
     * <p>{@link #deduplicate} is the shape §6 M0 names and is what the unit tests assert against;
     * this overload exists because the caller that drives a whole batch has to publish
     * {@code TransactionsIngestedEvent} with the ids of the rows it inserted and updated, and a
     * bare enum cannot tell it which rows those were. Returning the row is strictly more
     * information than returning the verdict, so there is one implementation and no chance of the
     * two disagreeing.
     */
    public Outcome reconcileOne(ConnectedAccount account, UUID userId, RawTransaction incoming,
                                DedupBatch batch) {
        return reconcileOne(account, userId, incoming, batch, null);
    }

    /**
     * The same decision, stamping every INSERTED row with the import batch that produced it
     * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
     *
     * @param importBatchId the {@code import_batch} being committed, or {@code null} for an
     *                      ordinary bank sync. Only an insert carries it: a row this run merely
     *                      MATCHED already exists and already belongs to whatever brought it here,
     *                      and re-attributing it would let an undo of this batch remove a row an
     *                      earlier one created
     */
    public Outcome reconcileOne(ConnectedAccount account, UUID userId, RawTransaction incoming,
                                DedupBatch batch, UUID importBatchId) {
        validate(incoming);

        Optional<Transaction> match = match(account, incoming, batch, null);
        if (match.isPresent()) {
            Transaction matched = match.get();
            batch.markMatched(matched.getId());
            if (promotesToPosted(matched, incoming)) {
                matched.markPosted(incoming.postedAt() != null ? incoming.postedAt()
                    : Instants.now());
                return new Outcome(DeduplicationResult.UPDATED, matched);
            }
            return new Outcome(DeduplicationResult.SKIPPED, matched);
        }
        return new Outcome(DeduplicationResult.NEW,
            insert(account, userId, incoming, importBatchId));
    }

    /**
     * <strong>What would happen to this row, without writing anything.</strong>
     *
     * <p>{@code POST /v1/imports} has to answer "40 new, 2 already here" before a single
     * transaction is inserted, and {@link #reconcileOne} INSERTS — it is not a read-only
     * classifier. Previewing through a transaction that is then rolled back would work exactly
     * once and would consume a sequence, fire the entity listeners and leave the persistence
     * context dirty; duplicating the matching rules in a second method would be two places for the
     * exact and fuzzy passes to disagree. So the RULES live in {@link #match} and
     * {@link #promotesToPosted}, which both callers share, and this method is the read-only half.
     *
     * <p>It does mutate {@code batch}, which is the caller's own in-memory scratch for one file:
     * without that, one stored transaction could be reported as matching two different incoming
     * rows and the preview's counts would not add up to the file's row count.
     *
     * @return {@link DeduplicationResult#NEW} for a row that would be inserted,
     *         {@link DeduplicationResult#UPDATED} for a pending row that would post,
     *         {@link DeduplicationResult#SKIPPED} for one already held
     * @throws RawTransactionRejectedException if the row could not be accepted at all — the same
     *                                         refusal, with the same reason, that the commit would
     *                                         report
     */
    public DeduplicationResult classify(ConnectedAccount account, RawTransaction incoming,
                                        DedupBatch batch) {
        return classify(account, incoming, batch, null);
    }

    /**
     * The same classification, reading a pre-loaded exact-match index instead of querying per row.
     *
     * <p>Confirmed review finding 5: a preview classifies every row of a file — up to fifty
     * thousand — and the single-row exact lookup made that fifty thousand sequential round trips.
     * The index is built once, by {@link #exactMatchIndex}, and the RULES are unchanged: this
     * method and {@link #reconcileOne} still go through {@link #match}, which reads the index when
     * it is given one and runs the same query when it is not.
     *
     * @param exactMatches ids to stored rows for this account, or {@code null} to query per row
     */
    public DeduplicationResult classify(ConnectedAccount account, RawTransaction incoming,
                                        DedupBatch batch, Map<String, Transaction> exactMatches) {
        validate(incoming);
        Optional<Transaction> match = match(account, incoming, batch, exactMatches);
        if (match.isEmpty()) {
            return DeduplicationResult.NEW;
        }
        Transaction matched = match.get();
        batch.markMatched(matched.getId());
        return promotesToPosted(matched, incoming)
            ? DeduplicationResult.UPDATED
            : DeduplicationResult.SKIPPED;
    }

    /**
     * The stored row this incoming row IS, if Trove already holds it.
     *
     * <p>The exact pass when the row carries a provider id, and the fuzzy window only when it does
     * not — never both. A row with a perfectly good id of its own that simply has not been seen
     * before is NEW; letting it fall through to the window is defect 1 above, and it swallowed a
     * stored row belonging to a different transaction.
     */
    private Optional<Transaction> match(ConnectedAccount account, RawTransaction incoming,
                                        DedupBatch batch, Map<String, Transaction> exactMatches) {
        if (incoming.hasProviderId()) {
            return exactMatches != null
                ? Optional.ofNullable(exactMatches.get(incoming.providerTransactionId()))
                : transactions.findByAccountIdAndProviderTransactionId(account.getId(),
                    incoming.providerTransactionId());
        }
        return fuzzyCandidate(account, incoming, batch);
    }

    /**
     * Every stored row of {@code account} that one of {@code rows} claims to be, in one pass.
     *
     * <p>Chunked, because a query with fifty thousand bind parameters is refused by the driver
     * long before it is slow. {@link #CHUNK} ids per statement turns a fifty-thousand-row file
     * into a hundred queries rather than fifty thousand — and the caller runs them inside ONE
     * read-only transaction, so it is also one connection rather than fifty thousand acquisitions.
     *
     * <p>Rows with no provider id are not in it and must not be: they are the fuzzy pass's, and
     * the fuzzy pass is a range query that this index cannot stand in for.
     *
     * @return an id-to-row map for the exact pass, never {@code null}
     */
    public Map<String, Transaction> exactMatchIndex(ConnectedAccount account,
                                                    List<RawTransaction> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (RawTransaction row : rows) {
            if (row.hasProviderId()) {
                ids.add(row.providerTransactionId());
            }
        }
        Map<String, Transaction> index = new HashMap<>(Math.max(16, ids.size()));
        List<String> ordered = List.copyOf(ids);
        for (int from = 0; from < ordered.size(); from += CHUNK) {
            List<String> chunk = ordered.subList(from, Math.min(from + CHUNK, ordered.size()));
            for (Transaction stored : transactions
                .findAllByAccountIdAndProviderTransactionIdIn(account.getId(), chunk)) {
                index.putIfAbsent(stored.getProviderTransactionId(), stored);
            }
        }
        return index;
    }

    /**
     * Whether the one in-place change ingestion ever makes applies: a pending row that has posted.
     *
     * <p>Nothing else is overwritten. A member may have corrected the merchant, added notes or
     * pinned a category since the row was first seen, and a re-sync that reasserted the source's
     * view of those fields would quietly undo their work on every run.
     */
    private static boolean promotesToPosted(Transaction existing, RawTransaction incoming) {
        return existing.isPending() && !incoming.pending();
    }

    /**
     * What deduplication did, and to which row.
     *
     * @param result      the verdict
     * @param transaction the stored row the verdict is about — inserted for
     *                    {@link DeduplicationResult#NEW}, matched otherwise. Never {@code null}
     */
    public record Outcome(DeduplicationResult result, Transaction transaction) {
    }

    /**
     * The first stored row that could be this transaction under another posting.
     *
     * <p>The two exclusions are defect 1's fix: a candidate this run is already reconciling
     * through the exact path, and a candidate this run has already matched to some other incoming
     * row, are both somebody else's transaction.
     */
    private Optional<Transaction> fuzzyCandidate(ConnectedAccount account, RawTransaction incoming,
                                                 DedupBatch batch) {
        BigDecimal amount = incoming.amount().abs();
        List<Transaction> candidates = transactions.findFuzzyCandidates(account.getId(),
            incoming.date().minusDays(DATE_TOLERANCE_DAYS),
            incoming.date().plusDays(DATE_TOLERANCE_DAYS),
            amount.subtract(AMOUNT_TOLERANCE), amount.add(AMOUNT_TOLERANCE));

        return candidates.stream()
            .filter(candidate -> !batch.hasMatched(candidate.getId()))
            .filter(candidate -> !batch.carriesProviderId(candidate.getProviderTransactionId()))
            .findFirst();
    }

    private Transaction insert(ConnectedAccount account, UUID userId, RawTransaction incoming,
                               UUID importBatchId) {
        CurrencyCode currency = resolveCurrency(account, incoming);
        Transaction transaction = new Transaction(account.getHouseholdId(), account.getId(),
            userId, incoming.hasProviderId() ? incoming.providerTransactionId() : null,
            incoming.amount().abs(), currency, incoming.type(), incoming.description(),
            incoming.merchantName(), incoming.date(), incoming.postedAt(), incoming.pending(),
            false, importBatchId, Instants.now());
        return transactions.save(transaction);
    }

    /**
     * The row's currency, or the account's — never a literal (F4).
     *
     * <p>A code this install does not support is a rejected row rather than a silently
     * reinterpreted one: storing a GBP amount as CAD would be wrong by a factor nobody could
     * detect later, because the number itself would look perfectly plausible.
     */
    private static CurrencyCode resolveCurrency(ConnectedAccount account, RawTransaction incoming) {
        if (incoming.currency() == null || incoming.currency().isBlank()) {
            return account.getCurrency();
        }
        try {
            return CurrencyCode.valueOf(incoming.currency().trim().toUpperCase(
                java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RawTransactionRejectedException(
                "unsupported currency '" + incoming.currency() + "'");
        }
    }

    /** Everything a row must have before it is worth looking for a match for it. */
    private static void validate(RawTransaction incoming) {
        if (incoming.date() == null) {
            throw new RawTransactionRejectedException("the source reported no date");
        }
        if (incoming.amount() == null) {
            throw new RawTransactionRejectedException("the source reported no amount");
        }
        if (!Money.fitsColumn(incoming.amount())) {
            // B1-04: before anything rounds it. CurrencyCode.normalise on 1E9999999 is seconds
            // of CPU, and the column could not hold the result anyway.
            throw new RawTransactionRejectedException("the amount is too large, or has more than "
                + Money.MAX_FRACTION_DIGITS + " decimal places; this server stores at most "
                + Money.MAX_INTEGER_DIGITS + " digits before the decimal point");
        }
        if (incoming.type() == null) {
            throw new RawTransactionRejectedException("the source reported no transaction type; "
                + "a direction is never assumed (finding F4)");
        }
        if (incoming.type() == TransactionType.TRANSFER && incoming.amount().signum() == 0) {
            throw new RawTransactionRejectedException("a zero-value transfer is not a movement");
        }
    }
}
