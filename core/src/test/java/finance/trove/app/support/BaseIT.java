package finance.trove.app.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import finance.trove.app.TroveApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The single integration-test base class for the monolith — it replaces the nine per-service
 * {@code BaseIT} copies ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.14).
 *
 * <p>It boots the whole application on a random port against a throwaway
 * {@code pgvector/pgvector:pg16} container (the same image the deployment uses, §1.1) so that
 * Flyway runs for real on every run and the schema under test is the schema that ships.
 *
 * <p>Requests are made with the JDK HTTP client rather than {@code MockMvc} because two things
 * that step 1 has to prove are invisible to {@code MockMvc}: the {@code /api} servlet context
 * path, and the separate management port. The management port is bound to 0 here so that
 * parallel runs never collide with the 9090 of a locally running instance.
 */
@SpringBootTest(
    // Named explicitly rather than discovered. Spring Boot's default search walks UPWARDS from
    // the test's own package looking for a @SpringBootConfiguration, and TroveApplication lives
    // in finance.trove.app — which is not an ancestor of finance.trove.auth, finance.trove.users
    // or any other domain package. Every IT outside finance.trove.app would otherwise fail at
    // context creation with "Unable to find a @SpringBootConfiguration", which is a confusing
    // way to learn about a package layout.
    classes = TroveApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIT {

    /** The header Tomcat's {@code RemoteIpValve} reads; see {@link #assignClientAddress}. */
    public static final String FORWARDED_FOR = "X-Forwarded-For";

    /** Test-only signing secret; see {@link JwtTestHelper}. */
    public static final String JWT_SECRET = JwtTestHelper.SECRET;

    /** The bootstrapped administrator every IT starts from (collapse step 3, gap P2-7). */
    public static final String BOOTSTRAP_ADMIN_EMAIL = "admin@trove.test";

    /**
     * Long, not on the deny-list, not a repeated fragment, and does not contain the address'
     * local part — the four things {@code PasswordPolicy} refuses. A bootstrap password the
     * policy rejects would leave every IT with no account to sign in as.
     */
    public static final String BOOTSTRAP_ADMIN_PASSWORD = "Correct-Horse-Battery-9";

    /** Origin used to build invite and reset URLs; asserted verbatim by {@code InviteIT}. */
    public static final String PUBLIC_URL = "http://localhost:5173";

    /**
     * Test-only field-encryption key, 64 hex characters. From collapse step 2
     * {@code FieldEncryptionService} validates {@code TROVE_ENCRYPTION_KEY} during context
     * refresh, so every {@code @SpringBootTest} has to supply one or the application will not
     * start — which is the intended behaviour in production (§1.6).
     */
    public static final String ENCRYPTION_KEY =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    /**
     * Stands in for the {@code trove_data} volume. StorageConfig creates
     * {@code receipts/exports/uploads} beneath it on boot; the {@code diskSpace} readiness
     * contributor watches {@code receipts} (gap P1-1).
     */
    private static final Path DATA_DIR = createDataDir();

    /**
     * One Postgres for the whole suite, started once and never stopped by JUnit — the
     * "singleton container" pattern.
     *
     * <p>The obvious alternative, {@code @Testcontainers} plus {@code @Container} on this static
     * field, is wrong for a shared base class: that lifecycle stops the container when the
     * <em>first</em> subclass finishes, so the second IT class boots against a dead port and
     * every one of its tests fails with a Hikari timeout after 30 s. Collapse step 1 had exactly
     * one IT class, so the defect was latent; step 2 adds the second and surfaces it.
     *
     * <p>Nothing stops it explicitly: Testcontainers' Ryuk sidecar removes it when the JVM exits,
     * which is also what makes the container survive across the whole Failsafe run and keeps the
     * suite to one Postgres start rather than one per class.
     */
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("trove_core_test")
            .withUsername("trove")
            .withPassword("trove");

    static {
        POSTGRES.start();
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @LocalServerPort
    protected int port;

    @LocalManagementPort
    protected int managementPort;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * The address this test appears to come from. See {@link #assignClientAddress}.
     *
     * <p>Volatile because two integration tests are real two-thread races
     * ({@code InviteIT}'s redemption and {@code AccessControlIT}'s last-admin guard) and the
     * worker threads read it.
     */
    private volatile String clientAddress = defaultClientAddress();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("TROVE_DATA_DIR", DATA_DIR::toString);
        registry.add("TROVE_JWT_SECRET", () -> JWT_SECRET);
        registry.add("TROVE_ENCRYPTION_KEY", () -> ENCRYPTION_KEY);
        // Collapse step 3: the first context to start bootstraps the install's one household and
        // its ADMIN, exactly as a real first boot does. Every IT then works through the real
        // invite -> register flow from that account rather than seeding rows by hand, which is
        // also what keeps the flow itself under test on every run.
        registry.add("TROVE_ADMIN_EMAIL", () -> BOOTSTRAP_ADMIN_EMAIL);
        registry.add("TROVE_ADMIN_PASSWORD", () -> BOOTSTRAP_ADMIN_PASSWORD);
        registry.add("TROVE_PUBLIC_URL", () -> PUBLIC_URL);
        // 9090 is the deployed management port; a random one keeps the suite runnable next to
        // a locally running instance.
        registry.add("management.server.port", () -> "0");
    }

    /**
     * Gives every test method its own client address.
     *
     * <p><strong>Why this exists.</strong> The whole suite shares one Postgres, one context and
     * one socket — every request arrives from {@code 127.0.0.1}. From collapse step 4 that single
     * address is also a single rate-limit bucket and a single login-lockout bucket, so one class
     * that drives a hundred sign-ins (which {@code LockoutIT} does, on purpose) would spend the
     * limits of every class that ran after it, and the failure would surface as an unrelated test
     * answering 429. Tests would then pass or fail on execution order, which is the definition of
     * a flaky suite.
     *
     * <p>{@code 127.0.0.1} is inside {@code server.tomcat.remoteip.internal-proxies}, so Tomcat's
     * {@code RemoteIpValve} honours {@code X-Forwarded-For} from it exactly as it honours Traefik's
     * in production. Sending one per test method therefore does two things at once: it isolates the
     * per-address controls, and it means <em>every</em> request the suite makes exercises the
     * trusted-proxy path rather than the bare-socket path — the path that actually ships.
     *
     * <p>The address is derived from the test's identity, so it is stable across runs and a
     * failure names the same address twice. It is drawn from {@code 198.18.0.0/15} (RFC 2544
     * benchmarking), which is deliberately NOT one of the private ranges the internal-proxies
     * regex trusts: a test's synthetic address must never accidentally look like a proxy.
     *
     * <p>A test that wants a specific address, or several, passes its own {@code X-Forwarded-For}
     * header; {@link #exchange} leaves it alone.
     */
    @BeforeEach
    void assignClientAddress(TestInfo testInfo) {
        this.clientAddress = addressFor(getClass().getName() + "#"
            + testInfo.getTestMethod().map(java.lang.reflect.Method::getName).orElse("-"));
    }

    /** The address the current test appears to come from. */
    protected String clientAddress() {
        return clientAddress;
    }

    /**
     * A stable {@code 198.18.x.y} address for {@code identity}.
     *
     * <p>{@code 198.18.0.0/15} is 131,072 addresses and the suite has a few hundred tests, so a
     * collision is possible and harmless: two tests sharing an address are exactly the situation
     * that held for the whole suite before this method existed, and neither comes close to a
     * limit on its own.
     */
    static String addressFor(String identity) {
        int hash = identity.hashCode();
        return "198." + (18 + ((hash >>> 16) & 1)) + "." + ((hash >>> 8) & 0xFF) + "."
            + (hash & 0xFF);
    }

    /**
     * Used by the handful of requests made outside a test method's lifecycle — a
     * {@code @BeforeAll}, or a helper called from a constructor — where {@link TestInfo} has not
     * been assigned yet.
     */
    private static String defaultClientAddress() {
        return addressFor("trove-it-suite");
    }

    /** Absolute URL for an application path, context path included: {@code /v1/status}. */
    protected String appUrl(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    /** Absolute URL on the management port, which has no context path (§1.4). */
    protected String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }

    protected HttpResponse<String> get(String url) {
        return exchange("GET", url, null);
    }

    /** Same, with a bearer token — used from collapse step 3 onward. */
    protected HttpResponse<String> get(String url, String bearerToken) {
        return getWithHeader(url, "Authorization", "Bearer " + bearerToken);
    }

    /** A GET carrying one arbitrary header, for the request-id and CSRF contracts. */
    protected HttpResponse<String> getWithHeader(String url, String name, String value) {
        return exchange("GET", url, null, name, value);
    }

    /** A POST with a JSON body and any number of extra headers, given as name/value pairs. */
    protected HttpResponse<String> postJson(String url, String json, String... headers) {
        return exchange("POST", url, json, headers);
    }

    /** A PUT with a JSON body. */
    protected HttpResponse<String> putJson(String url, String json, String... headers) {
        return exchange("PUT", url, json, headers);
    }

    /** A DELETE, optionally with a JSON body — {@code DELETE /v1/auth/totp} carries one. */
    protected HttpResponse<String> delete(String url, String json, String... headers) {
        return exchange("DELETE", url, json, headers);
    }

    /** A GET with any number of extra headers. */
    protected HttpResponse<String> getWith(String url, String... headers) {
        return exchange("GET", url, null, headers);
    }

    /**
     * One part of a {@code multipart/form-data} upload.
     *
     * @param fieldName   the form field the part is sent under
     * @param filename    the filename to declare, or {@code null} for a plain value part
     * @param contentType the part's declared content type, or {@code null} for a plain value part
     * @param content     the part's bytes
     */
    public record Part(String fieldName, String filename, String contentType, byte[] content) {

        /** A file part. */
        public static Part file(String fieldName, String filename, String contentType,
                                byte[] content) {
            return new Part(fieldName, filename, contentType, content);
        }

        /** A plain form field. */
        public static Part field(String fieldName, String value) {
            return new Part(fieldName, null, null, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * A {@code multipart/form-data} POST — what {@code POST /v1/imports} takes and what
     * {@link #exchange} cannot express, because every other body on this API is a JSON string.
     *
     * <p><strong>The body is built as bytes, not as a string</strong>, and that is load-bearing:
     * one of the OFX fixtures is windows-1252 and re-encoding it through UTF-16 on the way to the
     * socket would destroy exactly the bytes the test exists to prove are handled.
     *
     * <p>The body still reaches {@link OpenApiConformance} as text, decoded ISO-8859-1 so that
     * every byte survives the round trip. The validator does not schema-check a multipart body —
     * it is neither JSON nor url-encoded form data — but it does check that the operation declares
     * {@code multipart/form-data} and that a required body is present, so the part still has to be
     * real. No conformance opt-out is needed anywhere in this step.
     */
    protected HttpResponse<String> postMultipart(String url, List<Part> parts, String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers must be name/value pairs");
        }
        String boundary = "TroveBoundary" + java.util.UUID.randomUUID();
        byte[] body = multipartBody(parts, boundary);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        boolean carriesForwardedFor = false;
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
            carriesForwardedFor |= FORWARDED_FOR.equalsIgnoreCase(headers[i]);
        }
        if (!carriesForwardedFor) {
            builder.header(FORWARDED_FOR, clientAddress);
        }
        HttpRequest request = builder
            .method("POST", HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return send(request, new String(body, StandardCharsets.ISO_8859_1));
    }

    private static byte[] multipartBody(List<Part> parts, String boundary) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (Part part : parts) {
                StringBuilder head = new StringBuilder("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"")
                    .append(part.fieldName()).append('"');
                if (part.filename() != null) {
                    head.append("; filename=\"").append(part.filename()).append('"');
                }
                head.append("\r\n");
                if (part.contentType() != null) {
                    head.append("Content-Type: ").append(part.contentType()).append("\r\n");
                }
                head.append("\r\n");
                out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                out.write(part.content());
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * The one request builder every helper above funnels through.
     *
     * @param json    the request body, or {@code null} for none. An empty string sends a
     *                zero-length body with a JSON content type, which is what an axios
     *                {@code post(url, null)} produces on the wire and therefore what
     *                {@code /v1/auth/refresh} has to tolerate
     * @param headers name/value pairs; an odd count is a programming error and fails loudly
     */
    protected HttpResponse<String> exchange(String method, String url, String json,
                                            String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("headers must be name/value pairs");
        }
        URI target = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(30));
        boolean carriesForwardedFor = false;
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
            carriesForwardedFor |= FORWARDED_FOR.equalsIgnoreCase(headers[i]);
        }
        // Only for the application port. The management port's callers are machinery — the Docker
        // HEALTHCHECK and a Prometheus scrape — and the tests that probe its authorisation want to
        // choose their own address, on both sides of the trusted-network boundary.
        if (!carriesForwardedFor && target.getPort() == port) {
            builder.header(FORWARDED_FOR, clientAddress);
        }
        HttpRequest.BodyPublisher body = json == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json);
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        return send(builder.method(method, body).build(), json);
    }

    /**
     * Every {@code Set-Cookie} value on a response whose name matches, in order.
     *
     * <p>Returned raw rather than parsed because the attributes are the assertion in several
     * tests: {@code __Host-} requires {@code Secure} and {@code Path=/}, the CSRF cookie must
     * NOT be {@code HttpOnly}, and {@code SameSite} must be {@code Lax} rather than
     * {@code Strict} or the OIDC redirect lands logged out (§1.5).
     */
    protected static List<String> setCookies(HttpResponse<String> response, String name) {
        return response.headers().allValues("set-cookie").stream()
            .filter(value -> value.startsWith(name + "="))
            .toList();
    }

    /** The value of a {@code Set-Cookie}, or null when the response did not set that cookie. */
    protected static String cookieValue(HttpResponse<String> response, String name) {
        return setCookies(response, name).stream()
            .map(header -> header.substring(name.length() + 1))
            .map(rest -> {
                int semicolon = rest.indexOf(';');
                return semicolon < 0 ? rest : rest.substring(0, semicolon);
            })
            .filter(value -> !value.isEmpty())
            .findFirst()
            .orElse(null);
    }

    /** Joins cookies into one {@code Cookie} header value. */
    protected static String cookieHeader(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("cookies must be name/value pairs");
        }
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (nameValuePairs[i + 1] == null) {
                continue;
            }
            if (header.length() > 0) {
                header.append("; ");
            }
            header.append(nameValuePairs[i]).append('=').append(nameValuePairs[i + 1]);
        }
        return header.toString();
    }

    /**
     * The single point every request and response passes through, and therefore the single point
     * at which the API contract is enforced.
     *
     * <p>Anything addressed to the application port is validated against
     * {@code openapi/trove-core.yaml} — request and response, both directions, strictly — by
     * {@link OpenApiConformance}. Two other destinations exist and are excluded deliberately
     * rather than by omission:
     * <ul>
     *   <li>the <strong>management port</strong>, whose {@code /actuator} endpoints are Spring
     *       Boot's contract and not Trove's; the core document describes {@code /api/v1} and
     *       says so in its header. {@code GET /v1/status} is the public projection of that
     *       information and <em>is</em> in the contract;</li>
     *   <li>any <strong>other port</strong> — there is none, so reaching one is a bug in the
     *       test rather than a case to skip, and it fails here.</li>
     * </ul>
     */
    private HttpResponse<String> send(HttpRequest request, String requestBody) {
        HttpResponse<String> response = exchangeRaw(request);
        int destination = request.uri().getPort();
        if (destination == port) {
            OpenApiConformance.verify(request, requestBody, response);
        } else if (destination != managementPort) {
            throw new IllegalStateException("A test called " + request.uri() + ", which is "
                + "neither the application port (" + port + ") nor the management port ("
                + managementPort + "). Nothing else is part of this application.");
        }
        return response;
    }

    private HttpResponse<String> exchangeRaw(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + request.uri(), e);
        }
    }

    /**
     * Makes one call with request validation waived, because the request deliberately breaks the
     * contract to prove what the server answers. The response is still validated in full.
     *
     * @see OpenApiConformance#withoutRequestValidation(String, Supplier)
     */
    protected static <T> T withoutRequestValidation(String reason, Supplier<T> call) {
        return OpenApiConformance.withoutRequestValidation(reason, call);
    }

    /**
     * Makes one call to an interaction {@code openapi/trove-core.yaml} deliberately does not
     * describe — an unmapped path, or a method no operation defines.
     *
     * @see OpenApiConformance#outsideTheContract(String, Supplier)
     */
    protected static <T> T outsideTheContract(String reason, Supplier<T> call) {
        return OpenApiConformance.outsideTheContract(reason, call);
    }

    private static Path createDataDir() {
        try {
            return Files.createTempDirectory("trove-core-it-data");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
