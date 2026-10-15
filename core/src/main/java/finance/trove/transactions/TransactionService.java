package finance.trove.transactions;

import finance.trove.accounts.AccountService;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserClock;
import finance.trove.transactions.dto.ManualTransactionRequest;
import finance.trove.transactions.dto.TransactionPatch;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual entry, enrichment and deletion of the household's transactions
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4, §6 D4; gaps P2-13 and P2-14).
 *
 * <p>Reads live in {@link TransactionQueryService}; this class is the write side. Every method
 * takes {@code householdId} first and reaches rows only through scoped repository queries, so a
 * row of another household is 404 rather than 403 (§9 risk 2).
 */
@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final CategoryOverrideRepository overrides;
    private final CategoryRepository categories;
    private final AccountService accounts;
    private final TransactionCategoriser categoriser;
    private final ApplicationEventPublisher events;
    private final UserClock clock;

    public TransactionService(TransactionRepository transactions,
                              CategoryOverrideRepository overrides, CategoryRepository categories,
                              AccountService accounts, TransactionCategoriser categoriser,
                              ApplicationEventPublisher events, UserClock clock) {
        this.transactions = transactions;
        this.overrides = overrides;
        this.categories = categories;
        this.accounts = accounts;
        this.categoriser = categoriser;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Records a transaction by hand (gap P2-13).
     *
     * <p><strong>MANUAL accounts only.</strong> A hand-entered row on a synced account is
     * indistinguishable from a fetched one to everything downstream, so the next sync would find
     * a transaction the source has never heard of and the deduplicator would eventually reconcile
     * it away — silently losing money the member typed in. 409 rather than a 400 because the
     * request is well-formed and the account is real; it is the account's state that refuses.
     *
     * <p>The row is categorised on insert by the same pipeline that categorises an ingested one,
     * unless {@code categoryId} was given, which pins it: a member who chose a category at entry
     * time has already made the decision the pipeline would be guessing at.
     */
    @Transactional
    public Transaction createManual(UUID householdId, UUID userId,
                                    ManualTransactionRequest request) {
        ConnectedAccount account = accounts.get(householdId, request.accountId());
        if (!account.acceptsManualEntry()) {
            throw new ConflictException("Transactions can only be entered by hand on a MANUAL "
                + "account; " + account.getDisplayName() + " is a " + account.getSourceKind()
                + " account whose transactions come from its source.");
        }

        CurrencyCode currency = account.getCurrency();
        Transaction transaction = new Transaction(householdId, account.getId(), userId, null,
            request.amount(), currency, request.typeOrDefault(), null,
            trimToNull(request.merchant()), request.date(), clock.now(), false, true, clock.now());
        transaction.setNotes(trimToNull(request.notes()));

        UUID categoryId;
        if (request.categoryId() != null) {
            categoryId = requireVisibleCategory(householdId, request.categoryId());
        } else {
            // The six-argument overload, so a hand-entered row can reach the member's OWN AI
            // tier: the five-argument one passes no owner and therefore never consults it
            // (collapse step 16, closing step 17's follow-up). The principal is the owner here —
            // the member is present and it is their settings, their endpoint and their budget.
            categoryId = categoriser.categoriseOnInsert(householdId, userId,
                transaction.getMerchantName(), null, request.amount(), currency.name());
        }
        transaction.setCategoryId(categoryId);

        Transaction saved = transactions.save(transaction);
        if (request.categoryId() != null) {
            pin(householdId, userId, saved.getId(), categoryId);
        }
        publishCategorised(householdId, userId, saved, null, categoryId);
        return saved;
    }

    /**
     * Applies a partial update.
     *
     * <p>Only the fields the body actually carried are written — see {@link TransactionPatch} for
     * why presence has to be modelled explicitly rather than inferred from a null.
     *
     * <p>A {@code categoryId} writes the column <em>and</em> upserts the pin (§6 D4). Both, not
     * one: the column is what a budget aggregate reads, and the pin is what survives the next
     * re-sync. Doing only the second was the named defect, and it would have meant a member's
     * correction never moved envelope spend at all.
     */
    @Transactional
    public Transaction patch(UUID householdId, UUID userId, UUID transactionId,
                             TransactionPatch patch) {
        Transaction transaction = require(householdId, transactionId);
        UUID previousCategoryId = transaction.getCategoryId();
        boolean categoryChanged = false;

        if (patch.hasMerchantName()) {
            transaction.setMerchantName(patch.merchantName());
        }
        if (patch.hasNotes()) {
            transaction.setNotes(patch.notes());
        }
        if (patch.hasTags()) {
            transaction.setTags(patch.tags());
        }
        if (patch.hasTransferGroupId()) {
            transaction.setTransferGroupId(patch.transferGroupId());
        }
        if (patch.hasCategoryId()) {
            UUID categoryId = patch.categoryId() == null
                ? null
                : requireVisibleCategory(householdId, patch.categoryId());
            categoryChanged = !java.util.Objects.equals(previousCategoryId, categoryId);
            transaction.setCategoryId(categoryId);
            if (categoryId == null) {
                overrides.findByHouseholdIdAndTransactionId(householdId, transactionId)
                    .ifPresent(pin -> overrides.deleteByIdAndHouseholdId(pin.getId(), householdId));
            } else {
                pin(householdId, userId, transactionId, categoryId);
            }
        }

        Transaction saved;
        try {
            saved = transactions.save(transaction);
        } catch (DataIntegrityViolationException e) {
            // merchant_category_map.category_id is ON DELETE RESTRICT and category_override's
            // category_id has no cascade either, so a category that vanished between the
            // visibility check and the write surfaces here rather than as a 500.
            throw new ConflictException(
                "The transaction could not be updated because a referenced row changed. "
                    + "Reload and try again.", e);
        }
        if (categoryChanged) {
            publishCategorised(householdId, userId, saved, previousCategoryId,
                saved.getCategoryId());
        }
        return saved;
    }

    /**
     * Deletes a hand-entered transaction.
     *
     * <p>Manual rows only. A fetched row deleted on its own would simply be fetched again by the
     * next sync, so answering 200 to that request would be a lie; the honest way to remove one is
     * to disconnect the account it comes from, which is what {@code DELETE /v1/accounts/{id}}
     * does.
     */
    @Transactional
    public void delete(UUID householdId, UUID transactionId) {
        Transaction transaction = require(householdId, transactionId);
        if (!transaction.isManual()) {
            throw new ConflictException("Only a hand-entered transaction can be deleted. This one "
                + "came from its account's source and would return on the next sync; disconnect "
                + "the account instead.");
        }
        transactions.deleteByIdAndHouseholdId(transactionId, householdId);
    }

    /** The scoped lookup every by-id route funnels through; empty is 404, never 403. */
    public Transaction require(UUID householdId, UUID transactionId) {
        return transactions.findByIdAndHouseholdId(transactionId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Transaction", transactionId));
    }

    /**
     * Upserts the pin. One row per transaction (V21's {@code UNIQUE (transaction_id)}), so
     * correcting a category twice replaces the decision rather than accumulating contradictory
     * rows the way the pre-collapse {@code UNIQUE (user_id, transaction_id)} allowed.
     */
    private void pin(UUID householdId, UUID userId, UUID transactionId, UUID categoryId) {
        overrides.findByHouseholdIdAndTransactionId(householdId, transactionId)
            .ifPresentOrElse(
                existing -> {
                    existing.repin(categoryId, userId);
                    overrides.save(existing);
                },
                () -> overrides.save(new CategoryOverride(householdId, userId, transactionId,
                    categoryId, clock.now())));
    }

    /**
     * Resolves a category the household may actually use — a system row or one of its own.
     *
     * <p>400 rather than 404 because the id arrived in a body as a field value, so
     * {@code fieldErrors} can name it; a 404 here would also be ambiguous with the transaction's
     * own id being unknown.
     */
    private UUID requireVisibleCategory(UUID householdId, UUID categoryId) {
        return categories.findVisibleById(categoryId, householdId)
            .orElseThrow(() -> new ValidationException(
                "categoryId does not name a category this household can use"))
            .getId();
    }

    /**
     * Publishes {@link TransactionCategorisedEvent} (§6 D4 → D1).
     *
     * <p>Nothing consumes it in this step — {@code EnvelopeSpendListener} is step 8's and
     * {@code CategorisationListener} step 16's. Publishing it now is what lets those steps be
     * additive: they add a listener, they do not have to go back and find every write path.
     *
     * <p>The month comes from the transaction's own {@code date}, which is already a calendar date
     * in the source's terms, so no timezone conversion is involved or wanted here.
     */
    private void publishCategorised(UUID householdId, UUID userId, Transaction transaction,
                                    UUID previousCategoryId, UUID categoryId) {
        events.publishEvent(new TransactionCategorisedEvent(householdId, userId,
            transaction.getId(), previousCategoryId, categoryId,
            YearMonth.from(transaction.getDate()), transaction.getCurrency()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
