package finance.trove.ingestion.importbatch;

import finance.trove.common.repository.UserScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository over {@link ImportBatch}.
 *
 * <p>Extends {@link UserScopedRepository}. The four import ROUTES need nothing but the inherited
 * {@code findByIdAndHouseholdId}, which names its household — that is what makes a batch belonging
 * to another household a 404 by construction rather than by a check somebody has to remember
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 2).
 *
 * <p>{@link #findHouseholdIdsWithPreviewsBefore}, the per-household list and
 * {@link #deletePreview} belong to the retention sweep and to nothing else. They follow the
 * shape {@code TransactionRepository.findHouseholdIdsWithUnpairedLegs} established for a job that
 * has no household of its own: the unscoped one returns nothing but household ids — no batch, no
 * mapping, no storage key crosses it — and the sweep then narrows to one household and uses the
 * scoped query for the rows themselves.
 */
public interface ImportBatchRepository extends UserScopedRepository<ImportBatch, UUID> {

    /**
     * Households holding a {@code PREVIEW} batch created before {@code cutoff} — the sweep's work
     * list (confirmed review finding 3).
     */
    @Query("select distinct b.householdId from ImportBatch b "
        + "where b.status = finance.trove.ingestion.importbatch.ImportBatchStatus.PREVIEW "
        + "and b.createdAt < :cutoff")
    List<UUID> findHouseholdIdsWithPreviewsBefore(@Param("cutoff") Instant cutoff);

    /**
     * Moves a {@code PREVIEW} batch to {@code COMMITTED} with what the commit did (B1-16).
     *
     * <p>Conditional, because the PREVIEW check in {@code ImportService.commit} runs before a sync
     * that can take a minute: a second commit that passed the check in that window must not write
     * its own counts over the first one's, and the sweep must not have removed the batch.
     *
     * @return 1, or 0 when the batch is no longer a PREVIEW (or no longer exists)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update ImportBatch b "
        + "set b.status = finance.trove.ingestion.importbatch.ImportBatchStatus.COMMITTED, "
        + "b.rowCount = :rows, b.newCount = :inserted, b.dupCount = :duplicates "
        + "where b.id = :id and b.householdId = :householdId "
        + "and b.status = finance.trove.ingestion.importbatch.ImportBatchStatus.PREVIEW")
    int markCommitted(@Param("id") UUID id, @Param("householdId") UUID householdId,
                      @Param("rows") int rows, @Param("inserted") int inserted,
                      @Param("duplicates") int duplicates);

    /**
     * Deletes the batch only while it is still a {@code PREVIEW}: the sweep read it as one, and a
     * commit may have recorded itself since (B1-16).
     *
     * @return 1, or 0 when the batch was committed meanwhile
     */
    @Modifying
    @Transactional
    @Query("delete from ImportBatch b where b.id = :id and b.householdId = :householdId "
        + "and b.status = finance.trove.ingestion.importbatch.ImportBatchStatus.PREVIEW")
    int deletePreview(@Param("id") UUID id, @Param("householdId") UUID householdId);

    /** One household's batches in {@code status} created before {@code cutoff}. */
    List<ImportBatch> findAllByHouseholdIdAndStatusAndCreatedAtBefore(UUID householdId,
                                                                     ImportBatchStatus status,
                                                                     Instant cutoff);
}
