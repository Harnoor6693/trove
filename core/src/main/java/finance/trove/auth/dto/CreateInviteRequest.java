package finance.trove.auth.dto;

import finance.trove.users.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/admin/invites}. Every field is optional and the body may be {@code {}} — which
 * is literally what the web client sends ({@code apps/web/src/pages/Settings/index.tsx:167}:
 * {@code createInvite({})}), so both fields must be nullable and the whole body must be optional.
 *
 * <p>{@code email} binds the invite to one address: the registration that redeems it must use
 * exactly that address. Leaving it out produces a hand-out link, which is the common case — an
 * admin pastes it into whatever channel they already talk to their household in.
 *
 * <p>{@code role} lets an admin mint another admin. It defaults to MEMBER, because the failure
 * mode of the other default — every invite silently conferring admin — is not recoverable by the
 * person who notices it.
 */
public record CreateInviteRequest(
    @Email @Size(max = 320) String email,
    UserRole role) {

    /** The body an absent or empty JSON document stands for. */
    public static CreateInviteRequest empty() {
        return new CreateInviteRequest(null, null);
    }

    public UserRole roleOrDefault() {
        return role == null ? UserRole.MEMBER : role;
    }
}
