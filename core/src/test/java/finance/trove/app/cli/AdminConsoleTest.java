package finance.trove.app.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.auth.dto.PasswordResetLinkResponse;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.reset.PasswordResetService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.TroveException;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * The console escape hatch (§1.5, gap P2-7): the only recovery path that does not need an
 * administrator who can already sign in.
 *
 * <p>Everything the API offers — clearing a member's second factor, minting a reset link,
 * unlocking an account — needs an authenticated ADMIN. On the expected v1 topology, one household
 * with one administrator, that administrator losing their phone is the case nobody can serve, and
 * {@code FirstRunBootstrap} does not help because {@code users} is not empty. Until these commands
 * existed the honest answer was "edit the database", while {@code PasswordResetService}'s javadoc
 * asserted the CLI already existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminConsoleTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String EMAIL = "admin@trove.test";

    @Mock private UserRepository users;
    @Mock private PasswordResetService resets;
    @Mock private TotpService totp;
    @Mock private LocalAccountService accounts;
    @Mock private UserClock clock;

    private AdminConsole console;
    private final List<String> printed = new ArrayList<>();
    private User user;

    @BeforeEach
    void setUp() {
        console = new AdminConsole(users, resets, totp, accounts, clock);
        when(clock.now()).thenReturn(NOW);
        user = new User(UUID.randomUUID(), UserRole.ADMIN, EMAIL, "Admin",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
    }

    private int run(String commandLine) {
        return console.execute(commandLine, printed::add);
    }

    @Test
    @DisplayName("reset-password prints a link the locked-out administrator can open")
    void resetPasswordPrintsTheLink() {
        when(resets.issueFromConsole(EMAIL, NOW)).thenReturn(new PasswordResetLinkResponse(
            "https://trove.example/reset-password/a-token", NOW.plusSeconds(3600)));

        assertThat(run("reset-password " + EMAIL)).isEqualTo(AdminConsole.SUCCESS);

        assertThat(printed)
            .as("the link is the whole output of the command; printed, never logged, because it "
                + "is a credential and a log shipper keeps it")
            .anyMatch(line -> line.contains("https://trove.example/reset-password/a-token"));
        verify(resets).issueFromConsole(EMAIL, NOW);
    }

    @Test
    @DisplayName("An address that matches no account is a sentence and an exit code, not a stack")
    void resetPasswordForAnUnknownAddress() {
        when(resets.issueFromConsole(EMAIL, NOW)).thenThrow(new TroveException(
            "No account on this install uses that email address.",
            HttpStatus.NOT_FOUND, "not-found"));

        assertThat(run("reset-password " + EMAIL)).isEqualTo(AdminConsole.FAILURE);
        assertThat(printed).anyMatch(line -> line.contains("No account on this install"));
    }

    @Test
    @DisplayName("disable-totp removes the factor and records no actor")
    void disableTotpClearsTheFactor() {
        when(totp.isEnabled(user.getId())).thenReturn(true);

        assertThat(run("disable-totp " + EMAIL)).isEqualTo(AdminConsole.SUCCESS);

        // Null, deliberately: "somebody at the console did this". Naming the subject as the actor
        // would make it indistinguishable from a self-service disable, which is the whole
        // distinction security_event.actor_id exists to record.
        verify(totp).disable(eq(user.getId()), eq(null));
    }

    @Test
    @DisplayName("disable-totp on an account with no factor changes nothing and still succeeds")
    void disableTotpIsIdempotent() {
        when(totp.isEnabled(user.getId())).thenReturn(false);

        assertThat(run("disable-totp " + EMAIL))
            .as("an operator recovering an account should not have to find out first")
            .isEqualTo(AdminConsole.SUCCESS);
        verify(totp, never()).disable(any(), any());
    }

    @Test
    @DisplayName("unlock clears the lock without touching the password")
    void unlockClearsTheLock() {
        assertThat(run("unlock " + EMAIL)).isEqualTo(AdminConsole.SUCCESS);

        verify(accounts).unlock(user.getId());
        verifyNoInteractions(resets);
    }

    @Test
    @DisplayName("An address nobody has is refused for every account-scoped command")
    void unknownAddressesAreRefused() {
        when(users.findByEmailIgnoreCase("ghost@trove.test")).thenReturn(Optional.empty());

        assertThat(run("disable-totp ghost@trove.test")).isEqualTo(AdminConsole.FAILURE);
        assertThat(run("unlock ghost@trove.test")).isEqualTo(AdminConsole.FAILURE);
        verify(totp, never()).disable(any(), any());
        verify(accounts, never()).unlock(any());
    }

    @Test
    @DisplayName("A command that is not one of the three prints the usage and fails")
    void unknownCommandsPrintUsage() {
        assertThat(run("promote " + EMAIL)).isEqualTo(AdminConsole.FAILURE);
        assertThat(printed)
            .as("there is deliberately no command that creates or elevates an account: the worst "
                + "a mistyped one can do is invalidate an unused reset link")
            .anyMatch(line -> line.contains("reset-password"));
        verifyNoInteractions(resets, totp, accounts);
    }

    @Test
    @DisplayName("A command with no address, and no command at all, both fail cleanly")
    void degenerateCommandLines() {
        assertThat(run("reset-password")).isEqualTo(AdminConsole.FAILURE);
        assertThat(run("")).isEqualTo(AdminConsole.FAILURE);
        assertThat(run(null)).isEqualTo(AdminConsole.FAILURE);
        verifyNoInteractions(resets, totp, accounts);
    }

    @Test
    @DisplayName("Extra whitespace in the property value is tolerated")
    void whitespaceIsTolerated() {
        when(resets.issueFromConsole(EMAIL, NOW)).thenReturn(new PasswordResetLinkResponse(
            "https://trove.example/reset-password/a-token", NOW.plusSeconds(3600)));

        assertThat(run("  reset-password   " + EMAIL + "  ")).isEqualTo(AdminConsole.SUCCESS);
    }
}
