package finance.trove.ingestion.importbatch;

import finance.trove.common.repository.UserScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link ImportBatch}.
 *
 * <p>Extends {@link UserScopedRepository}. The four import ROUTES need nothing but the inherited
 * {@code findByIdAndHouseholdId}, which names its household — that is what makes a batch belonging
 * to another household a 404 by construction rather than by a check somebody has to remember
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 2).
 *
 * <p>The two queries below belong to the retention sweep and to nothing else. They follow the
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

    /** One household's batches in {@code status} created before {@code cutoff}. */
    List<ImportBatch> findAllByHouseholdIdAndStatusAndCreatedAtBefore(UUID householdId,
                                                                     ImportBatchStatus status,
                                                                     Instant cutoff);
}
