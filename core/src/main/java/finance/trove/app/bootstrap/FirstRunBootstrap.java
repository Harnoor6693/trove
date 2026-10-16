package finance.trove.app.bootstrap;

import finance.trove.auth.AuthProperties;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.PasswordPolicyException;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.Instants;
import finance.trove.common.time.TimeProperties;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the install's household and its first ADMIN, once, on a boot where {@code users} is
 * empty (readiness gap P2-7).
 *
 * <p>This is the answer to "every install dead-ends before minute five". With Authentik gone there
 * is no external admin source, and registration is closed by default, so without this there is no
 * way to create the first account at all — and opening registration to solve that would let
 * strangers create accounts on a household's finance server, which is the problem, not the fix.
 *
 * <p><strong>It runs exactly once and is decided by data, not by a flag.</strong> The condition is
 * "{@code users} is empty", not a {@code .bootstrapped} marker file and not a property. Leaving
 * {@code TROVE_ADMIN_EMAIL} and {@code TROVE_ADMIN_PASSWORD} in the environment file afterwards is
 * normal and must be harmless; re-applying them on every restart would silently undo a password
 * the owner has since changed, which is a way to lose an account rather than to recover one.
 *
 * <p><strong>It never fails the boot.</strong> A missing or refused credential is logged as an
 * ERROR with the exact remedy and the application starts anyway. The alternative — refusing to
 * start — takes an install that is merely un-onboarded and makes it unreachable, including the
 * {@code /v1/status} endpoint an operator would use to find out what is wrong.
 *
 * <p>An {@code ApplicationRunner} rather than an {@code @EventListener(ContextRefreshedEvent)} so
 * that it runs after the context is fully up, once, and not on a nested or refreshed context.
 */
@Component
public class FirstRunBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FirstRunBootstrap.class);

    private static final String REMEDY =
        "Set TROVE_ADMIN_EMAIL and TROVE_ADMIN_PASSWORD in the environment and restart. "
            + "They are read only while the users table is empty and are ignored afterwards.";

    private final BootstrapProperties bootstrap;
    private final AuthProperties auth;
    private final TimeProperties time;
    private final HouseholdRepository households;
    private final UserRepository users;
    private final LocalAccountService accounts;

    public FirstRunBootstrap(BootstrapProperties bootstrap, AuthProperties auth,
                             TimeProperties time, HouseholdRepository households,
                             UserRepository users, LocalAccountService accounts) {
        this.bootstrap = bootstrap;
        this.auth = auth;
        this.time = time;
        this.households = households;
        this.users = users;
        this.accounts = accounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            bootstrapIfEmpty(Instants.now());
        } catch (PasswordPolicyException e) {
            // swallow-ok: the operator's chosen admin password is too weak. Refusing to start
            // would make an un-onboarded install unreachable, including the status endpoint they
            // would use to find out why. The credential is refused after the household and
            // ADMIN rows are already committed (bootstrapIfEmpty runs on this, so its
            // @Transactional does not apply). The install boots with an administrator who has
            // no password, and a restart will not retry because the users table is no longer
            // empty. Recover with the console: --trove.cli.command="reset-password <email>"
            // (LocalAccountService.setPassword creates the missing credential on redemption).
            LOG.error("TROVE_ADMIN_PASSWORD was refused by the password policy: {}. {}",
                e.getMessage(), REMEDY);
        }
    }

    /** Package-private so the test can drive it without an application context. */
    @Transactional
    void bootstrapIfEmpty(Instant now) {
        if (users.countAllUsers() > 0) {
            LOG.debug("Install already has users; first-run bootstrap is a no-op");
            return;
        }
        if (!bootstrap.isComplete()) {
            LOG.error("This install has no accounts and cannot create one: registration is "
                + "closed and there is no first administrator. {}", REMEDY);
            return;
        }

        Jurisdiction country = countryOf(bootstrap.country());
        if (country == null) {
            LOG.error("TROVE_ADMIN_COUNTRY must be CA or US, so no first administrator was "
                + "created. Fix it in the environment and restart; it is read only while the users "
                + "table is empty.");
            return;
        }

        String email = bootstrap.email().strip().toLowerCase(Locale.ROOT);
        Household household = households.save(
            new Household(bootstrap.householdName(), auth.registrationOpen()));

        // ADMIN, in the operator's TROVE_ADMIN_COUNTRY (default CA), with that country's currency.
        // NOTHING CHANGES EITHER AFTERWARDS: Settings shows them read-only, because every stored
        // amount is denominated in them. That is why this is an environment variable read before
        // the first boot, and why it is not guessed from the container's locale, which differs
        // between the machine that built the image and the one that runs it.
        User admin = new User(household.getId(), UserRole.ADMIN, email,
            displayNameFor(email), country, country.defaultCurrency(), defaultZone());
        users.save(admin);
        accounts.createCredential(admin.getId(), bootstrap.password(), email, now);

        LOG.info("First-run bootstrap complete: household '{}' created with administrator {}. "
                + "Sign in at {} and change this password.",
            household.getName(), email, auth.publicUrl());
    }

    /**
     * A first display name derived from the address' local part.
     *
     * <p>There is no {@code TROVE_ADMIN_NAME} variable and deliberately so: it would be a third
     * thing to get right in an environment file to make a value the owner changes in the first
     * minute of using the application.
     */
    private static String displayNameFor(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String cleaned = local.replace('.', ' ').replace('_', ' ').replace('-', ' ').strip();
        if (cleaned.isEmpty()) {
            return "Administrator";
        }
        String truncated = cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
        return Character.toUpperCase(truncated.charAt(0)) + truncated.substring(1);
    }

    /** {@code CA} or {@code US}, or null for anything else (owner decision: those two only). */
    private static Jurisdiction countryOf(String code) {
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            if (jurisdiction.name().equals(code)) {
                return jurisdiction;
            }
        }
        return null;
    }

    private ZoneId defaultZone() {
        try {
            return ZoneId.of(time.defaultTimezone());
        } catch (DateTimeException e) {
            LOG.warn("trove.time.default-timezone is not a recognised zone; using UTC");
            return ZoneId.of("UTC");
        }
    }
}
