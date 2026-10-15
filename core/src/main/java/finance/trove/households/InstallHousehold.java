package finance.trove.households;

import finance.trove.households.invites.RegistrationClosedException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one household a self-serve registration can join.
 *
 * <p>The owner's decision is one shared household ledger per install
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5), so "the install's household" is a real
 * concept and the oldest row is it. Three call sites need it and each of them had its own copy of
 * {@code findAllByOrderByCreatedAtAsc().get(0)}: {@code AuthService} (which household an open
 * registration joins), {@code AuthController} (whether the login page shows a signup link), and
 * {@code OidcAccountLinker} (whether a verified federated identity may create an account). Three
 * copies of an assumption is how the assumption stops being one.
 *
 * <p>The limitation this makes explicit: <strong>only the install's original household can open
 * registration.</strong> A second household — which nothing in the application creates, and which
 * exists in tests only — may set its own {@code registration_open} flag and that flag has no
 * effect, because a bare {@code POST /v1/auth/register} has no way to name a household and this
 * accessor answers with the first one. That is a documented rule with a test on it
 * ({@code AccessControlIT}), not a silent behaviour: the alternative reading, "whichever
 * household happens to be open", would make the target of an anonymous registration depend on
 * row order.
 *
 * <p>The warning below fires only if a second household ever appears, and says exactly which
 * decision is being made on the caller's behalf.
 */
@Component
public class InstallHousehold {

    private static final Logger LOG = LoggerFactory.getLogger(InstallHousehold.class);

    private final HouseholdRepository households;

    public InstallHousehold(HouseholdRepository households) {
        this.households = households;
    }

    /**
     * The install's household, or empty on an install that has never been bootstrapped.
     *
     * <p>Empty is a real state, not a defect: the container can serve {@code /v1/auth/providers}
     * before {@code FirstRunBootstrap} has created anything, and the honest answer there is
     * "registration is closed", not a 500.
     */
    public Optional<Household> find() {
        List<Household> all = households.findAllByOrderByCreatedAtAsc();
        if (all.size() > 1) {
            LOG.warn("This install has {} households; self-serve registration and the providers "
                + "response both use the oldest one ({}). A second household's registrationOpen "
                + "flag has no effect.", all.size(), all.get(0).getId());
        }
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Whether an anonymous caller may register without an invite. */
    public boolean registrationOpen() {
        return find().map(Household::isRegistrationOpen).orElse(false);
    }

    /**
     * The household an open self-serve registration joins.
     *
     * @throws RegistrationClosedException when registration is closed, and also when the install
     *                                     has no household at all — there is nothing to join, and
     *                                     "closed" is the honest answer rather than a 500
     */
    public Household requireOpenForRegistration() {
        Optional<Household> household = find();
        if (household.isEmpty()) {
            LOG.warn("Registration attempted before the install has been bootstrapped");
            throw new RegistrationClosedException();
        }
        if (!household.get().isRegistrationOpen()) {
            throw new RegistrationClosedException();
        }
        return household.get();
    }

    /** The household an open registration would join, or empty when registration is closed. */
    public Optional<Household> findOpenForRegistration() {
        return find().filter(Household::isRegistrationOpen);
    }
}
