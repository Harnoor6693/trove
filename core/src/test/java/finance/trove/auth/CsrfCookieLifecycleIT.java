package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
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

    private String email;

    @BeforeEach
    void member() {
        Session admin = loginBootstrapAdmin();
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

    private static List<String> xsrfSetCookies(HttpResponse<String> response) {
        return response.headers().allValues("set-cookie").stream()
            .filter(header -> header.startsWith(XSRF + "="))
            .toList();
    }
}
