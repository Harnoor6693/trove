package finance.trove.app.audit;

/**
 * The closed set of {@code security_event.type} values (gap P2-17; schema V9).
 *
 * <p>These are the events an operator needs after an incident — "when did this account last log
 * in, from where, and did anyone remove its second factor?" — kept separate from the general
 * {@code audit_log} of HTTP requests (collapse step 4) because they are not requests: a lockout
 * and a detected token reuse are conclusions, not calls.
 *
 * <p>Names match the CHECK constraint in {@code V9__security_event.sql} exactly; adding a value
 * here without adding it there fails the insert, which is the intended coupling.
 */
public enum SecurityEventType {

    /** A completed login, both factors where TOTP is enabled. */
    LOGIN_OK,

    /** A rejected login. The row never records which half was wrong. */
    LOGIN_FAIL,

    /** An account crossed the failure threshold and is now soft-locked (gap P2-5). */
    LOCKOUT,

    /** TOTP moved from enrolled-but-unconfirmed to enabled. */
    TOTP_ENROL,

    /** TOTP was removed — by the owner with a step-up, or by an admin. {@code actorId} says. */
    TOTP_DISABLE,

    /** The owner changed their own password. */
    PASSWORD_CHANGE,

    /** A password was set through a reset link rather than by the owner knowing the old one. */
    PASSWORD_RESET,

    /** A session was deliberately ended: logout, "sign out other devices", admin revoke. */
    SESSION_REVOKE,

    /**
     * A rotated refresh token came back outside the grace window. The family is revoked; this is
     * the row that says a token was replayed rather than merely expired (§1.5).
     */
    REFRESH_REUSE_DETECTED,

    /** A bank connection was added (collapse step 6). */
    BANK_CONNECTION_ADD,

    /** A bank connection was removed (collapse step 6). */
    BANK_CONNECTION_REMOVE,

    /** A full data export was produced (collapse step 18). */
    EXPORT,

    /** An account entered the soft-delete grace period (collapse step 18). */
    ACCOUNT_DELETE,

    /** Reserved by §3's CHECK list; superseded here by the finer INVITE_* pair. */
    ADMIN_INVITE,

    /** An admin minted an invite link. */
    INVITE_CREATED,

    /** An invite link was redeemed into a new member. */
    INVITE_ACCEPTED,

    /** An encryption or signing key was rotated (collapse step 12+). */
    KEY_ROTATE,

    /** A user changed which outbound targets their AI endpoint may reach (collapse step 12). */
    EGRESS_SETTING_CHANGE
}
