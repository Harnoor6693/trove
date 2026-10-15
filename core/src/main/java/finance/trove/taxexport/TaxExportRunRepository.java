package finance.trove.taxexport;

import finance.trove.common.repository.UserOwnedRepository;
import java.util.UUID;

/**
 * Repository over {@link TaxExportRun}.
 *
 * <p>{@link UserOwnedRepository} rather than {@code UserScopedRepository} is the whole point: a
 * tax filing is individual even inside the shared household ledger (§1.5, §6), so every finder
 * here takes a {@code userId} and a household mate's run is simply not reachable. That is the
 * opposite of the tenancy every other ledger table follows, and it is deliberate — it is not a
 * mistake to "fix" to {@code householdId}.
 *
 * <p>Since commit 571aa17 every repository must extend one of the three scoped bases; the base
 * does not inherit {@code findById}/{@code findAll} at all and {@code ArchitectureTest} fails the
 * build on a subinterface that declares one. Nothing extra is declared here: the base's
 * {@code findByIdAndUserId}, {@code findAllByUserId(userId, pageable)}, {@code countByUserId},
 * {@code deleteByIdAndUserId} and {@code save} are exactly the queries this step makes.
 */
public interface TaxExportRunRepository extends UserOwnedRepository<TaxExportRun, UUID> {
}
