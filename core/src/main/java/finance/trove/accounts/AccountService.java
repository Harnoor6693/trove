package finance.trove.accounts;

import finance.trove.accounts.dto.AccountRequest;
import finance.trove.accounts.dto.AccountUpdateRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, reads, renames and disconnects the household's accounts
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3; gap P2-13).
 *
 * <p>Every method takes {@code householdId} as its first argument and passes it to a scoped
 * repository query. Nothing here accepts an identity from a caller — the controller reads it from
 * the signed token and hands it down — and a row of another household is 404, never 403, so a
 * refusal cannot be used to confirm that an id is real (§9 risk 2).
 */
@Service
public class AccountService {

    private final ConnectedAccountRepository accounts;
    private final UserRepository users;
    private final UserClock clock;
    private final ApplicationEventPublisher events;

    public AccountService(ConnectedAccountRepository accounts, UserRepository users,
                          UserClock clock, ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ConnectedAccount> list(UUID householdId) {
        return accounts.findAllByHouseholdIdOrderByCreatedAtDesc(householdId);
    }

    @Transactional(readOnly = true)
    public ConnectedAccount get(UUID householdId, UUID accountId) {
        return require(householdId, accountId);
    }

    /**
     * Adds an account to the household.
     *
     * <p>The currency is the request's, or the creating member's own {@code users.currency}. There
     * is no literal fallback: §9 risk 5 makes a hard-coded currency an architecture violation, and
     * the reason is not stylistic — an account created with the wrong denomination silently
     * mis-states every amount ever stored against it, and no later edit can repair it because the
     * amounts themselves were never wrong, only their unit.
     *
     * <p><strong>The synthetic external id.</strong> V18's uniqueness key is
     * {@code (household_id, source_kind, external_account_id) NULLS NOT DISTINCT} — nulls compare
     * <em>equal</em> — so a household could hold at most ONE account per source kind with the
     * column left null. That is plainly wrong as a product rule: gap P2-14 (transfers between the
     * household's own accounts) needs at least two accounts, and "Cash" and "Cheques" are two
     * perfectly ordinary manual accounts. So an account created through this endpoint is given its
     * own primary key as its external id.
     *
     * <p>That does not weaken the constraint, because of what the constraint is for (§6 M0): it
     * exists so that step 7's SimpleFIN re-exchange or repeat OFX/CSV upload can look an account up
     * by <strong>the provider's own id</strong> and reuse it, instead of creating a second account
     * and duplicating every transaction on it. An account somebody typed in here has no provider
     * identity to be looked up by — it is a placeholder, not the bridge's account — so a value
     * unique to the row is the honest thing to store, and rows step 7 creates with real provider
     * ids are still held to one per household per kind.
     *
     * <p>That also means this method has no conflict to report: a synthetic id unique to the row
     * cannot collide with anything, so there is no "this household already has an account of that
     * kind" case. A {@link DataIntegrityViolationException} here would mean the database refused
     * the write with an integrity or data error that request validation did not catch: no
     * expected path produces one, and it is not a duplicate-account signal. It is left to the
     * global handler, whose generic answer is 409 {@code conflict}, rather than given a cause it
     * has not established. Provider accounts go through {@code upsertProviderAccount}, which
     * inserts only if absent.
     */
    @Transactional
    public ConnectedAccount create(UUID householdId, UUID userId, AccountRequest request) {
        CurrencyCode currency = request.currency() != null
            ? request.currency()
            : users.findByIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId))
                .getCurrency();

        ConnectedAccount account = new ConnectedAccount(householdId, userId, request.sourceKind(),
            request.displayName().trim(), trimToNull(request.institution()),
            request.accountType(), currency, clock.now());

        ConnectedAccount saved = accounts.save(account);
        // The id is generated in memory by save(); the managed entity is flushed at commit with
        // the external id already set.
        saved.setExternalAccountId(saved.getId().toString());
        return saved;
    }

    /**
     * Finds this household's account for a provider's own account id, or creates it — the upsert
     * collapse step 7's SimpleFIN re-exchange and repeat OFX upload both go through
     * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0, blocker).
     *
     * <p><strong>Why an existing row is returned untouched.</strong> V18's unique key is
     * {@code (household_id, source_kind, external_account_id)}, and it exists so that connecting
     * the same bridge account a second time — after a token rotation, or after re-uploading last
     * month's statement — reuses the row instead of creating a second account and duplicating
     * every transaction on it. Reusing it means reusing what the member has since done to it: a
     * renamed account keeps its name, and the currency is not rewritten at all (the column is
     * {@code updatable = false}, because every amount already stored against the account was
     * written in it).
     *
     * <p><strong>The currency of a NEW row comes from the provider, then from the creating
     * member's own {@code users.currency}. Never a literal</strong> — §9 risk 5, and finding
     * F4's exact shape: an account created in the wrong denomination mis-states every amount
     * ever stored against it, and no later edit can repair it because the amounts were never
     * wrong, only their unit.
     *
     * @param externalAccountId the provider's own id — a SimpleFIN account id, an OFX
     *                          {@code ACCTID}. Never null here: the caller has one or it does not
     *                          call this method
     * @param currency          the currency the provider declared, or {@code null} for the
     *                          member's own
     * @param configEncrypted   the account's encrypted source configuration, or {@code null} to
     *                          leave whatever is there. Written on BOTH paths when supplied: a
     *                          re-exchange after a token rotation produces a NEW access URL, and
     *                          reusing the row while keeping the old, now-dead credential would
     *                          make every later sync fail with nothing to explain it
     */
    @Transactional
    public ConnectedAccount upsertProviderAccount(UUID householdId, UUID userId,
                                                  SourceKind sourceKind, String externalAccountId,
                                                  String displayName, String institution,
                                                  AccountType accountType, CurrencyCode currency,
                                                  String configEncrypted) {
        ConnectedAccount account = findOrCreate(householdId, userId, sourceKind, externalAccountId,
            displayName, institution, accountType, currency);
        if (configEncrypted != null) {
            account.setConfigEncrypted(configEncrypted);
            account = accounts.save(account);
        }
        return account;
    }

    private ConnectedAccount findOrCreate(UUID householdId, UUID userId, SourceKind sourceKind,
                                          String externalAccountId, String displayName,
                                          String institution, AccountType accountType,
                                          CurrencyCode currency) {
        Optional<ConnectedAccount> existing = accounts
            .findByHouseholdIdAndSourceKindAndExternalAccountId(householdId, sourceKind,
                externalAccountId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Insert-if-absent, then read: a concurrent first upsert of the same account makes this a
        // no-op that reads the winner's row, where a caught unique violation would have aborted
        // this transaction (B1-15).
        accounts.insertProviderAccountIfAbsent(householdId, userId, sourceKind.name(),
            externalAccountId, displayName.trim(), trimToNull(institution), accountType.name(),
            (currency != null ? currency : creatorCurrency(householdId, userId)).name(),
            clock.now());
        return accounts.findByHouseholdIdAndSourceKindAndExternalAccountId(householdId, sourceKind,
                externalAccountId)
            .orElseThrow(() -> new ConflictException("This household already has a "
                + sourceKind + " account for that source."));
    }

    private CurrencyCode creatorCurrency(UUID householdId, UUID userId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId))
            .getCurrency();
    }

    @Transactional
    public ConnectedAccount update(UUID householdId, UUID accountId,
                                   AccountUpdateRequest request) {
        ConnectedAccount account = require(householdId, accountId);
        account.setDisplayName(request.displayName().trim());
        account.setInstitution(trimToNull(request.institution()));
        account.setAccountType(request.accountType());
        return accounts.save(account);
    }

    /**
     * Disconnects an account, and with it every transaction stored against it
     * ({@code ON DELETE CASCADE} on {@code transaction.account_id}).
     *
     * <p>That cascade is the whole reason {@code DELETE /v1/transactions/{id}} refuses a synced
     * row: a fetched transaction removed on its own would simply be fetched again, so the honest
     * way to be rid of one is to be rid of the connection that produces it.
     *
     * <p><strong>Envelopes are recomputed (G2).</strong> The cascade is invisible to the
     * application, and a budget envelope's {@code spent} is a stored figure rewritten only when a
     * {@link TransactionCategorisedEvent} arrives, so without one the envelope would keep counting
     * rows that no longer exist. Two sets of rows change: the account's own categorised rows
     * (gone) and the categorised legs of its transfer partners on other accounts (unlinked by
     * {@code freeTransferPartners}, so spending again). Both are read first, while they still
     * exist and are still linked, and one event per category and month is published after the
     * delete; the listener recomputes from the ledger as it stands once this commits. An unknown
     * or foreign account is 404 and publishes nothing.
     *
     * @param userId the member disconnecting the account; the events need an actor
     */
    @Transactional
    public void delete(UUID householdId, UUID userId, UUID accountId) {
        List<CategorisedRow> affected =
            accounts.categorisedRowsAffectedByDisconnect(accountId, householdId);
        accounts.freeTransferPartners(accountId, householdId);
        if (accounts.deleteByIdAndHouseholdId(accountId, householdId) == 0) {
            throw ResourceNotFoundException.of("Account", accountId);
        }
        Map<CategoryMonth, CategorisedRow> perCategoryAndMonth = new LinkedHashMap<>();
        for (CategorisedRow row : affected) {
            perCategoryAndMonth.putIfAbsent(
                new CategoryMonth(row.categoryId(), YearMonth.from(row.date())), row);
        }
        perCategoryAndMonth.forEach((bucket, row) -> events.publishEvent(
            new TransactionCategorisedEvent(householdId, userId, row.transactionId(),
                bucket.categoryId(), bucket.categoryId(), bucket.month(), row.currency())));
    }

    /** What one envelope recompute covers; the listener keys on exactly this pair. */
    private record CategoryMonth(UUID categoryId, YearMonth month) {
    }

    /** The scoped lookup every by-id route funnels through; empty is 404, never 403. */
    ConnectedAccount require(UUID householdId, UUID accountId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Account", accountId));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
