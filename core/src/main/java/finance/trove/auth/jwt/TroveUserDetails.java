package finance.trove.auth.jwt;

import finance.trove.users.UserRole;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal for every request in the monolith.
 *
 * <p>It carries three things the pre-collapse {@code libs/common} version did not, each of which
 * closes a real gap:
 * <ul>
 *   <li><strong>{@code householdId}.</strong> Tenancy is by household (§1.5). Carrying it on the
 *       principal is what lets every repository call take a household id from the request context
 *       instead of from a path variable or a request body — the structural cause of the G1 IDOR.
 *       It is read from the token, which the server signed; a client cannot change it.</li>
 *   <li><strong>{@code role}.</strong> The household-scoped ADMIN flag, so an admin route is a
 *       matcher rather than a service-layer {@code if}.</li>
 *   <li><strong>{@code steppedUpAt}.</strong> When this session last completed a step-up
 *       (password + TOTP). Sensitive actions — disabling TOTP, changing a password, adding a bank
 *       connection, export, account deletion — require one inside a short window (§1.5, gap
 *       P2-6). Putting the instant on the principal means a controller asks "is this session
 *       stepped up?" rather than re-authenticating by hand in five places.</li>
 * </ul>
 *
 * <p>{@link #getPassword()} is {@code null} by design: this principal is built from a verified
 * token, never from a credential comparison, and nothing downstream may re-check a password
 * through it.
 */
public final class TroveUserDetails implements UserDetails {

    private final UUID userId;
    private final UUID householdId;
    private final UUID familyId;
    private final String email;
    private final UserRole role;
    private final Set<String> roles;
    private final Instant steppedUpAt;

    public TroveUserDetails(UUID userId, UUID householdId, UUID familyId, String email,
                            UserRole role, Instant steppedUpAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.householdId = Objects.requireNonNull(householdId, "householdId");
        this.familyId = familyId;
        this.email = email;
        this.role = role == null ? UserRole.MEMBER : role;
        this.steppedUpAt = steppedUpAt;
        Set<String> authorities = new LinkedHashSet<>();
        authorities.add("ROLE_USER");
        authorities.add(this.role.authority());
        this.roles = Set.copyOf(authorities);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    /**
     * The refresh-token family this session belongs to, or {@code null} for a token minted before
     * the {@code fid} claim existed.
     *
     * <p>It is what lets an action end "every other session" without asking the caller to hand its
     * refresh token back — a bearer client holds that token in a keystore and has no reason to put
     * it in a request body.
     */
    public UUID getFamilyId() {
        return familyId;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    /** {@code ROLE_USER} plus the household role — the shape the pre-collapse clients read. */
    public Set<String> getRoles() {
        return roles;
    }

    /** When this session last completed a step-up, or {@code null} if it never has. */
    public Instant getSteppedUpAt() {
        return steppedUpAt;
    }

    public boolean isAdmin() {
        return role.isAdmin();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast)
            .toList();
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
