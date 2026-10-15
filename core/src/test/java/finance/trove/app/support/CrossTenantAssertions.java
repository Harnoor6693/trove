package finance.trove.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The reusable cross-household isolation harness of §9 risk 2.
 *
 * <p>It exists because the structural cause of the pre-collapse G1 IDOR — any repository call can
 * omit the scoping column — is not fixed by fixing one endpoint. Every later collapse step adds
 * by-id routes, and each of them is a fresh chance to reintroduce it. This class is what those
 * steps extend: a step adds its {@link Probe}s to its own {@code AccessControlIT} and gets the
 * whole matrix for free rather than reinventing two-tenant setup each time.
 *
 * <p>Three assertions, and the third is the one people forget:
 * <ul>
 *   <li>{@link #assertForeignAccessRefused} — a member of household B touching household A's
 *       resource gets 404 (or 403), never 200 and never a 500.</li>
 *   <li>{@link #assertHouseholdMatesShareVisibility} — two members of the SAME household see each
 *       other's rows. Under the owner's shared-ledger decision this is a requirement, not a leak;
 *       an implementation that scoped by {@code user_id} instead of {@code household_id} would
 *       pass the isolation test and fail this one, and that mistake is invisible on a
 *       single-member install.</li>
 *   <li>{@link #assertAnonymousRefused} — the same routes with no credential at all answer 401,
 *       which catches a route accidentally added to the permit list.</li>
 * </ul>
 *
 * <p><strong>Why a 404 and not a 403.</strong> A 403 confirms the resource exists; over enough
 * probes that is an enumeration oracle for another household's row ids. A scoped finder that
 * returns empty and a controller that maps empty to "not found" produce 404 by construction,
 * which is both the safer answer and the one that falls out of writing the query correctly.
 */
public final class CrossTenantAssertions {

    /**
     * How the harness makes a request. Implemented by the IT, which owns the base URL and the
     * client — the harness deliberately knows nothing about either.
     */
    @FunctionalInterface
    public interface Exchange {

        /**
         * @param method      HTTP method
         * @param path        application path including {@code /v1}, without the context path
         * @param bearerToken the caller's access token, or {@code null} for anonymous
         * @param jsonBody    request body, or {@code null}
         */
        HttpResponse<String> send(String method, String path, String bearerToken, String jsonBody);
    }

    /**
     * One side of the isolation test.
     *
     * @param label       a name for assertion messages, e.g. {@code "household A admin"}
     * @param householdId the household this account belongs to
     * @param userId      the account
     * @param email       the account's address
     * @param accessToken a live bearer token for it
     */
    public record Tenant(String label, UUID householdId, UUID userId, String email,
                         String accessToken) {
    }

    /**
     * One route to probe.
     *
     * @param description       what the probe is about, for the assertion message
     * @param method            HTTP method
     * @param path              the fully-resolved path, ids substituted — the owner's ids, since
     *                          the point is to ask for somebody else's row by its real id
     * @param jsonBody          request body, or {@code null}
     * @param refusedStatuses   what a foreign caller may receive; 404 by default, 403 where a
     *                          route is role-gated before it ever looks at an id
     * @param ownerStatuses     what the owner receives, so the probe proves the route works at
     *                          all rather than being uniformly broken
     * @param expectedRowId     for a GET probe, the id of a row both household mates must be able
     *                          to see. Without it a shared-visibility assertion over an empty
     *                          listing is a tautology — two members who can both see nothing
     *                          compare equal
     */
    public record Probe(String description, String method, String path, String jsonBody,
                        Set<Integer> refusedStatuses, Set<Integer> ownerStatuses,
                        String expectedRowId) {

        /** The common case: the owner reads 200, everyone else gets 404. */
        public static Probe get(String description, String path) {
            return new Probe(description, "GET", path, null, Set.of(404), Set.of(200), null);
        }

        /**
         * A GET whose result must contain {@code expectedRowId} for every member of the
         * household — the form to use with {@link #assertHouseholdMatesShareVisibility}.
         */
        public static Probe get(String description, String path, String expectedRowId) {
            return new Probe(description, "GET", path, null, Set.of(404), Set.of(200),
                expectedRowId);
        }

        /** A by-id mutation: the owner succeeds, a foreign caller gets 404. */
        public static Probe mutate(String description, String method, String path, String body) {
            return new Probe(description, method, path, body, Set.of(404), Set.of(200, 204), null);
        }

        /** A route the security chain refuses on role before any id is read. */
        public static Probe adminOnly(String description, String method, String path,
                                      String body) {
            return new Probe(description, method, path, body, Set.of(403), Set.of(200, 201, 204),
                null);
        }
    }

    private final Exchange exchange;

    public CrossTenantAssertions(Exchange exchange) {
        this.exchange = exchange;
    }

    /** A member of {@code outsider}'s household must not reach {@code owner}'s rows. */
    public void assertForeignAccessRefused(Tenant owner, Tenant outsider, List<Probe> probes) {
        assertThat(owner.householdId())
            .as("the harness needs two genuinely different households to prove anything")
            .isNotEqualTo(outsider.householdId());

        for (Probe probe : probes) {
            HttpResponse<String> response = exchange.send(probe.method(), probe.path(),
                outsider.accessToken(), probe.jsonBody());
            assertThat(response.statusCode())
                .as("%s: %s %s as %s (household %s) must not reach household %s. Body: %s",
                    probe.description(), probe.method(), probe.path(), outsider.label(),
                    outsider.householdId(), owner.householdId(), response.body())
                .isIn(probe.refusedStatuses());
            assertThat(response.body())
                .as("%s: a refusal must not echo the other household's data", probe.description())
                .doesNotContain(owner.email());
        }
    }

    /** The owner reaches their own rows, so a uniformly broken route cannot pass the test above. */
    public void assertOwnerAccessGranted(Tenant owner, List<Probe> probes) {
        for (Probe probe : probes) {
            HttpResponse<String> response = exchange.send(probe.method(), probe.path(),
                owner.accessToken(), probe.jsonBody());
            assertThat(response.statusCode())
                .as("%s: %s %s as its owner %s should succeed, or the isolation assertion above "
                        + "is passing because the route is broken for everybody. Body: %s",
                    probe.description(), probe.method(), probe.path(), owner.label(),
                    response.body())
                .isIn(probe.ownerStatuses());
        }
    }

    /**
     * Two members of one household see the same things.
     *
     * <p>The owner decision is one shared ledger per household, so this is the requirement that
     * distinguishes correct household scoping from an over-tight per-user scoping that would look
     * identical on a single-member install and hide every one of a partner's transactions on a
     * two-member one.
     */
    public void assertHouseholdMatesShareVisibility(Tenant one, Tenant two, List<Probe> probes) {
        assertThat(one.householdId())
            .as("this assertion is about two members of the SAME household")
            .isEqualTo(two.householdId());

        for (Probe probe : probes) {
            HttpResponse<String> first = exchange.send(probe.method(), probe.path(),
                one.accessToken(), probe.jsonBody());
            HttpResponse<String> second = exchange.send(probe.method(), probe.path(),
                two.accessToken(), probe.jsonBody());
            assertThat(second.statusCode())
                .as("%s: %s and %s share a household, so %s must see it too. Body: %s",
                    probe.description(), one.label(), two.label(), two.label(), second.body())
                .isEqualTo(first.statusCode());

            if (!"GET".equalsIgnoreCase(probe.method()) || first.statusCode() != 200) {
                continue;
            }
            // Status codes alone prove nothing on a list route: a per-user-scoped listing answers
            // 200 with an empty array to the member who owns none of the rows, and "200 == 200"
            // is exactly the shape of the bug this assertion exists to catch. Compare what came
            // back.
            List<String> mine = rowIds(first.body());
            List<String> theirs = rowIds(second.body());
            assertThat(theirs)
                .as("%s: %s and %s share one ledger, so the rows they see are the same set. "
                        + "%s saw %s, %s saw %s",
                    probe.description(), one.label(), two.label(),
                    one.label(), mine, two.label(), theirs)
                .containsExactlyInAnyOrderElementsOf(mine);
            if (probe.expectedRowId() != null) {
                assertThat(theirs)
                    .as("%s: %s must see the row %s created", probe.description(), two.label(),
                        one.label())
                    .contains(probe.expectedRowId());
            }
        }
    }

    /**
     * The {@code id} of every row in an enveloped body, whether {@code data} is a list or one
     * object.
     */
    private static List<String> rowIds(String body) {
        JsonNode data = Json.data(body);
        if (data.isArray()) {
            return Json.elements(data).stream()
                .map(node -> node.path("id").asString(""))
                .toList();
        }
        return List.of(data.path("id").asString(""));
    }

    /**
     * A MEMBER of the SAME household is refused on every administrative route.
     *
     * <p>Separate from {@link #assertForeignAccessRefused}, which asserts up front that the two
     * accounts belong to different households: this one asserts the opposite, because the risk it
     * covers is different. Cross-household refusal is about tenancy; this is about role, and the
     * failure it catches is an admin route reachable by any authenticated member of the household
     * that owns the row — where a scoped finder would happily return it.
     */
    public void assertRoleRefusedWithinHousehold(Tenant admin, Tenant member, List<Probe> probes) {
        assertThat(member.householdId())
            .as("this assertion is about role, not tenancy: both accounts are in one household")
            .isEqualTo(admin.householdId());

        for (Probe probe : probes) {
            HttpResponse<String> response = exchange.send(probe.method(), probe.path(),
                member.accessToken(), probe.jsonBody());
            assertThat(response.statusCode())
                .as("%s: %s %s as %s, an ordinary MEMBER of the same household, must be refused "
                        + "on role. Body: %s",
                    probe.description(), probe.method(), probe.path(), member.label(),
                    response.body())
                .isIn(probe.refusedStatuses());
        }
    }

    /** No credential at all: 401, and never 200. */
    public void assertAnonymousRefused(List<Probe> probes) {
        for (Probe probe : probes) {
            HttpResponse<String> response =
                exchange.send(probe.method(), probe.path(), null, probe.jsonBody());
            assertThat(response.statusCode())
                .as("%s: %s %s must require a session; a route that answers anonymously has "
                        + "escaped the security chain (§9 risk 1). Body: %s",
                    probe.description(), probe.method(), probe.path(), response.body())
                .isEqualTo(401);
        }
    }
}
