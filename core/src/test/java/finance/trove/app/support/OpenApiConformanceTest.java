package finance.trove.app.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link OpenApiConformance} bites.
 *
 * <p>A conformance check wired into a base class is exactly the kind of machinery that can be
 * silently inert — a spec that failed to load, a level resolver that downgraded everything to
 * {@code WARN}, a body that was never handed to the validator — and every integration test would
 * go on passing while proving nothing about the contract. So this test feeds the real validation
 * point, loaded from the real {@code openapi/trove-core.yaml}, responses that are deliberately
 * wrong in each of the ways a server drifts, and requires every one of them to fail.
 *
 * <p>It is a unit test, not an IT: it needs no Postgres and no context, only the spec file and a
 * hand-built request/response pair, so the proof runs in Surefire on every build in about a
 * second.
 */
class OpenApiConformanceTest {

    private static final URI STATUS = URI.create("http://localhost:8080/api/v1/status");
    private static final URI ME = URI.create("http://localhost:8080/api/v1/auth/me");
    private static final URI LOGIN = URI.create("http://localhost:8080/api/v1/auth/login");
    private static final URI UNMAPPED = URI.create("http://localhost:8080/api/v1/households");

    private static final String GOOD_STATUS =
        "{\"status\":\"UP\",\"version\":\"1.0.0\",\"time\":\"2026-09-04T14:27:31.482913Z\"}";

    @Test
    @DisplayName("The contract the ITs validate against is the file in openapi/")
    void theSpecIsTheRepositoryFile() throws java.io.IOException {
        assertThat(OpenApiConformance.SPEC).isRegularFile();
        assertThat(OpenApiConformance.SPEC.toString()).endsWith("openapi/trove-core.yaml");
        assertThat(Files.readString(OpenApiConformance.SPEC)).contains("openapi: 3.0.3");
    }

    @Test
    @DisplayName("A response that matches the spec passes")
    void aConformingResponsePasses() {
        assertThatCode(() -> verify(get(STATUS), json(200, GOOD_STATUS)))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A missing required field fails")
    void aMissingRequiredFieldFails() {
        assertThatThrownBy(() -> verify(get(STATUS),
            json(200, "{\"status\":\"UP\",\"time\":\"2026-09-04T14:27:31.482913Z\"}")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("required property 'version' not found");
    }

    @Test
    @DisplayName("An extra field on a closed schema fails")
    void anUndeclaredFieldFails() {
        assertThatThrownBy(() -> verify(get(STATUS),
            json(200, "{\"status\":\"UP\",\"version\":\"1.0.0\","
                + "\"time\":\"2026-09-04T14:27:31.482913Z\",\"uptimeSeconds\":41}")))
            .isInstanceOf(AssertionError.class)
            .as("ServerStatus is additionalProperties: false, and a field the clients cannot "
                + "know about is drift whether or not it is useful")
            .hasMessageContaining("uptimeSeconds");
    }

    @Test
    @DisplayName("A value outside an enum fails")
    void anUndeclaredEnumValueFails() {
        assertThatThrownBy(() -> verify(get(STATUS),
            json(200, "{\"status\":\"WOBBLY\",\"version\":\"1.0.0\","
                + "\"time\":\"2026-09-04T14:27:31.482913Z\"}")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("enum");
    }

    @Test
    @DisplayName("A status the operation does not declare fails")
    void anUndeclaredStatusFails() {
        assertThatThrownBy(() -> verify(get(STATUS), json(418, "{}")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Response status 418 not defined");
    }

    @Test
    @DisplayName("An error slug this operation cannot carry fails")
    void anErrorSlugOutsideTheOperationsListFails() {
        assertThatCode(() -> verify(get(ME), json(401, error("unauthorized"))))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> verify(get(ME), json(401, error("credentials-invalid"))))
            .isInstanceOf(AssertionError.class)
            .as("the slug is what both clients switch on, so 'which slugs can appear here' is "
                + "part of the contract and not decoration")
            .hasMessageContaining("enum");
    }

    @Test
    @DisplayName("An enveloped success missing its data fails")
    void anEnvelopeWithoutItsDataFails() {
        assertThatThrownBy(() -> verify(get(ME), json(200, "{\"success\":true}")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("required property 'data' not found");
    }

    @Test
    @DisplayName("A reserved-TLD address is accepted and a malformed one is not")
    void emailIsCheckedByShapeAndNotByPublicSuffix() {
        assertThatCode(() -> verify(get(ME), json(200, me("admin@trove.test"))))
            .as("the server checks addresses with Jakarta @Email, which says nothing about the "
                + "domain being registrable. A contract that refused an install's internal "
                + "domain would be lying about the server")
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> verify(get(ME), json(200, me("no-at-sign"))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("pattern");
    }

    @Test
    @DisplayName("A malformed uuid in a response fails")
    void otherFormatsStillBite() {
        assertThatThrownBy(() -> verify(get(ME),
            json(200, me("member@trove.test").replace(
                "00000000-0000-4000-8000-000000000001", "not-a-uuid"))))
            .isInstanceOf(AssertionError.class)
            .as("dropping the email format assertion must not have dropped the others")
            .hasMessageContaining("uuid");
    }

    @Test
    @DisplayName("withoutRequestValidation waives the request and keeps the response")
    void theRequestOptOutStillValidatesTheResponse() {
        HttpRequest badLogin = post(LOGIN);
        String malformed = "{\"email\":\"not-an-email\",\"password\":\"\"}";

        assertThatCode(() -> OpenApiConformance.withoutRequestValidation(
            "proves a malformed login is answered 400, not 422",
            () -> verify(badLogin, malformed, json(400, error("validation-error")))))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> OpenApiConformance.withoutRequestValidation(
            "the response is still the contract",
            () -> verify(badLogin, malformed, json(499, "{}"))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Response status 499 not defined");
    }

    @Test
    @DisplayName("An opt-out on a request that was valid all along fails")
    void anUnnecessaryRequestOptOutFails() {
        assertThatThrownBy(() -> OpenApiConformance.withoutRequestValidation(
            "not actually invalid",
            () -> verify(get(STATUS), json(200, GOOD_STATUS))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("The opt-out is not needed");
    }

    @Test
    @DisplayName("An opt-out that is never used fails")
    void anUnusedOptOutFails() {
        assertThatThrownBy(() -> OpenApiConformance.withoutRequestValidation(
            "makes no request at all", () -> "nothing"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("was never used");
    }

    @Test
    @DisplayName("outsideTheContract accepts an unmapped path and refuses a described one")
    void theInteractionOptOutOnlyCoversWhatIsUndescribed() {
        assertThatCode(() -> OpenApiConformance.outsideTheContract(
            "no controller serves /v1/households; the 401 proves the security chain's body",
            () -> verify(get(UNMAPPED), json(401, error("unauthorized")))))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> OpenApiConformance.outsideTheContract(
            "but this route is described",
            () -> verify(get(STATUS), json(200, GOOD_STATUS))))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("does describe this route");
    }

    // ---------------------------------------------------------------- fixtures

    private static Object verify(HttpRequest request, HttpResponse<String> response) {
        return verify(request, null, response);
    }

    private static Object verify(HttpRequest request, String body,
                                 HttpResponse<String> response) {
        OpenApiConformance.verify(request, body, response);
        return null;
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).GET().build();
    }

    private static HttpRequest post(URI uri) {
        return HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    }

    /** A {@code GET /v1/auth/me} body, whose {@code MeResponse} carries an address and ids. */
    private static String me(String email) {
        return "{\"success\":true,\"data\":{"
            + "\"userId\":\"00000000-0000-4000-8000-000000000001\","
            + "\"email\":\"" + email + "\",\"displayName\":\"Example Member\","
            + "\"householdId\":\"00000000-0000-4000-8000-0000000000a1\","
            + "\"role\":\"MEMBER\",\"totpEnabled\":false,"
            + "\"currency\":\"CAD\",\"country\":\"CA\","
            + "\"timezone\":\"America/Halifax\",\"locale\":\"en-CA\","
            + "\"onboardingCompleted\":true}}";
    }

    private static String error(String slug) {
        return "{\"success\":false,\"error\":{"
            + "\"type\":\"https://trove.finance/errors/" + slug + "\","
            + "\"title\":\"Refused\",\"status\":401,\"detail\":\"No usable session.\","
            + "\"instance\":\"/api/v1/auth/me\","
            + "\"requestId\":\"99999999-8888-4777-8666-555555555555\"}}";
    }

    private static HttpResponse<String> json(int status, String body) {
        return new StubResponse(status, body,
            HttpHeaders.of(Map.of("content-type", List.of("application/json")), (a, b) -> true));
    }

    /**
     * The parts of {@link HttpResponse} the conformance check reads. Written out rather than
     * mocked so that what the validator is handed is visible in this file.
     */
    private record StubResponse(int status, String body, HttpHeaders headers)
        implements HttpResponse<String> {

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return STATUS;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
