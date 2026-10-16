package finance.trove.app.cli;

import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.dto.PasswordResetLinkResponse;
import finance.trove.auth.reset.PasswordResetService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.exception.TroveException;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * The escape hatch for an administrator who cannot get in at all — plan §1.5's
 * {@code trove admin disable-totp <email>} and {@code trove admin reset-password <email>}, and
 * readiness gap P2-7's headless equivalent.
 *
 * <p><strong>Why it has to exist.</strong> Every recovery path the API offers needs an
 * authenticated ADMIN session: an admin clears a member's second factor, an admin mints a reset
 * link, an admin unlocks an account. The expected v1 topology is a household with exactly one
 * administrator, and that administrator losing their phone or their password is the one case
 * where nobody can perform any of those. {@code FirstRunBootstrap} will not help either — it runs
 * only while {@code users} is empty, and the household's members are still there. Without these
 * commands the answer is "edit the database", which is the answer this project exists to avoid.
 *
 * <p><strong>What authorises it.</strong> Shell access to the server, and nothing else. That is
 * deliberate: it is a strictly stronger credential than any session — whoever has it can read the
 * database directly — so requiring a password here would protect nothing and would fail in the
 * exact situation the commands are for. It is also why the equivalent actions are refused on
 * {@code /v1/admin/users/{id}/totp} when an admin aims them at their own account: over HTTP a
 * stolen bearer token would be enough, and here it is not enough to be anywhere near the machine.
 *
 * <p><strong>How it runs.</strong> One property, checked by {@link AdminConsoleRunner}:
 *
 * <pre>{@code
 *   cd deploy && docker compose run --rm trove-core --trove.cli.command="reset-password admin@example.com"
 * }</pre>
 *
 * <p>Run it from {@code deploy/}, where {@code docker-compose.yml} lives; the compose service is
 * {@code trove-core}.
 *
 * <p>Without {@code trove.cli.command} the bean does nothing at all and the application boots
 * normally — the runner is what is conditional, so an ordinary boot cannot be talked into
 * executing a command by a stray request. Every command is idempotent and none of them can create
 * or elevate an account: the worst a mistyped one can do is invalidate an unused reset link.
 * Creating the first administrator stays with {@code FirstRunBootstrap} and its environment
 * variables, which already covers the headless first boot P2-7 asks about.
 */
@Component
public class AdminConsole {

    /** What {@link #execute} answers when it could not do what it was asked. */
    public static final int FAILURE = 1;

    public static final int SUCCESS = 0;

    private static final String USAGE = """
        Usage: --trove.cli.command="<command> <email>"

          reset-password <email>   print a one-hour, single-use password reset link
          disable-totp <email>     remove the account's second factor
          unlock <email>           clear a lockout without changing the password
        """;

    private final UserRepository users;
    private final PasswordResetService resets;
    private final TotpService totp;
    private final LocalAccountService accounts;
    private final UserClock clock;

    public AdminConsole(UserRepository users, PasswordResetService resets, TotpService totp,
                        LocalAccountService accounts, UserClock clock) {
        this.users = users;
        this.resets = resets;
        this.totp = totp;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Runs one command.
     *
     * <p>Written to take its output as a {@link Consumer} rather than writing to
     * {@code System.out} so that the whole surface is testable without capturing a stream, and so
     * that a future caller could send it somewhere else.
     *
     * @param commandLine the raw value of {@code trove.cli.command}
     * @param out         where each line of output goes
     * @return a process exit code: 0 when the command did what it said, 1 otherwise
     */
    public int execute(String commandLine, Consumer<String> out) {
        List<String> words = split(commandLine);
        if (words.isEmpty()) {
            out.accept(USAGE);
            return FAILURE;
        }
        String command = words.get(0).toLowerCase(Locale.ROOT);
        String email = words.size() > 1 ? words.get(1) : null;
        if (email == null || email.isBlank()) {
            out.accept("This command needs an email address.");
            out.accept(USAGE);
            return FAILURE;
        }

        return switch (command) {
            case "reset-password" -> resetPassword(email, out);
            case "disable-totp" -> disableTotp(email, out);
            case "unlock" -> unlock(email, out);
            default -> {
                out.accept("Unknown command: " + command);
                out.accept(USAGE);
                yield FAILURE;
            }
        };
    }

    private int resetPassword(String email, Consumer<String> out) {
        // Resolved by the service, install-wide: a console caller has no household, and the
        // account they are recovering may be the only administrator of the only household.
        PasswordResetLinkResponse link;
        try {
            link = resets.issueFromConsole(email, clock.now());
        } catch (TroveException e) {
            // The service speaks HTTP because its other caller is a controller. Here the operator
            // wants a sentence and an exit code, not a status.
            out.accept(e.getMessage());
            return FAILURE;
        }
        out.accept("Password reset link for " + email + " (valid until " + link.expiresAt() + "):");
        out.accept(link.url());
        out.accept("Anyone holding this link can set that account's password. It is single-use "
            + "and any earlier link for the account has just been invalidated.");
        return SUCCESS;
    }

    private int disableTotp(String email, Consumer<String> out) {
        Optional<User> user = users.findByEmailIgnoreCase(email);
        if (user.isEmpty()) {
            out.accept("No account on this install uses " + email + ".");
            return FAILURE;
        }
        if (!totp.isEnabled(user.get().getId())) {
            out.accept(email + " has no second factor enrolled; nothing to remove.");
            return SUCCESS;
        }
        // actorId null: "somebody at the console did this". Attributing it to the subject would
        // be indistinguishable from a self-service disable, which is the distinction
        // security_event.actor_id exists to preserve.
        totp.disable(user.get().getId(), null);
        out.accept("Removed the second factor from " + email
            + ". They can sign in with their password alone and re-enrol from Settings.");
        return SUCCESS;
    }

    private int unlock(String email, Consumer<String> out) {
        Optional<User> user = users.findByEmailIgnoreCase(email);
        if (user.isEmpty()) {
            out.accept("No account on this install uses " + email + ".");
            return FAILURE;
        }
        accounts.unlock(user.get().getId());
        out.accept("Cleared the lockout on " + email + ". Their password is unchanged.");
        return SUCCESS;
    }

    private static List<String> split(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }
        return List.of(commandLine.strip().split("\\s+"));
    }
}
