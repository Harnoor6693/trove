package finance.trove.app.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.bootstrap.*} — the first-run admin of readiness gap P2-7.
 *
 * <p>These are read exactly once, on a boot where {@code users} is empty, and ignored for ever
 * afterwards. That is deliberate: leaving them in the environment file is normal and must not
 * mean the password is re-applied on every restart, which would silently undo a password the
 * owner has since changed.
 *
 * <p>Nothing here is validated in the constructor. An install that has already been bootstrapped
 * has no admin credentials in its environment at all, and failing the boot over that would make
 * the fix ("delete two lines from .env") into an outage. {@link FirstRunBootstrap} decides, and
 * it only complains when the values are actually needed.
 *
 * @param email         the first ADMIN's address
 * @param password      the first ADMIN's password; subject to the same policy as any other
 * @param householdName the name of the install's one household
 */
@ConfigurationProperties(prefix = "trove.bootstrap")
public record BootstrapProperties(String email, String password, String householdName) {

    public BootstrapProperties {
        email = email == null || email.isBlank() ? null : email.strip();
        password = password == null || password.isBlank() ? null : password;
        householdName = householdName == null || householdName.isBlank()
            ? "Household"
            : householdName.strip();
    }

    /** Whether the operator supplied both halves of a usable first-run credential. */
    public boolean isComplete() {
        return email != null && password != null;
    }
}
