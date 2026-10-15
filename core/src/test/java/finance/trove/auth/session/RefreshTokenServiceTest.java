package finance.trove.auth.session;

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
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.common.exception.UnauthorizedException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
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
 * Rotation, the reuse-grace window, and family revocation (§1.5).
 *
 * <p>The grace window is the part that looks like a weakening and is not. Without it, two browser
 * tabs that both hit a 401 and both refresh — or one request that is retried — present the same
 * token twice, which is indistinguishable from theft, and the household is logged out for using
 * the application normally. Both adversarial reviewers of the plan found that independently. With
 * it, a genuine replay outside thirty seconds still burns the whole family.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceTest {

    private static final String SECRET = "refresh-service-test-secret-32-chars++";
    /**
     * The instant every case below counts from.
     *
     * <p><strong>Read from the real clock, not a literal, and that is load-bearing.</strong> It
     * was written as {@code Instant.parse("2026-09-04T12:00:00Z")} when this test was authored.
     * The tokens {@code issue} mints are real signed JWTs whose {@code exp} is {@code NOW} plus
     * the 24-hour refresh TTL, and {@link JwtVerifier} checks {@code exp} against the wall clock —
     * so from 2026-09-05T12:00Z onward every token this fixture minted was already expired before
     * the assertion ran, and six of these cases began failing on a build that had changed nothing.
     * Every case uses {@code NOW} only for relative arithmetic ({@code plusSeconds},
     * {@code plus(Duration)}), so anchoring it to the present keeps each of them meaning exactly
     * what it meant and stops the suite depending on the date it is run.
     */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    @Mock
    private RefreshTokenRepository tokens;

    @Mock
    private SecurityEventRecorder securityEvents;

    private final JwtProperties jwtProperties =
        new JwtProperties(SECRET, "k1", "trove-core", 900, 86_400, 60, 300);
    private final JwtIssuerService issuer = new JwtIssuerService(jwtProperties);
    private final JwtVerifier verifier = new JwtVerifier(jwtProperties);
    private final RefreshProperties refreshProperties =
        new RefreshProperties(Duration.ofHours(12), Duration.ofSeconds(30));

    private final Map<String, RefreshToken> store = new HashMap<>();
    private RefreshTokenService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(tokens, issuer, verifier, jwtProperties,
            refreshProperties, securityEvents, new SessionRevoker(tokens));
        store.clear();
        when(tokens.save(any(RefreshToken.class))).thenAnswer(invocation -> {
            RefreshToken token = invocation.getArgument(0);
            assignIdIfAbsent(token);
            store.put(token.getTokenHash(), token);
            return token;
        });
        when(tokens.findByTokenHash(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        // Rotation takes the row lock rather than a plain read, so two tabs converge on one
        // successor instead of forking the family (see findByTokenHashForUpdate).
        when(tokens.findByTokenHashForUpdate(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(tokens.revokeFamily(any(UUID.class), any(Instant.class))).thenAnswer(invocation -> {
            UUID familyId = invocation.getArgument(0);
            Instant at = invocation.getArgument(1);
            int revoked = 0;
            for (RefreshToken token : store.values()) {
                if (token.getFamilyId().equals(familyId) && !token.isRevoked()) {
                    token.revoke(at);
                    revoked++;
                }
            }
            return revoked;
        });
    }

    /** The database assigns the id; the in-memory double has to do the same or replaceWith NPEs. */
    private static void assignIdIfAbsent(RefreshToken token) {
        try {
            Field id = RefreshToken.class.getDeclaredField("id");
            id.setAccessible(true);
            if (id.get(token) == null) {
                id.set(token, UUID.randomUUID());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Issuing opens a family and stores only a hash of the token")
    void issueStoresOnlyAHash() {
        RefreshResult result = service.issue(userId, "203.0.113.4", "curl/8", NOW);

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.familyId()).isNotNull();
        assertThat(result.replayed()).isFalse();
        RefreshToken stored = store.get(RefreshTokenService.hash(result.token().value()));
        assertThat(stored).isNotNull();
        assertThat(stored.getTokenHash())
            .as("the raw token exists in the response and the client's keystore, nowhere else")
            .isNotEqualTo(result.token().value())
            .hasSize(64);
        assertThat(stored.getIpAddress()).isEqualTo("203.0.113.4");
    }

    @Test
    @DisplayName("Rotation mints a successor in the same family and marks the old one replaced")
    void rotationKeepsTheFamily() {
        RefreshResult first = service.issue(userId, null, null, NOW);

        RefreshResult second = service.rotate(first.token().value(), null, null, NOW.plusSeconds(1));

        assertThat(second.familyId()).isEqualTo(first.familyId());
        assertThat(second.token().value()).isNotEqualTo(first.token().value());
        assertThat(second.replayed()).isFalse();
        assertThat(store.get(RefreshTokenService.hash(first.token().value())).isRotated()).isTrue();
    }

    @Test
    @DisplayName("A successor never outlives the family's original deadline")
    void rotationInheritsTheAbsoluteDeadline() {
        RefreshResult first = service.issue(userId, null, null, NOW);
        Instant deadline = first.token().expiresAt();

        RefreshResult second =
            service.rotate(first.token().value(), null, null, NOW.plus(Duration.ofHours(6)));

        assertThat(second.token().expiresAt())
            .as("refreshing forever must not outlive the ceiling")
            .isBeforeOrEqualTo(deadline);
    }

    @Test
    @DisplayName("Two tabs racing inside the grace window get the SAME successor, not a revoke")
    void concurrentRefreshInsideTheGraceWindowConverges() {
        RefreshResult first = service.issue(userId, null, null, NOW);

        RefreshResult tabOne =
            service.rotate(first.token().value(), null, null, NOW.plusSeconds(1));
        RefreshResult tabTwo =
            service.rotate(first.token().value(), null, null, NOW.plusSeconds(2));

        assertThat(tabTwo.token().value())
            .as("two tabs must converge on one token rather than forking the family")
            .isEqualTo(tabOne.token().value());
        assertThat(tabTwo.replayed()).isTrue();
        verify(securityEvents, never())
            .record(eq(SecurityEventType.REFRESH_REUSE_DETECTED), any(), any());
        // The predecessor is legitimately marked revoked by the rotation itself (replaceWith
        // sets revokedAt). What must NOT have happened is the family being burnt, so the
        // assertion is on the successor both tabs are now holding.
        assertThat(store.get(RefreshTokenService.hash(tabOne.token().value())).isRevoked())
            .as("the token both tabs hold has to keep working")
            .isFalse();
    }

    @Test
    @DisplayName("Replaying a rotated token after the grace window burns the whole family")
    void reuseOutsideTheGraceWindowRevokesTheFamily() {
        RefreshResult first = service.issue(userId, null, null, NOW);
        RefreshResult second =
            service.rotate(first.token().value(), null, null, NOW.plusSeconds(1));

        assertThatThrownBy(() ->
            service.rotate(first.token().value(), null, null, NOW.plusSeconds(120)))
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("Invalid or expired session");

        assertThat(store.get(RefreshTokenService.hash(second.token().value())).isRevoked())
            .as("nobody honest still holds the old token, so the successor is compromised too")
            .isTrue();
        verify(securityEvents)
            .record(eq(SecurityEventType.REFRESH_REUSE_DETECTED), eq(userId), any());
    }

    @Test
    @DisplayName("A token this install never issued is refused without touching any family")
    void unknownTokenIsRefused() {
        String foreign = issuer.issueRefreshToken(userId, NOW, Duration.ofHours(1)).value();

        assertThatThrownBy(() -> service.rotate(foreign, null, null, NOW))
            .isInstanceOf(UnauthorizedException.class);
        verify(tokens, never()).revokeFamily(any(), any());
    }

    @Test
    @DisplayName("An access token presented at the refresh endpoint is refused on its type")
    void accessTokenIsNotARefreshToken() {
        String access = issuer.issueAccessToken(userId, UUID.randomUUID(), "a@b.test",
            finance.trove.users.UserRole.MEMBER, UUID.randomUUID(), false, null, NOW).value();

        assertThatThrownBy(() -> service.rotate(access, null, null, NOW))
            .isInstanceOf(UnauthorizedException.class);
        verify(tokens, never()).findByTokenHash(anyString());
    }

    @Test
    @DisplayName("A session unused past the idle timeout dies inside its absolute lifetime")
    void idleSessionsExpire() {
        RefreshResult issued = service.issue(userId, null, null, NOW);
        Instant thirteenHoursLater = NOW.plus(Duration.ofHours(13));

        assertThatThrownBy(() ->
            service.rotate(issued.token().value(), null, null, thirteenHoursLater))
            .as("a laptop closed at lunchtime should not still hold a working session at "
                + "midnight merely because the 24-hour absolute clock had not run out")
            .isInstanceOf(UnauthorizedException.class);
        assertThat(store.get(RefreshTokenService.hash(issued.token().value())).isRevoked())
            .isTrue();
    }

    @Test
    @DisplayName("A session used within the idle window keeps rotating")
    void activeSessionsSurvive() {
        RefreshResult first = service.issue(userId, null, null, NOW);
        RefreshResult second = service.rotate(first.token().value(), null, null,
            NOW.plus(Duration.ofHours(11)));

        assertThat(second.replayed()).isFalse();
        assertThat(second.familyId()).isEqualTo(first.familyId());
    }

    @Test
    @DisplayName("A malformed token is refused with the SESSION wording, not the token wording")
    void malformedTokensUseTheSessionWording() {
        assertThatThrownBy(() -> service.rotate("not-a-jwt-at-all", null, null, NOW))
            .as("the verifier says 'token' and everything else says 'session'; a caller that can "
                + "tell them apart learns which half of a guess was wrong")
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("Invalid or expired session");
    }

    @Test
    @DisplayName("Every refusal says exactly the same thing")
    void refusalsAreIndistinguishable() {
        RefreshResult issued = service.issue(userId, null, null, NOW);
        store.get(RefreshTokenService.hash(issued.token().value())).revoke(NOW);

        assertThatThrownBy(() -> service.rotate(issued.token().value(), null, null, NOW))
            .as("the caller must not learn whether a token was unknown, revoked, expired or "
                + "replayed")
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("Invalid or expired session");
    }

    @Test
    @DisplayName("Logout revokes the family and is a no-op for an unknown token")
    void logoutIsIdempotent() {
        RefreshResult issued = service.issue(userId, null, null, NOW);

        service.revokeByToken(issued.token().value(), NOW);
        assertThat(store.get(RefreshTokenService.hash(issued.token().value())).isRevoked()).isTrue();
        verify(securityEvents).record(eq(SecurityEventType.SESSION_REVOKE), eq(userId), any());

        service.revokeByToken("a-token-nobody-ever-issued", NOW);
    }

    @Test
    @DisplayName("A family may only be revoked by the user who owns it")
    void revokingAFamilyIsScopedToItsOwner() {
        RefreshResult issued = service.issue(userId, null, null, NOW);
        when(tokens.findAllByFamilyId(issued.familyId()))
            .thenReturn(List.of(store.get(RefreshTokenService.hash(issued.token().value()))));

        assertThat(service.revokeFamily(UUID.randomUUID(), issued.familyId(), NOW))
            .as("a family id alone authorises nothing")
            .isFalse();
        assertThat(service.revokeFamily(userId, issued.familyId(), NOW)).isTrue();
    }

    @Test
    @DisplayName("Hashing is SHA-256 hex and never returns the token itself")
    void hashing() {
        assertThat(RefreshTokenService.hash("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
