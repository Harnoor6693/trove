package finance.trove.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import finance.trove.common.repository.UserScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link Transaction}.
 *
 * <p>Extends {@link UserScopedRepository}: no inherited {@code findById}, no {@code findAll}, and
 * every query below names its scope. Two of them take {@code accountId} rather than
 * {@code householdId} and are package-private to the ingestion path — an account id has already
 * been resolved inside a household by the time they are reached, so the scope is established, not
 * skipped.
 */
public interface TransactionRepository extends UserScopedRepository<Transaction, UUID> {

    /**
     * The exact deduplication key (§6 M0): one transaction per {@code (account, provider id)}.
     *
     * <p>This is the query the pre-collapse service also ran — against a column that held the
     * plugin's name on every row, so it matched the account's single stored transaction every
     * time and every subsequent row was discarded as a duplicate.
     */
    Optional<Transaction> findByAccountIdAndProviderTransactionId(UUID accountId,
                                                                  String providerTransactionId);

    /**
     * The same exact key, for a whole file at once (confirmed review finding 5).
     *
     * <p>A preview classifies EVERY row of an upload — up to {@code trove.import.max-rows}, fifty
     * thousand — and doing that one {@code findByAccountIdAndProviderTransactionId} at a time is
     * fifty thousand sequential round trips on one request thread. Every CSV row carries a
     * deterministic id and most OFX rows carry a {@code FITID}, so this is the dominant path, and
     * {@code DeduplicationService.exactMatchIndex} chunks a file's ids through here instead.
     *
     * <p>It answers exactly what the single-row query answers, row for row: the rules stay in
     * {@code DeduplicationService.match}, which reads this index when it has one and runs the
     * single-row query when it does not.
     */
    List<Transaction> findAllByAccountIdAndProviderTransactionIdIn(UUID accountId,
                                                        Collection<String> providerTransactionIds);

    /**
     * Candidates for the corrected fuzzy pass: same account, date within the window, amount within
     * the tolerance.
     *
     * <p>It deliberately does <em>not</em> filter on {@code pending}. The pre-collapse query did,
     * which is the second of the two fuzzy defects: a re-imported CSV arrives already posted, so
     * no pending candidate existed, nothing matched, and every row of the file was inserted a
     * second time. Which candidates are eligible is now decided in
     * {@code DeduplicationService} — where the incoming row's own state is also known — rather
     * than baked into the query.
     */
    @Query("select t from Transaction t where t.accountId = :accountId "
        + "and t.date >= :fromDate and t.date <= :toDate "
        + "and t.amount >= :minAmount and t.amount <= :maxAmount "
        + "order by t.date asc, t.createdAt asc")
    List<Transaction> findFuzzyCandidates(@Param("accountId") UUID accountId,
                                          @Param("fromDate") LocalDate fromDate,
                                          @Param("toDate") LocalDate toDate,
                                          @Param("minAmount") BigDecimal minAmount,
                                          @Param("maxAmount") BigDecimal maxAmount);

    /**
     * Every unpaired leg of this household's ledger inside a date window, for the transfer-pairing
     * job (gap P2-14). Household-scoped, so a leg can never be paired with another household's.
     */
    @Query("select t from Transaction t where t.householdId = :householdId "
        + "and t.transferGroupId is null "
        + "and t.date >= :fromDate and t.date <= :toDate "
        + "order by t.date asc, t.createdAt asc")
    List<Transaction> findUnpairedLegs(@Param("householdId") UUID householdId,
                                       @Param("fromDate") LocalDate fromDate,
                                       @Param("toDate") LocalDate toDate);

    /**
     * Households that have at least one unpaired leg in the window — the transfer-pairing job's
     * work list.
     *
     * <p>The one query here that takes no {@code householdId}, because it is what <em>produces</em>
     * the household ids the scoped queries are then run with. It returns nothing but ids: no row
     * of any household's ledger crosses this method, and the job immediately narrows to one
     * household per call.
     */
    @Query("select distinct t.householdId from Transaction t where t.transferGroupId is null "
        + "and t.date >= :fromDate and t.date <= :toDate")
    List<UUID> findHouseholdIdsWithUnpairedLegs(@Param("fromDate") LocalDate fromDate,
                                                @Param("toDate") LocalDate toDate);

    /** Whether this household has any transaction on the given account. */
    boolean existsByHouseholdIdAndAccountId(UUID householdId, UUID accountId);

    /**
     * The rows one import batch inserted (gap P2-12), household-scoped.
     *
     * <p>It is the READ half of an undo. The delete is deliberately not expressed here as a
     * {@code deleteByImportBatchId}: V22 chose {@code ON DELETE SET NULL} on this column precisely
     * so that removing a batch can never take a household's ledger with it, and an undo has to
     * make the same distinction one row at a time — a row a member has since edited stays, still
     * carrying the batch id as the record of where it came from.
     */
    List<Transaction> findAllByHouseholdIdAndImportBatchIdOrderByCreatedAtAsc(UUID householdId,
                                                                              UUID importBatchId);
}
