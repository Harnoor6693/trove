package finance.trove.auth.oidc;

import finance.trove.common.repository.UserOwnedRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Links are per-user. {@link #findByProviderAndProviderSub} is the one unscoped finder and is
 * constrained by the {@code UNIQUE (provider, provider_sub)} of V6 — at the moment it runs there
 * is no authenticated principal to scope by, because resolving the link is what produces one.
 */
public interface OidcLinkRepository extends UserOwnedRepository<OidcLink, UUID> {

    Optional<OidcLink> findByProviderAndProviderSub(String provider, String providerSub);
}
