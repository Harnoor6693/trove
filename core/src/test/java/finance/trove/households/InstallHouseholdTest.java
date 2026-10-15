package finance.trove.households;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import finance.trove.households.invites.RegistrationClosedException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The single-household assumption, stated once instead of three times.
 *
 * <p>{@code AuthService}, {@code AuthController} and {@code OidcAccountLinker} each carried their
 * own {@code findAllByOrderByCreatedAtAsc().get(0)}. The behaviour those copies produced was never
 * asserted anywhere, so the rule they implement — <em>only the install's original household can
 * open self-serve registration</em> — was a coincidence of row order rather than a decision. Here
 * it is a decision with a test on it.
 */
@ExtendWith(MockitoExtension.class)
class InstallHouseholdTest {

    @Mock
    private HouseholdRepository households;

    @Test
    @DisplayName("An install with no household is closed, not broken")
    void anUnbootstrappedInstallIsClosed() {
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        InstallHousehold install = new InstallHousehold(households);

        assertThat(install.find()).isEmpty();
        assertThat(install.registrationOpen())
            .as("the providers response is served before FirstRunBootstrap has run; the honest "
                + "answer is 'closed', not a 500")
            .isFalse();
        assertThat(install.findOpenForRegistration()).isEmpty();
        assertThatThrownBy(install::requireOpenForRegistration)
            .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("A closed household refuses registration and reports itself closed")
    void aClosedHouseholdRefuses() {
        when(households.findAllByOrderByCreatedAtAsc())
            .thenReturn(List.of(new Household("Household", false)));
        InstallHousehold install = new InstallHousehold(households);

        assertThat(install.find()).isPresent();
        assertThat(install.registrationOpen()).isFalse();
        assertThat(install.findOpenForRegistration()).isEmpty();
        assertThatThrownBy(install::requireOpenForRegistration)
            .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("An open household is the one an anonymous registration joins")
    void anOpenHouseholdAdmits() {
        Household open = new Household("Household", true);
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(open));
        InstallHousehold install = new InstallHousehold(households);

        assertThat(install.registrationOpen()).isTrue();
        assertThat(install.requireOpenForRegistration()).isSameAs(open);
        assertThat(install.findOpenForRegistration()).contains(open);
    }

    @Test
    @DisplayName("Only the OLDEST household's flag counts — a later one's has no effect")
    void aSecondHouseholdsFlagIsIgnored() {
        // The rule, stated: a bare POST /v1/auth/register carries no household, so the target
        // cannot depend on which row happens to be open. It is always the install's original
        // household. A second household exists only in tests today, and this is what it gets.
        Household first = new Household("Install household", false);
        Household second = new Household("Later household", true);
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        InstallHousehold install = new InstallHousehold(households);

        assertThat(install.find()).contains(first);
        assertThat(install.registrationOpen())
            .as("household B opening its own registration must not open the install's")
            .isFalse();
        assertThatThrownBy(install::requireOpenForRegistration)
            .isInstanceOf(RegistrationClosedException.class);
    }

    @Test
    @DisplayName("With the oldest open, a later closed household does not close it")
    void theOldestHouseholdDecidesBothWays() {
        Household first = new Household("Install household", true);
        Household second = new Household("Later household", false);
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(first, second));

        assertThat(new InstallHousehold(households).requireOpenForRegistration())
            .as("the rule is 'the oldest decides', not 'any open one wins' — it has to answer "
                + "the same way in both directions or it is not a rule")
            .isSameAs(first);
    }
}
