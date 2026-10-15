package finance.trove.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The counterpart to {@link UserScopedRepository} for the tables that are scoped to an individual
 * <strong>user</strong> rather than to the shared household ledger
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5): {@code refresh_token},
 * {@code local_credential}, {@code totp_credential}, {@code oidc_link}, {@code login_attempt},
 * {@code security_event}, {@code ai_settings}, {@code notification_preference},
 * {@code webhook_subscription}, {@code user_preferences} and {@code tax_export_run}'s owner view.
 *
 * <p>Same mechanism, same reason: no inherited {@code findById}. A household admin can act on a
 * member's ledger rows; nobody, admin included, reads another member's credentials or AI settings
 * through an id in a path.
 *
 * @param <T> the entity type
 * @param <I> the entity's identifier type
 */
@NoRepositoryBean
public interface UserOwnedRepository<T, I> extends Repository<T, I> {

    Optional<T> findByIdAndUserId(I id, UUID userId);

    List<T> findAllByUserId(UUID userId);

    Page<T> findAllByUserId(UUID userId, Pageable pageable);

    boolean existsByIdAndUserId(I id, UUID userId);

    long countByUserId(UUID userId);

    /** @return rows deleted; {@code 0} means the id belongs to someone else — answer 404 */
    long deleteByIdAndUserId(I id, UUID userId);

    <S extends T> S save(S entity);

    <S extends T> List<S> saveAll(Iterable<S> entities);
}
