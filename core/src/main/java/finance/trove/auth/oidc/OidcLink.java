package finance.trove.auth.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A link between one Trove account and one identity at one OpenID provider (V6).
 *
 * <p>The lookup key is {@code (provider, provider_sub)} and never the email address. That is the
 * whole of readiness gap §9 risk 27: the pre-collapse success handler matched on
 * {@code getEmail()} alone, so anyone who could get an IdP account issued with an unverified
 * address matching a household member could sign in as that member with full ledger access.
 * {@code sub} is the only claim an OpenID provider guarantees is stable and unique.
 *
 * <p>{@code email_at_link} records what the ID token claimed at the moment the link was made. It
 * is forensic — an operator can see which address was trusted — and never an input to a decision.
 */
@Entity
@Table(name = "oidc_link")
public class OidcLink {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_sub", nullable = false, length = 255, updatable = false)
    private String providerSub;

    @Column(name = "email_at_link", length = 320)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false, insertable = false)
    private Instant linkedAt;

    protected OidcLink() {
        // JPA
    }

    public OidcLink(UUID userId, String provider, String providerSub, String emailAtLink) {
        this.userId = userId;
        this.provider = provider;
        this.providerSub = providerSub;
        this.emailAtLink = emailAtLink;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderSub() {
        return providerSub;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
