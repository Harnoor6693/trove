package finance.trove.households.invites;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * An invitation could not be used.
 *
 * <p>Three distinct slugs, unlike the login path's single one, because the audience is different:
 * whoever holds the link is already meant to have it, so telling them "this expired" rather than
 * "this is not valid" costs nothing and saves a support conversation. The web client renders a
 * different message per slug.
 */
public class InviteException extends TroveException {
    // Status codes here are dictated by the clients, which were written first and dispatch this
    // family on the HTTP status rather than on the slug: apps/web/src/pages/Login/errorCopy.ts:71
    // maps 404 and 410 — and only those two — onto the "that invite link is no longer valid"
    // copy. A 400, which is what a naive reading of "the token is invalid" would produce, falls
    // through to the generic "Trove is having trouble on its end" message, which blames the
    // server for the user's expired link. 404 = we do not have this token; 410 = we had it and
    // it is spent.

    private static final long serialVersionUID = 1L;

    private InviteException(String message, HttpStatus status, String slug) {
        super(message, status, slug);
    }

    /** No such invitation, already used, or withdrawn. */
    public static InviteException invalid() {
        return new InviteException(
            "This invitation link is not valid. It may already have been used or withdrawn.",
            HttpStatus.NOT_FOUND, "invite-invalid");
    }

    public static InviteException expired() {
        return new InviteException(
            "This invitation link has expired. Ask a household admin for a new one.",
            HttpStatus.GONE, "invite-expired");
    }

    /** The invitation names an address and registration used a different one. */
    /**
     * The invite is bound to one address and a different one was offered.
     *
     * <p>Answered as 404 with the same slug and the same wording as an unknown token, on purpose:
     * a distinct "this invite is for someone else" response would confirm to whoever holds a
     * leaked link which address it was issued to, which is exactly the fact the binding exists to
     * protect.
     */
    public static InviteException wrongEmail() {
        return new InviteException(
            "This invitation link is not valid. It may already have been used or withdrawn.",
            HttpStatus.NOT_FOUND, "invite-invalid");
    }

    /** Already redeemed — the token existed, so 410 rather than 404 (§1.5, single-use). */
    public static InviteException alreadyUsed() {
        return new InviteException(
            "This invitation link has already been used. Ask a household admin for a new one.",
            HttpStatus.GONE, "invite-expired");
    }
}
