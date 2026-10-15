package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.auth.jwt.IssuedToken;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.LockoutService;
import finance.trove.auth.local.TooManyLoginAttemptsException;
import finance.trove.auth.session.RefreshResult;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.households.InstallHousehold;
import finance.trove.households.invites.InviteService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The <em>order</em> of the checks in {@link AuthService#login}, which is the part of it that is
 * security rather than plumbing.
 *
 * <p>{@code LockoutIT#thePerAddressGateRunsBeforeAnyCredentialIsRead} asserts a status code, and a
 * status code cannot see this: once an address is throttled, a login answers 429 whether the gate
 * runs before the argon2id verification or after it. Both orders pass that test, and only one of
 * them is the control that §2.1 describes — "a credential-stuffing run must not get an argon2
 * verification per guess". A hundred milliseconds of memory-hard hashing per guess, handed to an
 * attacker who is already known to be guessing, is a denial-of-service amplifier pointed at the
 * host. This is the test that would fail if somebody moved the gate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final String EMAIL = "member@trove.test";
    private static final String PASSWORD = "a-perfectly-fine-passphrase";
    private static final String IP = "203.0.113.7";

    @Mock private UserRepository users;
    @Mock private InstallHousehold installHousehold;
    @Mock private LocalAccountService accounts;
    @Mock private LockoutService lockout;
    @Mock private TotpService totp;
    @Mock private InviteService invites;
    @Mock private RefreshTokenService sessions;
    @Mock private JwtIssuerService issuer;
    @Mock private JwtVerifier verifier;
    @Mock private JwtProperties jwtProperties;
    @Mock private SecurityEventRecorder securityEvents;

    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, installHousehold, accounts, lockout, totp, invites,
            sessions, issuer, verifier, jwtProperties, securityEvents);
        user = new User(UUID.randomUUID(), UserRole.MEMBER, EMAIL, "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("A throttled address is refused before any credential is read")
    void theAddressGateRunsBeforeTheCredentialIsRead() {
        doThrow(new TooManyLoginAttemptsException(600L))
            .when(lockout).assertAddressNotThrottled(IP, NOW);

        assertThatThrownBy(() -> service.login(EMAIL, PASSWORD, IP, "agent", NOW))
            .isInstanceOf(TooManyLoginAttemptsException.class);

        verify(accounts, never()).authenticate(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(issuer, sessions);
    }

    @Test
    @DisplayName("On the way in, the gate is consulted first and the credential second")
    void theGateIsConsultedBeforeTheCredential() {
        UUID familyId = UUID.randomUUID();
        when(accounts.authenticate(EMAIL, PASSWORD, IP, NOW)).thenReturn(user);
        when(totp.isEnabled(any())).thenReturn(false);
        when(sessions.issue(any(), eq(IP), anyString(), eq(NOW)))
            .thenReturn(new RefreshResult(user.getId(), familyId,
                new IssuedToken("refresh-token", "jti-r", NOW.plusSeconds(3600)), false));
        when(issuer.issueAccessToken(any(), any(), anyString(), any(), eq(familyId), anyBoolean(),
            any(), eq(NOW)))
            .thenReturn(new IssuedToken("access-token", "jti-a", NOW.plusSeconds(900)));

        service.login(EMAIL, PASSWORD, IP, "agent", NOW);

        InOrder order = inOrder(lockout, accounts);
        order.verify(lockout).assertAddressNotThrottled(IP, NOW);
        order.verify(accounts).authenticate(EMAIL, PASSWORD, IP, NOW);
        order.verifyNoMoreInteractions();
    }
}
