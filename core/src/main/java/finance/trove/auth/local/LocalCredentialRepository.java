package finance.trove.auth.local;

import finance.trove.common.repository.UserKeyedRepository;

/**
 * Credentials are strictly per-user (§1.5): a household admin administers a member's account but
 * never reads their password material, so this extends {@link UserKeyedRepository} and not the
 * household-scoped base.
 *
 * <p>The primary key <em>is</em> the user id, which is why the base is
 * {@link UserKeyedRepository} rather than {@link finance.trove.common.repository.UserOwnedRepository}:
 * there is no surrogate {@code id} property for Spring Data to derive {@code findByIdAndUserId}
 * from, and asking it to would fail the context at startup.
 */
public interface LocalCredentialRepository extends UserKeyedRepository<LocalCredential> {
}
