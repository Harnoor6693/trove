package finance.trove.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.auth.AuthMode;
import finance.trove.auth.jwt.AuthCookies;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup every collapse step-3 integration test starts from.
 *
 * <p>Deliberately no seeded rows. Each test walks the real flow — sign in as the bootstrapped
 * administrator, mint an invite, redeem it — because that flow is itself the thing under test and
 * because a fixture that inserted users directly would keep passing after the endpoint that
 * creates them broke. It is also the only way to get a credential row at all: argon2id hashes are
 * not something a SQL fixture can produce.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class AuthFlowSupport extends BaseIT {

    /** Keeps generated addresses unique: the IT database is shared across every IT class. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Satisfies the password policy: long, not deny-listed, not a repeated fragment. */
    public static final String MEMBER_PASSWORD = "a-perfectly-fine-passphrase";

    /** One authenticated bearer session. */
    public record Session(UUID userId, UUID householdId, String email, String accessToken,
                          String refreshToken) {
    }

    /** An address no other test has used, on a shared database that is never truncated. */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-"
            + UUID.randomUUID().toString().substring(0, 8) + "@trove.test";
    }

    // ------------------------------------------------------------------ bearer flows

    /** Signs in as the administrator {@code FirstRunBootstrap} created, in bearer mode. */
    protected Session loginBootstrapAdmin() {
        return loginBearer(BOOTSTRAP_ADMIN_EMAIL, BOOTSTRAP_ADMIN_PASSWORD);
    }

    /** Signs in in bearer mode and asserts the response carried a full session. */
    protected Session loginBearer(String email, String password) {
        HttpResponse<String> response = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", password),
            AuthMode.HEADER, "bearer");
        assertThat(response.statusCode())
            .as("login as %s failed: %s", email, response.body())
            .isEqualTo(200);
        return sessionFrom(response.body());
    }

    /** Mints an invite as an admin and returns the raw token from the creation response. */
    protected String createInvite(Session admin, String boundEmail) {
        HttpResponse<String> response = postJson(appUrl("/v1/admin/invites"),
            boundEmail == null ? "{}" : Json.object("email", boundEmail),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(response.statusCode())
            .as("creating an invite failed: %s", response.body())
            .isEqualTo(201);
        return Json.data(response.body()).path("token").asString();
    }

    /** Registers a new member against an invite, in bearer mode. */
    protected Session registerWithInvite(String inviteToken, String email, String password) {
        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", inviteToken, "email", email, "password", password,
                "displayName", "Test Member", "currency", "CAD", "country", "CA",
                "timezone", "America/Toronto"),
            AuthMode.HEADER, "bearer");
        assertThat(response.statusCode())
            .as("registering %s failed: %s", email, response.body())
            .isEqualTo(201);
        return sessionFrom(response.body());
    }

    /** Admin mints an invite, a new member redeems it. The ordinary way into a household. */
    protected Session inviteAndRegister(Session admin, String prefix) {
        String email = uniqueEmail(prefix);
        return registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
    }

    private static Session sessionFrom(String body) {
        JsonNode data = Json.data(body);
        assertThat(data.path("accessToken").asString(""))
            .as("bearer mode must return both tokens in the body")
            .isNotBlank();
        return new Session(
            UUID.fromString(data.path("user").path("id").asString()),
            UUID.fromString(data.path("user").path("householdId").asString()),
            data.path("user").path("email").asString(),
            data.path("accessToken").asString(),
            data.path("refreshToken").asString());
    }

    // ------------------------------------------------------------------ cookie flows

    /**
     * The CSRF token a browser would already hold.
     *
     * <p>Taken from any anonymous GET, because {@code CsrfCookieFilter} materialises the cookie on
     * every response — which is the whole reason that filter exists. The web client has no CSRF
     * bootstrap call and relies on the cookie being there before its very first login POST
     * ({@code apps/web/src/api/client.ts:15-17}).
     */
    protected String csrfToken() {
        HttpResponse<String> response = get(appUrl("/v1/auth/providers"));
        String token = cookieValue(response, "XSRF-TOKEN");
        assertThat(token)
            .as("without this cookie the SPA's first login 403s against a token that was never "
                + "written")
            .isNotNull();
        return token;
    }

    /** The headers a browser sends on a cookie-mode mutation. */
    protected String[] browserHeaders(String csrf, String cookies) {
        return new String[] {
            AuthMode.HEADER, "cookie",
            "X-Requested-With", "XMLHttpRequest",
            "X-XSRF-TOKEN", csrf,
            "Cookie", cookies,
        };
    }

    /** A cookie-mode login. Returns the raw response so a test can assert on the cookies. */
    protected HttpResponse<String> loginCookieMode(String email, String password) {
        String csrf = csrfToken();
        return postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", password),
            browserHeaders(csrf, cookieHeader("XSRF-TOKEN", csrf)));
    }

    /** The {@code Cookie} header a browser would send after a cookie-mode login. */
    protected String sessionCookies(HttpResponse<String> loginResponse, String csrf) {
        return cookieHeader(
            "XSRF-TOKEN", csrf,
            AuthCookies.ACCESS_COOKIE, cookieValue(loginResponse, AuthCookies.ACCESS_COOKIE),
            AuthCookies.REFRESH_COOKIE, cookieValue(loginResponse, AuthCookies.REFRESH_COOKIE));
    }
}
