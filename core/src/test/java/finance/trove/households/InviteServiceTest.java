package finance.trove.households;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.dto.CreateInviteRequest;
import finance.trove.auth.dto.InviteResponse;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.households.invites.HouseholdInvite;
import finance.trove.households.invites.HouseholdInviteRepository;
import finance.trove.households.invites.InviteException;
import finance.trove.households.invites.InviteService;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
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
 * Readiness gap P2-7's invitations.
 *
 * <p>The statuses are asserted explicitly because the clients dispatch this whole family on the
 * HTTP status and not on the slug ({@code apps/web/src/pages/Login/errorCopy.ts:71} maps 404 and
 * 410, and only those two, onto "that invite link is no longer valid"). A 400 — the obvious
 * reading of "the token is invalid" — falls through to a generic message that blames the server
 * for the user's expired link.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private HouseholdInviteRepository invites;

    @Mock
    private UserRepository users;

    @Mock
    private SecurityEventRecorder securityEvents;

    private final AuthProperties properties =
        new AuthProperties(false, "https://trove.example.com/");
    /** A real service, not a mock: the point is that what comes back out is what went in. */
    private final FieldEncryptionService encryption =
        new FieldEncryptionService("0".repeat(63) + "1");
    private final Map<String, HouseholdInvite> byHash = new HashMap<>();

    private InviteService service;
    private final UUID householdId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InviteService(invites, users, properties, securityEvents, encryption);
        byHash.clear();
        when(invites.save(any(HouseholdInvite.class))).thenAnswer(invocation -> {
            HouseholdInvite invite = invocation.getArgument(0);
            assignId(invite);
            byHash.put(invite.getTokenHash(), invite);
            return invite;
        });
        when(invites.findByTokenHash(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(byHash.get(invocation.getArgument(0))));
        when(invites.findByTokenHashForUpdate(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(byHash.get(invocation.getArgument(0))));
        when(users.findAccount(any())).thenReturn(Optional.empty());
    }

    private static void assignId(HouseholdInvite invite) {
        try {
            var field = HouseholdInvite.class.getDeclaredField("id");
            field.setAccessible(true);
            if (field.get(invite) == null) {
                field.set(invite, UUID.randomUUID());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private InviteResponse create(String email) {
        return service.create(householdId, adminId, new CreateInviteRequest(email, null), NOW);
    }

    @Test
    @DisplayName("A created invite carries the token once, an absolute URL and a 72-hour deadline")
    void createReturnsTheOnlyReadableCopy() {
        InviteResponse response = create(null);

        assertThat(response.token()).isNotBlank();
        assertThat(response.url())
            .as("the URL is absolute because an admin pastes it into a chat, and the trailing "
                + "slash on TROVE_PUBLIC_URL must not produce a double slash")
            .isEqualTo("https://trove.example.com/invite/" + response.token());
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(72)));
        assertThat(response.email()).isNull();
        assertThat(response.acceptedAt()).isNull();
        assertThat(byHash.keySet()).singleElement()
            .satisfies(hash -> assertThat(hash)
                .as("only a hash reaches the database, exactly as for refresh and reset tokens")
                .hasSize(64)
                .isNotEqualTo(response.token()));
    }

    @Test
    @DisplayName("A listing hands back the link of an invite that can still be redeemed")
    void listingCarriesTheTokenOfAUsableInvite() {
        // The shipped web admin UI discards the creation response and renders `invite.url` from
        // this listing (apps/web/src/pages/Settings/index.tsx:166-169, 215). A listing that
        // nulled it made the invite feature unusable from the only UI there is.
        InviteResponse created = create(null);
        when(invites.findAllByHouseholdIdOrderByCreatedAtDesc(householdId))
            .thenReturn(java.util.List.copyOf(byHash.values()));

        assertThat(service.list(householdId, NOW)).singleElement().satisfies(invite -> {
            assertThat(invite.id()).isEqualTo(created.id());
            assertThat(invite.token()).isEqualTo(created.token());
            assertThat(invite.url())
                .isEqualTo("https://trove.example.com/invite/" + created.token());
        });
        assertThat(byHash.values()).singleElement().satisfies(stored -> {
            assertThat(stored.getTokenEncrypted())
                .as("what sits in the row is ciphertext; a dump without the key is worthless")
                .isNotNull()
                .doesNotContain(created.token())
                .startsWith(FieldEncryptionService.CURRENT_VERSION + ":");
            assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(created.token());
        });
    }

    @Test
    @DisplayName("A spent or expired invite lists with no token, and none is recoverable")
    void listingNeverCarriesASpentToken() {
        InviteResponse accepted = create("accepted@trove.test");
        InviteResponse revoked = create("revoked@trove.test");
        InviteResponse expired = create("expired@trove.test");
        byHash.values().stream()
            .filter(invite -> "accepted@trove.test".equals(invite.getEmail()))
            .forEach(invite -> invite.accept(UUID.randomUUID(), NOW));
        byHash.values().stream()
            .filter(invite -> "revoked@trove.test".equals(invite.getEmail()))
            .forEach(invite -> invite.revoke(NOW));
        when(invites.findAllByHouseholdIdOrderByCreatedAtDesc(householdId))
            .thenReturn(java.util.List.copyOf(byHash.values()));

        // An hour past the 72-hour deadline: every one of the three is now unusable, the first
        // two because they are spent and the third because it timed out.
        assertThat(service.list(householdId, NOW.plus(Duration.ofHours(73))))
            .as("%s / %s / %s", accepted.id(), revoked.id(), expired.id())
            .allSatisfy(invite -> {
                assertThat(invite.token()).isNull();
                assertThat(invite.url()).isNull();
            });
        assertThat(byHash.values())
            .filteredOn(invite -> invite.getAcceptedAt() != null || invite.getRevokedAt() != null)
            .as("accepting or revoking forgets the readable copy — it is not merely hidden")
            .allSatisfy(invite -> assertThat(invite.getTokenEncrypted()).isNull());
    }

    @Test
    @DisplayName("Redemption takes a row lock, so one seat cannot admit two people")
    void redemptionLocksTheRow() {
        InviteResponse created = create(null);

        service.resolveForRegistration(created.token(), "a@trove.test", NOW);

        verify(invites).findByTokenHashForUpdate(InviteService.hash(created.token()));
        verify(invites, never()).findByTokenHash(InviteService.hash(created.token()));
    }

    @Test
    @DisplayName("An empty body is accepted — the web client literally sends {}")
    void acceptsAnEmptyBody() {
        assertThat(service.create(householdId, adminId, null, NOW).token()).isNotBlank();
        assertThat(service.create(householdId, adminId, CreateInviteRequest.empty(), NOW).email())
            .isNull();
    }

    @Test
    @DisplayName("The role defaults to MEMBER, so an invite never silently confers admin")
    void roleDefaultsToMember() {
        service.create(householdId, adminId, CreateInviteRequest.empty(), NOW);
        assertThat(byHash.values()).allSatisfy(invite ->
            assertThat(invite.getRole()).isEqualTo(UserRole.MEMBER));
    }

    @Test
    @DisplayName("An unknown token previews as invalid rather than 404 — no guessing oracle")
    void previewOfAnUnknownTokenIsAPlainNo() {
        assertThat(service.preview("not-a-token", NOW).valid()).isFalse();
        assertThat(service.preview(null, NOW).valid()).isFalse();
        assertThat(service.preview("  ", NOW).expiresAt()).isNull();
    }

    @Test
    @DisplayName("A valid bound invite previews with its address, so the form can pre-fill")
    void previewOfABoundInvite() {
        InviteResponse created = create("Invitee@Trove.Test");

        var preview = service.preview(created.token(), NOW);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.email()).isEqualTo("invitee@trove.test");
        assertThat(preview.expiresAt()).isEqualTo(created.expiresAt());
    }

    @Test
    @DisplayName("An expired invite previews as invalid and discloses nothing about its address")
    void previewOfAnExpiredInvite() {
        InviteResponse created = create("invitee@trove.test");

        var preview = service.preview(created.token(), NOW.plus(Duration.ofHours(73)));
        assertThat(preview.valid()).isFalse();
        assertThat(preview.email()).isNull();
    }

    @Test
    @DisplayName("Registration: an unknown token is 404 invite-invalid")
    void unknownTokenAtRegistration() {
        assertThatThrownBy(() ->
            service.resolveForRegistration("nope", "a@trove.test", NOW))
            .isInstanceOf(InviteException.class)
            .satisfies(thrown -> {
                InviteException e = (InviteException) thrown;
                assertThat(e.getStatus().value()).isEqualTo(404);
                assertThat(e.getSlug()).isEqualTo("invite-invalid");
            });
    }

    @Test
    @DisplayName("Registration: an expired token is 410 invite-expired")
    void expiredTokenAtRegistration() {
        InviteResponse created = create(null);

        assertThatThrownBy(() -> service.resolveForRegistration(created.token(), "a@trove.test",
            NOW.plus(Duration.ofHours(72).plusSeconds(1))))
            .isInstanceOf(InviteException.class)
            .satisfies(thrown -> {
                InviteException e = (InviteException) thrown;
                assertThat(e.getStatus().value()).isEqualTo(410);
                assertThat(e.getSlug()).isEqualTo("invite-expired");
            });
    }

    @Test
    @DisplayName("Registration: an invite is single-use — the second attempt is 410")
    void invitesAreSingleUse() {
        InviteResponse created = create(null);
        HouseholdInvite invite =
            service.resolveForRegistration(created.token(), "a@trove.test", NOW);
        service.markAccepted(invite, UUID.randomUUID(), NOW);

        assertThatThrownBy(() ->
            service.resolveForRegistration(created.token(), "b@trove.test", NOW))
            .isInstanceOf(InviteException.class)
            .satisfies(thrown -> assertThat(((InviteException) thrown).getStatus().value())
                .isEqualTo(410));
    }

    @Test
    @DisplayName("Registration: a revoked token is 404, indistinguishable from an unknown one")
    void revokedTokenAtRegistration() {
        InviteResponse created = create(null);
        byHash.values().iterator().next().revoke(NOW);

        assertThatThrownBy(() ->
            service.resolveForRegistration(created.token(), "a@trove.test", NOW))
            .isInstanceOf(InviteException.class)
            .satisfies(thrown -> assertThat(((InviteException) thrown).getStatus().value())
                .isEqualTo(404));
    }

    @Test
    @DisplayName("A bound invite refuses a different address, with the unknown-token wording")
    void boundInvitesRefuseAnotherAddress() {
        InviteResponse created = create("invitee@trove.test");

        assertThatThrownBy(() ->
            service.resolveForRegistration(created.token(), "someone.else@trove.test", NOW))
            .isInstanceOf(InviteException.class)
            .satisfies(thrown -> {
                InviteException e = (InviteException) thrown;
                assertThat(e.getStatus().value()).isEqualTo(404);
                assertThat(e.getMessage())
                    .as("a distinct message would confirm to whoever holds a leaked link which "
                        + "address it was issued to — the fact the binding exists to protect")
                    .isEqualTo(InviteException.invalid().getMessage());
            });

        assertThat(service.resolveForRegistration(created.token(), "INVITEE@TROVE.TEST", NOW))
            .as("the bound address is matched case-insensitively")
            .isNotNull();
    }

    @Test
    @DisplayName("Revoking is scoped to the admin's own household")
    void revokeIsHouseholdScoped() {
        UUID inviteId = UUID.randomUUID();
        when(invites.findByIdAndHouseholdId(inviteId, householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(householdId, inviteId, adminId, NOW))
            .as("an admin of one household must not revoke another household's invite by "
                + "guessing a UUID (§9 risk 2)")
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
