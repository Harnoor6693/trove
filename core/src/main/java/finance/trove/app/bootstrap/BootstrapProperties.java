package finance.trove.app.bootstrap;

import java.util.Locale;
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
 * @param country the first ADMIN's country, {@code CA} or {@code US} ({@code TROVE_ADMIN_COUNTRY},
 *     default {@code CA}). It decides the currency too, and neither can be changed afterwards, so
 *     an operator outside Canada has to set it before the first boot. Normalised here but not
 *     validated, for the reason above: {@link FirstRunBootstrap} refuses an unsupported value, and
 *     only on the boot that would use it.
 */
@ConfigurationProperties(prefix = "trove.bootstrap")
public record BootstrapProperties(String email, String password, String householdName,
                                  String country) {

    public BootstrapProperties {
        email = email == null || email.isBlank() ? null : email.strip();
        password = password == null || password.isBlank() ? null : password;
        householdName = householdName == null || householdName.isBlank()
            ? "Household"
            : householdName.strip();
        country = country == null || country.isBlank()
            ? "CA"
            : country.strip().toUpperCase(Locale.ROOT);
    }

    /** Whether the operator supplied both halves of a usable first-run credential. */
    public boolean isComplete() {
        return email != null && password != null;
    }
}
