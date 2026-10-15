package finance.trove.common.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The scoped base for entities whose <strong>primary key is the user id</strong> — one row per
 * user, no surrogate id at all.
 *
 * <p>{@link UserOwnedRepository} does not fit these. It declares {@code findByIdAndUserId} and
 * friends, which Spring Data derives at startup by walking the entity's properties; on an entity
 * with no {@code id} property that fails the context with
 * {@code No property 'id' found for type ...}, naming one repository and giving no clue that the
 * base interface is the problem. The distinction is real rather than cosmetic: for
 * {@code local_credential} and {@code totp_credential} the user id <em>is</em> the identity, so
 * {@code findByUserId} is already the scoped finder and a second id would be a way to address the
 * row without naming its owner.
 *
 * <p>Later steps will map {@code ai_settings} and {@code notification_preference} the same way
 * (§3 V37, V33); this is the base they extend.
 *
 * <p>Like the other two bases it deliberately exposes no {@code findById}, {@code findAll} or
 * {@code deleteById}: {@code ArchitectureTest} treats an unscoped finder on any of the three as a
 * violation (§9 risk 2).
 */
@NoRepositoryBean
public interface UserKeyedRepository<T> extends Repository<T, UUID> {

    Optional<T> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    long deleteByUserId(UUID userId);

    <S extends T> S save(S entity);
}
