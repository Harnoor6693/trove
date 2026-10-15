package finance.trove.users;

/**
 * A member's role <em>within their household</em>
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, gap P2-7).
 *
 * <p>Admin rights are household-scoped and never cross a household boundary: an ADMIN of
 * household A has no more access to household B than an anonymous caller does. Under the owner's
 * one-shared-ledger decision there is normally one household per install, but the rule is written
 * into the tenancy tests ({@code AccessControlIT}) rather than assumed from the deployment shape.
 *
 * <p>An ADMIN may invite and remove members, flip the registration policy, issue password-reset
 * links, unlock a locked account and clear a member's TOTP. An ADMIN may <strong>not</strong> read
 * another member's credentials, TOTP secret or AI settings — those tables are user-scoped, and
 * "admin is not a tenancy bypass" is gap P2-7's own wording.
 */
public enum UserRole {

    MEMBER,
    ADMIN;

    /** Spring Security authority form, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
