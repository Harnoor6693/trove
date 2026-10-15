package finance.trove.auth.totp;

import finance.trove.common.repository.UserKeyedRepository;

/**
 * A second factor belongs to one member and to nobody else — not even to their household's
 * admin, who may <em>clear</em> a member's TOTP (an audited action) but can never read the
 * secret. Hence {@link UserKeyedRepository}, whose finders all take a user id.
 */
public interface TotpCredentialRepository extends UserKeyedRepository<TotpCredential> {
}
