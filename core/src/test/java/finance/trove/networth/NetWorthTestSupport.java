package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.households.invites.InviteService;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup for this step's integration tests: a fresh, isolated household (so a series or
 * a total can be asserted exactly, without noise from whatever other suites have already written
 * into the shared bootstrap household), and the net-worth CRUD calls built on the real endpoints.
 *
 * <p>Creating a second household with two SQL statements and then joining it through the real
 * registration endpoint is the same pattern {@code AccessControlIT#createSecondHousehold} uses,
 * duplicated here (not extracted to a shared support class) because it is a handful of lines and
 * this package is the only one that needs it for this reason — steps 8 and 10 have their own test
 * packages and their own fixtures.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class NetWorthTestSupport extends AuthFlowSupport {

    private final SecureRandom random = new SecureRandom();

    /** A brand-new household with one ADMIN member, joined through the real endpoints. */
    protected Session freshHousehold(String memberPrefix) {
        UUID householdId = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES (?, false) RETURNING id",
            UUID.class, "Net worth test household " + UUID.randomUUID());

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdId, InviteService.hash(token));

        return registerWithInvite(token, uniqueEmail(memberPrefix), MEMBER_PASSWORD);
    }

    /** Creates an asset via the real endpoint and returns the response body's {@code data}. */
    protected JsonNode createAsset(Session session, String name, String assetType, String value,
                                   String currency) {
        HttpResponse<String> response = postJson(appUrl("/v1/networth/assets"),
            Json.object("name", name, "assetType", assetType, "value", value, "currency",
                currency, "accountId", null),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("creating an asset failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /** Creates a liability via the real endpoint and returns the response body's {@code data}. */
    protected JsonNode createLiability(Session session, String name, String liabilityType,
                                       String balance, String currency) {
        HttpResponse<String> response = postJson(appUrl("/v1/networth/liabilities"),
            Json.object("name", name, "liabilityType", liabilityType, "balance", balance,
                "interestRate", null, "currency", currency, "accountId", null),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("creating a liability failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /** Takes a snapshot via the real endpoint and returns the response body's {@code data}. */
    protected JsonNode takeSnapshot(Session session) {
        HttpResponse<String> response = postJson(appUrl("/v1/networth/snapshot"), null,
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("taking a snapshot failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /** The series for this range, via the real endpoint. */
    protected JsonNode getSeries(Session session, String range) {
        HttpResponse<String> response = get(
            appUrl("/v1/networth/series" + (range == null ? "" : "?range=" + range)),
            session.accessToken());
        assertThat(response.statusCode())
            .as("reading the series failed: %s", response.body()).isEqualTo(200);
        return Json.data(response.body());
    }
}
