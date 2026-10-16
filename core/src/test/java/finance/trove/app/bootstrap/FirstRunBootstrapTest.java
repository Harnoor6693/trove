package finance.trove.app.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.auth.AuthProperties;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.PasswordPolicyException;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.TimeProperties;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Readiness gap P2-7's first-run admin.
 *
 * <p>Two properties dominate: it must run exactly once, decided by data rather than a marker, and
 * it must never fail the boot. The first is what stops a restart silently reverting a password the
 * owner has since changed. The second is what stops a merely un-onboarded install becoming an
 * unreachable one — including the {@code /v1/status} endpoint an operator would use to find out
 * why.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirstRunBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private HouseholdRepository households;

    @Mock
    private UserRepository users;

    @Mock
    private LocalAccountService accounts;

    private FirstRunBootstrap bootstrap(BootstrapProperties properties) {
        return new FirstRunBootstrap(properties, new AuthProperties(false, "https://trove.test"),
            new TimeProperties("America/Toronto"), households, users, accounts);
    }

    private static BootstrapProperties complete() {
        return new BootstrapProperties("Owner@Trove.Test", "a-perfectly-fine-passphrase",
            "The Household", null);
    }

    @Test
    @DisplayName("On an empty install it creates one household and one ADMIN")
    void createsTheFirstHouseholdAndAdmin() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(complete()).bootstrapIfEmpty(NOW);

        ArgumentCaptor<Household> household = ArgumentCaptor.forClass(Household.class);
        verify(households).save(household.capture());
        assertThat(household.getValue().getName()).isEqualTo("The Household");
        assertThat(household.getValue().isRegistrationOpen())
            .as("closed by default; the env var is only a seed for this one row")
            .isFalse();

        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(admin.getValue().getEmail())
            .as("addresses are stored lower-cased; the unique index is on lower(email)")
            .isEqualTo("owner@trove.test");
        assertThat(admin.getValue().getDisplayName()).isEqualTo("Owner");
        assertThat(admin.getValue().getCountry()).isEqualTo(Jurisdiction.CA);
        assertThat(admin.getValue().getCurrency()).isEqualTo(CurrencyCode.CAD);
        assertThat(admin.getValue().getTimezone()).isEqualTo("America/Toronto");
        verify(accounts).createCredential(any(), eq("a-perfectly-fine-passphrase"),
            eq("owner@trove.test"), eq(NOW));
    }

    @Test
    @DisplayName("The registration-open seed is copied onto the household it creates")
    void seedsRegistrationOpen() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new FirstRunBootstrap(complete(), new AuthProperties(true, "https://trove.test"),
            new TimeProperties("UTC"), households, users, accounts).bootstrapIfEmpty(NOW);

        ArgumentCaptor<Household> household = ArgumentCaptor.forClass(Household.class);
        verify(households).save(household.capture());
        assertThat(household.getValue().isRegistrationOpen()).isTrue();
    }

    @Test
    @DisplayName("On an install that already has users it does nothing at all")
    void isANoOpOnceUsersExist() {
        when(users.countAllUsers()).thenReturn(1L);

        bootstrap(complete()).bootstrapIfEmpty(NOW);

        verify(households, never()).save(any());
        verify(users, never()).save(any());
        verify(accounts, never()).createCredential(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Missing credentials leave the install empty; the boot still succeeds")
    void incompleteCredentialsDoNotFailTheBoot() {
        when(users.countAllUsers()).thenReturn(0L);

        bootstrap(new BootstrapProperties(null, null, null, null)).bootstrapIfEmpty(NOW);
        bootstrap(new BootstrapProperties("owner@trove.test", null, null, null))
            .bootstrapIfEmpty(NOW);
        bootstrap(new BootstrapProperties(null, "a-passphrase", null, null)).bootstrapIfEmpty(NOW);

        verify(households, never()).save(any());
    }

    @Test
    @DisplayName("A refused admin password does not stop the application starting")
    void aWeakPasswordDoesNotFailTheBoot() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new PasswordPolicyException("too short"))
            .when(accounts).createCredential(any(), anyString(), anyString(), any());

        // run(), not bootstrapIfEmpty(): the catch that keeps the boot alive lives there.
        bootstrap(complete()).run(null);
    }

    @Test
    @DisplayName("An unparseable default timezone falls back to UTC rather than failing")
    void unknownDefaultTimezoneFallsBackToUtc() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new FirstRunBootstrap(complete(), new AuthProperties(false, "https://trove.test"),
            new TimeProperties("Nowhere/Special"), households, users, accounts)
            .bootstrapIfEmpty(NOW);

        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getTimezone()).isEqualTo("UTC");
    }

    @Test
    @DisplayName("The display name is derived from the address' local part")
    void derivesADisplayName() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(new BootstrapProperties("first.last@trove.test", "a-fine-passphrase", null, null))
            .bootstrapIfEmpty(NOW);

        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getDisplayName())
            .as("there is no TROVE_ADMIN_NAME: it would be a third thing to get right in an "
                + "environment file to set a value the owner changes in the first minute")
            .isEqualTo("First last");
    }

    @Test
    @DisplayName("Blank properties bind as absent and the household name defaults")
    void propertiesNormalise() {
        BootstrapProperties properties = new BootstrapProperties("  ", "  ", "  ", null);
        assertThat(properties.email()).isNull();
        assertThat(properties.password()).isNull();
        assertThat(properties.householdName()).isEqualTo("Household");
        assertThat(properties.isComplete()).isFalse();

        assertThat(new BootstrapProperties(" a@b.test ", "pw", " Home ", null).isComplete())
            .isTrue();
        assertThat(new BootstrapProperties(" a@b.test ", "pw", " Home ", null).email())
            .isEqualTo("a@b.test");
    }

    @Test
    @DisplayName("The country defaults to CA and is normalised to upper case")
    void countryNormalises() {
        assertThat(new BootstrapProperties("a@b.test", "pw", null, null).country())
            .isEqualTo("CA");
        assertThat(new BootstrapProperties("a@b.test", "pw", null, "  ").country())
            .isEqualTo("CA");
        assertThat(new BootstrapProperties("a@b.test", "pw", null, " us ").country())
            .isEqualTo("US");
    }

    @Test
    @DisplayName("TROVE_ADMIN_COUNTRY=US makes the first admin American, in US dollars")
    void usCountryMakesAUsAdmin() {
        when(users.countAllUsers()).thenReturn(0L);
        when(households.save(any(Household.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(new BootstrapProperties("owner@trove.test", "a-fine-passphrase", null, "US"))
            .bootstrapIfEmpty(NOW);

        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getCountry()).isEqualTo(Jurisdiction.US);
        assertThat(admin.getValue().getCurrency())
            .as("the currency follows the country; nothing can change either afterwards")
            .isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("An unsupported country creates nobody, and the boot still succeeds")
    void unsupportedCountryCreatesNobody() {
        when(users.countAllUsers()).thenReturn(0L);

        bootstrap(new BootstrapProperties("owner@trove.test", "a-fine-passphrase", null, "UK"))
            .bootstrapIfEmpty(NOW);

        verify(households, never()).save(any());
        verify(users, never()).save(any());
        verify(accounts, never()).createCredential(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("A household id is never invented — it comes from the saved row")
    void householdIdComesFromTheSavedRow() {
        when(users.countAllUsers()).thenReturn(0L);
        Household saved = new Household("The Household", false);
        setId(saved);
        when(households.save(any(Household.class))).thenReturn(saved);
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        bootstrap(complete()).bootstrapIfEmpty(NOW);

        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(users).save(admin.capture());
        assertThat(admin.getValue().getHouseholdId()).isEqualTo(saved.getId());
    }

    private static void setId(Household household) {
        try {
            var field = Household.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(household, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
