package finance.trove.dataexport;

import finance.trove.common.repository.UserOwnedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link DataExportRun}.
 *
 * <p>{@link UserOwnedRepository} rather than {@code UserScopedRepository} is the whole point: the
 * archive is a copy of one member's own account, so every finder here takes a {@code userId} and a
 * household mate's run is simply not reachable. Since commit 571aa17 every repository must extend
 * one of the three scoped bases; the base does not inherit {@code findById}/{@code findAll} at
 * all, and {@code ArchitectureTest} fails the build on a subinterface that declares one.
 *
 * <p>The base's {@code findByIdAndUserId}, {@code findAllByUserId}, {@code deleteByIdAndUserId}
 * and {@code save} are most of what this step asks for. Two queries are added.
 */
public interface DataExportRunRepository extends UserOwnedRepository<DataExportRun, UUID> {

    /**
     * How many runs this member has started since {@code from} — the one-per-day cap.
     *
     * <p>{@code from} is the start of the caller's OWN local day, computed by the service through
     * {@code UserClock}, never a server date. Two members in {@code America/Vancouver} and
     * {@code America/Toronto} get different boundaries from the same instant, and a cap that used
     * the container's day would refuse one of them a run they are entitled to.
     *
     * <p>It counts every run, including {@code FAILED} ones: a member whose export failed can
     * plainly see it failed, and letting a failure reset the cap would make the cap free to defeat
     * by cancelling. The refusal message says when they may ask again.
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(UUID userId, Instant from);

    /**
     * Completed runs whose download link has lapsed and whose bytes are still on the volume.
     *
     * <p>Deliberately NOT user-scoped, and deliberately not named {@code findAll...}: this is the
     * sweep's query, it runs on a schedule with no caller and no session, and the rows it returns
     * are never rendered to anybody — each one is resolved to a file key, the file is deleted and
     * the key is cleared. The {@code Pageable} bounds one pass so that a volume with a long
     * backlog is drained over several runs rather than in one transaction.
     */
    @Query("select r from DataExportRun r "
        + "where r.fileKey is not null and r.expiresAt is not null and r.expiresAt < :cutoff "
        + "order by r.expiresAt asc")
    List<DataExportRun> findLapsedArchives(@Param("cutoff") Instant cutoff, Pageable page);
}
