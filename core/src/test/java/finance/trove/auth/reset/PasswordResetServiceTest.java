package finance.trove.auth.reset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.dto.PasswordResetLinkResponse;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.PasswordPolicyException;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.TroveException;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
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

/**
 * Readiness gap P2-8: a reset path that does not need a mail server.
 *
 * <p>Most self-hosters have no SMTP, so "forgot password" cannot be an email. It is an admin
 * generating a one-hour single-use link and handing it over out of band. The three properties this
 * class exists to guarantee are that issuing a second link kills the first, that redeeming ends
 * every session, and that the new password still goes through the ordinary policy.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private PasswordResetTokenRepository tokens;

    @Mock
    private UserRepository users;

    @Mock
    private LocalAccountService accounts;

    @Mock
    private RefreshTokenService sessions;

    @Mock
    private SecurityEventRecorder securityEvents;

    private final Map<String, PasswordResetToken> byHash = new HashMap<>();
    private PasswordResetService service;
    private User user;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(tokens, users, accounts, sessions,
            new AuthProperties(false, "https://trove.example.com"), securityEvents);
        byHash.clear();
        user = new User(UUID.randomUUID(), UserRole.MEMBER, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
        setId(user);
        when(users.findByEmailIgnoreCase("member@trove.test")).thenReturn(Optional.of(user));
        when(users.findAccount(user.getId())).thenReturn(Optional.of(user));
        when(tokens.save(any(PasswordResetToken.class))).thenAnswer(invocation -> {
            PasswordResetToken token = invocation.getArgument(0);
            byHash.put(token.getTokenHash(), token);
            return token;
        });
        when(tokens.findByTokenHashForUpdate(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(byHash.get(invocation.getArgument(0))));
    }

    private static void setId(User user) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private String issueToken() {
        PasswordResetLinkResponse response =
            service.issue("member@trove.test", adminId, user.getHouseholdId(), NOW);
        return response.url().substring(response.url().lastIndexOf('/') + 1);
    }

    @Test
    @DisplayName("Issuing returns an absolute one-hour link and stores only a hash")
    void issueReturnsALinkAndStoresAHash() {
        PasswordResetLinkResponse response =
            service.issue("member@trove.test", adminId, user.getHouseholdId(), NOW);

        assertThat(response.url()).startsWith("https://trove.example.com/reset-password/");
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(byHash.keySet()).singleElement()
            .satisfies(hash -> assertThat(hash).hasSize(64));
        assertThat(byHash.values().iterator().next().getCreatedBy()).isEqualTo(adminId);
        verify(securityEvents)
            .record(eq(SecurityEventType.PASSWORD_RESET), eq(user.getId()), eq(adminId), any());
    }

    @Test
    @DisplayName("Issuing consumes any earlier outstanding link for that account")
    void issuingInvalidatesEarlierLinks() {
        service.issue("member@trove.test", adminId, user.getHouseholdId(), NOW);

        verify(tokens)
            .consumeAllForUser(user.getId(), NOW);
    }

    @Test
    @DisplayName("An address with no account is a 404 — this route is admin-only, not public")
    void unknownAddressIsNotFound() {
        when(users.findByEmailIgnoreCase("nobody@trove.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.issue("nobody@trove.test", adminId, user.getHouseholdId(), NOW))
            .as("pretending to succeed here would mean an admin pasting a dead link to somebody "
                + "and neither of them knowing why")
            .isInstanceOf(TroveException.class)
            .satisfies(thrown ->
                assertThat(((TroveException) thrown).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("An address in ANOTHER household is 404 — an admin is not a tenancy bypass")
    void anotherHouseholdsMemberIsNotFound() {
        assertThatThrownBy(() ->
            service.issue("member@trove.test", adminId, UUID.randomUUID(), NOW))
            .as("resolving by email across the whole install would let an admin of one household "
                + "mint a working reset link for a member of another (§9 risk 2)")
            .isInstanceOf(TroveException.class)
            .satisfies(thrown ->
                assertThat(((TroveException) thrown).getStatus().value()).isEqualTo(404));
    }

    @Test
    @DisplayName("Redeeming sets the password, spends the token and ends every session")
    void redeemResetsAndRevokes() {
        String raw = issueToken();
        when(sessions.revokeAllForUser(any(), anyString(), any())).thenReturn(3);

        service.redeem(raw, "a-perfectly-fine-passphrase", NOW.plusSeconds(60));

        verify(accounts).setPassword(user.getId(), "a-perfectly-fine-passphrase",
            "member@trove.test", NOW.plusSeconds(60));
        verify(accounts).unlock(user.getId());
        verify(sessions).revokeAllForUser(user.getId(), "password-reset", NOW.plusSeconds(60));
        assertThat(byHash.values().iterator().next().getConsumedAt()).isNotNull();
    }

    @Test
    @DisplayName("A link is single-use: the second attempt is refused")
    void linksAreSingleUse() {
        String raw = issueToken();
        service.redeem(raw, "a-perfectly-fine-passphrase", NOW.plusSeconds(60));

        assertThatThrownBy(() -> service.redeem(raw, "another-fine-passphrase", NOW.plusSeconds(90)))
            .isInstanceOf(TroveException.class)
            .hasMessageContaining("not valid");
    }

    @Test
    @DisplayName("An expired link is refused, with the same wording as an unknown one")
    void expiredLinksAreRefused() {
        String raw = issueToken();

        assertThatThrownBy(() ->
            service.redeem(raw, "a-perfectly-fine-passphrase", NOW.plus(Duration.ofHours(2))))
            .isInstanceOf(TroveException.class)
            .hasMessage(unknownTokenMessage());
    }

    @Test
    @DisplayName("An unknown token is refused without revealing that it is unknown")
    void unknownTokensAreRefused() {
        assertThatThrownBy(() ->
            service.redeem("never-issued", "a-perfectly-fine-passphrase", NOW))
            .isInstanceOf(TroveException.class)
            .hasMessage(unknownTokenMessage());
    }

    @Test
    @DisplayName("A refused new password leaves the link usable for a second attempt")
    void aRefusedPasswordDoesNotBurnTheLink() {
        String raw = issueToken();
        org.mockito.Mockito.doThrow(new PasswordPolicyException("too short"))
            .when(accounts).setPassword(any(), eq("short"), anyString(), any());

        assertThatThrownBy(() -> service.redeem(raw, "short", NOW.plusSeconds(10)))
            .isInstanceOf(PasswordPolicyException.class);

        assertThat(byHash.values().iterator().next().getConsumedAt())
            .as("burning the link on a policy failure would force the member to ask for another")
            .isNull();
        verify(sessions, never()).revokeAllForUser(any(), anyString(), any());
    }

    @Test
    @DisplayName("Hashing is SHA-256 hex, matching the CHAR(64) column of V9.1")
    void hashing() {
        assertThat(PasswordResetService.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private static String unknownTokenMessage() {
        return "This password reset link is not valid. It may have expired or already been used.";
    }
}
