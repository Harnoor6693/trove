package finance.trove.receipts.receipt;

import finance.trove.common.repository.UserScopedRepository;
import finance.trove.receipts.receipt.dto.ReceiptSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The household's receipts ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §9 risk 2).
 *
 * <p>{@link UserScopedRepository} because {@code household_id} is the scope: every finder it
 * declares takes one, and the base deliberately inherits no {@code findById}, {@code findAll} or
 * {@code deleteById} for a caller to reach for. {@code ArchitectureTest} fails the build on a
 * repository that extends none of the three bases and on one that hand-declares an unscoped
 * finder name.
 *
 * <p><strong>This is the only repository in the receipts package.</strong> {@code receipt_image}
 * and {@code line_item} carry no {@code household_id} of their own, so a repository over either
 * could only be scoped by trusting an id — instead both are mapped as owned collections on
 * {@link Receipt} and are reached through it.
 *
 * <p>The two queries below take no household, and both are deliberate exceptions with a stated
 * reason: they belong to the hourly orphan sweep, which is an install-wide background job rather
 * than a request, has no principal, and must see every household by definition. Neither is a
 * by-id lookup and neither is reachable from a controller.
 */
public interface ReceiptRepository extends UserScopedRepository<Receipt, UUID> {

    /**
     * Receipts stuck in a non-terminal status since before {@code cutoff}, across every household.
     *
     * <p>The sweep's own query (§5 item 6). It matches V37's {@code receipt_active_idx}, a partial
     * index over exactly {@code status NOT IN ('COMPLETE','FAILED')}, which is why that index
     * exists rather than being carried over decoratively.
     */
    @Query("select r from Receipt r where r.status not in (finance.trove.receipts.receipt"
        + ".ReceiptStatus.COMPLETE, finance.trove.receipts.receipt.ReceiptStatus.FAILED) "
        + "and r.updatedAt < :cutoff order by r.updatedAt asc")
    List<Receipt> findStuckBefore(@Param("cutoff") Instant cutoff);

    /**
     * One page of the household's receipts as a summary projection, newest first.
     *
     * <p>A constructor expression rather than a page of entities: {@link Receipt} eagerly fetches
     * its images and its line items — which the pipeline and every by-id route need — and a page of
     * two hundred entities would be four hundred collection selects for a list that renders
     * neither. This reads one row per receipt and touches no collection at all.
     */
    @Query("select new finance.trove.receipts.receipt.dto.ReceiptSummaryResponse("
        + "r.id, r.status, r.source, r.extractedMerchant, r.extractedAmount, r.extractedCurrency, "
        + "r.extractedDate, r.confidence, r.reviewRequired, r.createdAt) "
        + "from Receipt r where r.householdId = :householdId order by r.createdAt desc, r.id desc")
    List<ReceiptSummaryResponse> findSummaries(@Param("householdId") UUID householdId,
                                               Pageable pageable);

    /**
     * Writes the terminal failure of one receipt as a targeted {@code UPDATE}, touching four
     * columns and nothing else.
     *
     * <p><strong>The last resort of {@code ReceiptPipelineOrchestrator.fail}, and it exists
     * because saving the entity can be the very thing that failed.</strong> If a stage put a value
     * on the receipt that the database refuses — a figure too wide for a {@code NUMERIC(19,4)}
     * column, say — then {@code save} throws, the failure path catches it and calls {@code save}
     * again with that same value still on the entity, and it throws again. The receipt is then
     * stuck in a non-terminal status for ever and the hourly sweep re-enqueues it every hour,
     * spending one of its bounded slots on a row that can never finish. This statement carries
     * none of the entity's other columns, so it lands whatever else is wrong with it.
     *
     * <p>Household-scoped like every other query here, even though the caller has just read the
     * row: an id on its own is not a permission anywhere else in this tree and is not one here.
     *
     * @return the number of rows updated: 1, or 0 if the receipt was deleted meanwhile
     */
    @Modifying
    @Transactional
    @Query("update Receipt r set r.status = finance.trove.receipts.receipt.ReceiptStatus.FAILED, "
        + "r.errorMessage = :message, r.reviewRequired = true, r.processingCompletedAt = :now "
        + "where r.id = :receiptId and r.householdId = :householdId")
    int markFailed(@Param("receiptId") UUID receiptId, @Param("householdId") UUID householdId,
                   @Param("message") String message, @Param("now") Instant now);

    /**
     * Every stored file key the database still knows about, across every household.
     *
     * <p>The other half of the sweep: a file on the volume whose key appears in no row is an
     * orphan, and an orphan that nothing deletes is gap row 30's unbounded disk growth. Returning
     * keys rather than entities keeps the set small enough to hold while walking the directory.
     */
    @Query("select i.fileKey from ReceiptImage i")
    List<String> findAllStoredKeys();
}
