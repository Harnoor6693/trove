package finance.trove.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.TimeProperties;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.households.InstallHousehold;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.ZoneId;
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

/**
 * §9 risk 27 — the account takeover the pre-collapse OIDC handler shipped.
 *
 * <p>That handler matched an identity to a local account on the {@code email} claim alone
 * ({@code TroveOidcSuccessHandler.java:83-88}), so anyone who could get an account issued at the
 * configured identity provider with an unverified address matching a household member logged in as
 * that member with full ledger access. Every case below is a variant of that attack, and none of
 * them may return {@link OidcLinkOutcome.Linked}.
 *
 * <p>These are unit tests rather than an {@code OidcLinkIT}: exercising this end to end needs a
 * live OpenID provider to mint ID tokens with and without the claim, which no test environment
 * here has. The decision is therefore isolated in this class precisely so it can be tested without
 * one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OidcAccountLinkerTest {

    private static final String PROVIDER = "oidc";
    private static final String SUBJECT = "provider-subject-42";
    private static final String EMAIL = "member@trove.test";

    @Mock
    private OidcLinkRepository links;

    @Mock
    private UserRepository users;

    @Mock
    private HouseholdRepository households;

    @Mock
    private SecurityEventRecorder securityEvents;

    private OidcAccountLinker linker;
    private User member;
    private Household household;

    @BeforeEach
    void setUp() {
        // A real InstallHousehold over the mocked repository: the "oldest household is the
        // install's household" rule is the thing under test at registerNew, so stubbing it out
        // would remove the only part of it this class can see.
        linker = new OidcAccountLinker(links, users, new InstallHousehold(households),
            new TimeProperties("UTC"), securityEvents);
        household = new Household("Household", false);
        member = new User(UUID.randomUUID(), UserRole.MEMBER, EMAIL, "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
        when(links.findByProviderAndProviderSub(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(household));
    }

    @Test
    @DisplayName("An existing link wins, and the email claim is then irrelevant")
    void anExistingLinkIsAuthoritative() {
        OidcLink link = new OidcLink(member.getId(), PROVIDER, SUBJECT, "old@trove.test");
        when(links.findByProviderAndProviderSub(PROVIDER, SUBJECT)).thenReturn(Optional.of(link));
        when(users.findAccount(member.getId())).thenReturn(Optional.of(member));

        OidcLinkOutcome outcome = linker.resolve(PROVIDER, SUBJECT, "changed@elsewhere.test", null);

        assertThat(outcome)
            .as("sub is the only stable identity a provider guarantees; once linked, a changed "
                + "email at the provider must not disturb anything")
            .isInstanceOf(OidcLinkOutcome.Linked.class);
        assertThat(((OidcLinkOutcome.Linked) outcome).newLink()).isFalse();
    }

    @Test
    @DisplayName("A link pointing at a deleted account is refused, never silently re-pointed")
    void aDanglingLinkIsRefused() {
        when(links.findByProviderAndProviderSub(PROVIDER, SUBJECT))
            .thenReturn(Optional.of(new OidcLink(UUID.randomUUID(), PROVIDER, SUBJECT, EMAIL)));
        when(users.findAccount(any())).thenReturn(Optional.empty());

        assertThat(linker.resolve(PROVIDER, SUBJECT, EMAIL, true))
            .as("falling through to the email branch would re-link the identity to whoever now "
                + "holds that address")
            .isInstanceOf(OidcLinkOutcome.Denied.class);
    }

    @Test
    @DisplayName("email_verified=true auto-links to a matching local account")
    void verifiedEmailAutoLinks() {
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(member));

        OidcLinkOutcome outcome = linker.resolve(PROVIDER, SUBJECT, EMAIL, true);

        assertThat(outcome).isInstanceOf(OidcLinkOutcome.Linked.class);
        assertThat(((OidcLinkOutcome.Linked) outcome).newLink()).isTrue();
        verify(links).save(any(OidcLink.class));
    }

    @Test
    @DisplayName("email_verified=false does NOT auto-link — this is the takeover")
    void unverifiedEmailDoesNotAutoLink() {
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(member));

        OidcLinkOutcome outcome = linker.resolve(PROVIDER, SUBJECT, EMAIL, false);

        assertThat(outcome).isInstanceOf(OidcLinkOutcome.LinkRequired.class);
        assertThat(((OidcLinkOutcome.LinkRequired) outcome).email()).isEqualTo(EMAIL);
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("An ABSENT email_verified claim is treated as false, not as permission")
    void anAbsentClaimIsNotConsent() {
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(member));

        assertThat(linker.resolve(PROVIDER, SUBJECT, EMAIL, null))
            .as("a provider that does not say the address is verified has not said it")
            .isInstanceOf(OidcLinkOutcome.LinkRequired.class);
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("Address matching is case-insensitive, so casing is not a bypass either way")
    void addressMatchingIsCaseInsensitive() {
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(member));

        assertThat(linker.resolve(PROVIDER, SUBJECT, "Member@Trove.Test", false))
            .isInstanceOf(OidcLinkOutcome.LinkRequired.class);
    }

    @Test
    @DisplayName("An identity with no email claim at all is refused")
    void noEmailClaimIsRefused() {
        assertThat(linker.resolve(PROVIDER, SUBJECT, null, true))
            .isInstanceOf(OidcLinkOutcome.Denied.class);
        assertThat(linker.resolve(PROVIDER, SUBJECT, "   ", true))
            .isInstanceOf(OidcLinkOutcome.Denied.class);
    }

    @Test
    @DisplayName("No local account and closed registration: refused, not created")
    void closedRegistrationRefusesANewIdentity() {
        assertThat(linker.resolve(PROVIDER, SUBJECT, "stranger@elsewhere.test", true))
            .as("an identity provider with open enrolment must not become an open door into "
                + "somebody's finance server (gap P2-7)")
            .isInstanceOf(OidcLinkOutcome.Denied.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("No local account and OPEN registration: an account is created and linked")
    void openRegistrationCreatesAnAccount() {
        household.setRegistrationOpen(true);

        OidcLinkOutcome outcome =
            linker.resolve(PROVIDER, SUBJECT, "Stranger@Elsewhere.Test", true);

        assertThat(outcome).isInstanceOf(OidcLinkOutcome.Registered.class);
        User created = ((OidcLinkOutcome.Registered) outcome).user();
        assertThat(created.getEmail()).isEqualTo("stranger@elsewhere.test");
        assertThat(created.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(created.getHouseholdId()).isEqualTo(household.getId());
        verify(links).save(any(OidcLink.class));
    }

    @Test
    @DisplayName("Open registration still refuses an unverified address")
    void openRegistrationStillRequiresVerification() {
        household.setRegistrationOpen(true);

        assertThat(linker.resolve(PROVIDER, SUBJECT, "stranger@elsewhere.test", false))
            .as("creating an account from an unverified address lets anyone with control of an "
                + "IdP squat an address a household member might later want")
            .isInstanceOf(OidcLinkOutcome.Denied.class);
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("Registration before the install is bootstrapped is refused, not a 500")
    void noHouseholdMeansRefused() {
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());

        assertThat(linker.resolve(PROVIDER, SUBJECT, "stranger@elsewhere.test", true))
            .isInstanceOf(OidcLinkOutcome.Denied.class);
    }

    @Test
    @DisplayName("A deactivated local account is not matchable")
    void deactivatedAccountsAreNotLinkable() {
        member.setActive(false);
        when(users.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(member));

        assertThat(linker.resolve(PROVIDER, SUBJECT, EMAIL, true))
            .isInstanceOf(OidcLinkOutcome.Denied.class);
    }

    @Test
    @DisplayName("Linking after a password proof is idempotent")
    void linkAfterPasswordProofIsIdempotent() {
        linker.linkAfterPasswordProof(member, PROVIDER, SUBJECT, EMAIL);
        verify(links).save(any(OidcLink.class));

        when(links.findByProviderAndProviderSub(PROVIDER, SUBJECT))
            .thenReturn(Optional.of(new OidcLink(member.getId(), PROVIDER, SUBJECT, EMAIL)));
        linker.linkAfterPasswordProof(member, PROVIDER, SUBJECT, EMAIL);
        verify(links, org.mockito.Mockito.times(1)).save(any(OidcLink.class));
    }
}
