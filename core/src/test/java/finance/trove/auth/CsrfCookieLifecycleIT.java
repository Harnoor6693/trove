package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.totp.TotpGenerator;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code XSRF-TOKEN} cookie across a whole cookie-mode session, the way a browser holds it
 * (D1-01).
 *
 * <p>{@link CsrfIT} pins each CSRF piece one request at a time, with the token handed in by the
 * test. That is how D1-01 went unseen: every successful authenticated request answered with a
 * {@code Set-Cookie} that <em>deleted</em> the token, so the SPA's next write went out without
 * {@code X-XSRF-TOKEN} and was refused (201, 403, 201 for three {@code POST /v1/accounts} in a
 * row; the import wizard's commit failed on first use). The cause was Spring's
 * {@code SessionManagementFilter}, which {@code sessionCreationPolicy(STATELESS)} switches on: with
 * nothing in a security-context repository, every JWT-authenticated request looked like a fresh
 * login, and {@code CsrfAuthenticationStrategy} cleared the token on each one.
 *
 * <p>So these tests keep a cookie jar and apply every {@code Set-Cookie} — deletions included —
 * before the next request, and send {@code X-XSRF-TOKEN} only when the jar holds the cookie,
 * exactly as {@code apps/web/src/api/client.ts} does.
 */
class CsrfCookieLifecycleIT extends AuthFlowSupport {

    private static final String XSRF = "XSRF-TOKEN";
    private static final String ACCOUNT = Json.object(
        "sourceKind", "MANUAL", "displayName", "Everyday", "accountType", "CHECKING");

    /** What the browser holds, in insertion order. */
    private final Map<String, String> jar = new LinkedHashMap<>();

    private Session admin;
    private String email;

    @BeforeEach
    void member() {
        admin = loginBootstrapAdmin();
        email = uniqueEmail("csrf-life");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
        jar.clear();
    }

    @Test
    @DisplayName("Three consecutive cookie-mode writes all succeed and the token survives each")
    void consecutiveWritesKeepTheToken() {
        logIn();
        String token = jar.get(XSRF);

        for (int write = 1; write <= 3; write++) {
            HttpResponse<String> response = send("POST", "/v1/accounts", ACCOUNT, true);

            assertThat(response.statusCode())
                .as("write %d of 3: the import wizard's preview-then-commit, or adding two "
                    + "things in a row, is two writes (D1-01 measured 201, 403, 201)", write)
                .isEqualTo(201);
            assertThat(jar.get(XSRF))
                .as("after write %d the browser must still hold the same token: a response "
                    + "that deletes it leaves the SPA's next write with no X-XSRF-TOKEN", write)
                .isEqualTo(token);
        }
    }

    @Test
    @DisplayName("The token is there right after login and authenticated GETs leave it alone")
    void theTokenIsThereRightAfterLogin() {
        logIn();
        assertThat(jar).as("right after login").containsKey(XSRF);
        String token = jar.get(XSRF);

        for (int read = 1; read <= 2; read++) {
            HttpResponse<String> me = send("GET", "/v1/auth/me", null, true);

            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(xsrfSetCookies(me))
                .as("GET %d: an authenticated read has no reason to touch the token, and the "
                    + "SPA hydrates with GET /v1/auth/me straight after login", read)
                .isEmpty();
        }
        assertThat(jar.get(XSRF)).isEqualTo(token);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true).statusCode())
            .as("the first write after login and hydration")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("A cookie-mode write without the token header, or with the wrong one, is still 403")
    void writesStillNeedTheHeader() {
        logIn();

        assertThat(send("POST", "/v1/accounts", ACCOUNT, false).statusCode())
            .as("the cookie alone proves nothing: a cross-site form post carries it too")
            .isEqualTo(403);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true, "not-the-token").statusCode())
            .as("a header that does not match the cookie")
            .isEqualTo(403);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true).statusCode())
            .as("and the matching pair still works afterwards")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("Signing in replaces the token: one from before sign-in no longer passes")
    void signInReplacesTheToken() {
        assertThat(send("GET", "/v1/status", null, false).statusCode()).isEqualTo(200);
        String before = jar.get(XSRF);

        HttpResponse<String> login = send("POST", "/v1/auth/login",
            Json.object("email", email, "password", MEMBER_PASSWORD), true);

        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        assertFreshToken(before);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true, before).statusCode())
            .as("a token learned or planted before sign-in must not carry over into the session "
                + "(OWASP: a new CSRF token on authentication)")
            .isEqualTo(403);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Registering signs the member in, so it replaces the token too")
    void registrationReplacesTheToken() {
        assertThat(send("GET", "/v1/status", null, false).statusCode()).isEqualTo(200);
        String before = jar.get(XSRF);

        HttpResponse<String> register = send("POST", "/v1/auth/register", Json.object(
            "inviteToken", createInvite(admin, null), "email", uniqueEmail("csrf-reg"),
            "password", MEMBER_PASSWORD, "displayName", "New Member", "currency", "CAD",
            "country", "CA", "timezone", "America/Toronto"), true);

        assertThat(register.statusCode()).as(register.body()).isEqualTo(201);
        assertFreshToken(before);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true, before).statusCode())
            .isEqualTo(403);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("Completing the TOTP step signs the member in, so it replaces the token")
    void totpCompletionReplacesTheToken() {
        String secret = enrolTotp();
        assertThat(send("GET", "/v1/status", null, false).statusCode()).isEqualTo(200);
        HttpResponse<String> login = send("POST", "/v1/auth/login",
            Json.object("email", email, "password", MEMBER_PASSWORD), true);
        String challenge = Json.data(login.body()).path("challengeToken").asString();
        assertThat(challenge).as("a second factor is owed: %s", login.body()).isNotBlank();
        String before = jar.get(XSRF);

        HttpResponse<String> stepUp = send("POST", "/v1/auth/login/totp",
            Json.object("challengeToken", challenge, "code", totpCode(secret, 1)), true);

        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(200);
        assertFreshToken(before);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true, before).statusCode())
            .isEqualTo(403);
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("A refresh continues the session and keeps the token")
    void refreshKeepsTheToken() {
        logIn();
        String token = jar.get(XSRF);

        HttpResponse<String> refresh = send("POST", "/v1/auth/refresh", "{}", true);

        assertThat(refresh.statusCode()).as(refresh.body()).isEqualTo(200);
        assertThat(xsrfSetCookies(refresh))
            .as("same refresh family, so the same session, not a sign-in. And the SPA replays "
                + "the request that 401'd with the X-XSRF-TOKEN it was first sent with "
                + "(client.ts), so a token replaced here would make every replayed write a 403")
            .isEmpty();
        assertThat(send("POST", "/v1/accounts", ACCOUNT, true, token).statusCode())
            .isEqualTo(201);
    }

    @Test
    @DisplayName("The chain is still stateless: no response creates an HTTP session")
    void noSessionIsEverCreated() {
        // Not a D1-01 symptom (it passes before the fix too); it guards the fix. The fix moves STATELESS off the session-management configurer (whose setter is what
        // switched SessionManagementFilter on). An anonymous GET of a protected route that is not
        // an XHR and does not ask for JSON is the request a session-backed request cache saves,
        // so it is the one that would mint a JSESSIONID if the policy had been lost on the way.
        HttpResponse<String> navigation = get(appUrl("/v1/auth/me"));
        assertThat(navigation.statusCode()).isEqualTo(401);
        assertThat(navigation.headers().allValues("set-cookie"))
            .noneMatch(header -> header.startsWith("JSESSIONID="));

        logIn();
        HttpResponse<String> write = send("POST", "/v1/accounts", ACCOUNT, true);
        assertThat(write.statusCode()).isEqualTo(201);
        assertThat(write.headers().allValues("set-cookie"))
            .noneMatch(header -> header.startsWith("JSESSIONID="));
    }

    // ------------------------------------------------------------------ the browser

    /** An anonymous first page load, then the SPA's cookie-mode login. */
    private void logIn() {
        assertThat(send("GET", "/v1/status", null, false).statusCode()).isEqualTo(200);
        assertThat(jar).as("an anonymous GET materialises the token").containsKey(XSRF);

        HttpResponse<String> login = send("POST", "/v1/auth/login",
            Json.object("email", email, "password", MEMBER_PASSWORD), true);
        assertThat(login.statusCode()).as("cookie-mode login: %s", login.body()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String json,
                                      boolean echoToken) {
        return send(method, path, json, echoToken, jar.get(XSRF));
    }

    /**
     * One request with the jar's cookies and the SPA's headers. The token header goes out only when
     * {@code echoToken} is set and there is a value to echo — the client reads it from
     * {@code document.cookie}, so a deleted cookie means no header.
     */
    private HttpResponse<String> send(String method, String path, String json,
                                      boolean echoToken, String headerValue) {
        List<String> headers = new ArrayList<>(List.of(
            AuthMode.HEADER, "cookie",
            "X-Requested-With", "XMLHttpRequest"));
        if (echoToken && headerValue != null) {
            headers.addAll(List.of("X-XSRF-TOKEN", headerValue));
        }
        if (!jar.isEmpty()) {
            headers.addAll(List.of("Cookie", jar.entrySet().stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "))));
        }
        HttpResponse<String> response =
            exchange(method, appUrl(path), json, headers.toArray(String[]::new));
        remember(response);
        return response;
    }

    /** Applies every {@code Set-Cookie} in order, as a browser does; an expiring one deletes. */
    private void remember(HttpResponse<String> response) {
        for (String header : response.headers().allValues("set-cookie")) {
            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            boolean expires = value.isEmpty() || header.contains("Max-Age=0");
            if (expires) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    /** Enrols the member in TOTP over bearer, as {@code TotpFlowIT} does; returns the secret. */
    private String enrolTotp() {
        String bearer = "Bearer " + loginBearer(email, MEMBER_PASSWORD).accessToken();
        HttpResponse<String> setup = postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD), "Authorization", bearer);
        assertThat(setup.statusCode()).as(setup.body()).isEqualTo(200);
        String secret = Json.data(setup.body()).path("secret").asString();
        HttpResponse<String> confirm = postJson(appUrl("/v1/auth/totp/confirm"),
            Json.object("code", totpCode(secret, 0)), "Authorization", bearer);
        assertThat(confirm.statusCode()).as(confirm.body()).isEqualTo(200);
        return secret;
    }

    /** A code {@code offset} steps ahead: a step spent at enrolment is refused at login. */
    private static String totpCode(String secret, int offset) {
        TotpGenerator generator = new TotpGenerator();
        return generator.codeAt(secret, generator.timeStep(Instant.now(), 30) + offset, 6);
    }

    private void assertFreshToken(String before) {
        assertThat(jar.get(XSRF))
            .as("the response that signs the browser in plants a new token, so the SPA can write "
                + "straight away, with a value the page before sign-in never saw")
            .isNotNull()
            .isNotEqualTo(before);
    }

    private static List<String> xsrfSetCookies(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
            .filter(header -> header.startsWith(XSRF + "="))
            .toList();
    }
}
