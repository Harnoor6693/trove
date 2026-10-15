package finance.trove.app.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Validates every HTTP interaction an integration test makes against
 * {@code openapi/trove-core.yaml} — the owner's "OpenAPI-first" decision of 2026-09-04, which
 * says the spec is the single source of truth for every {@code /api/v1} route and that server
 * conformance is <em>tested</em>, not assumed.
 *
 * <p>{@link BaseIT#exchange} funnels every helper through one {@code send}, and {@code send}
 * calls {@link #verify} on the way out. So a route that drifts from the spec — a renamed field, a
 * status nobody wrote down, an extra property on a closed schema, a request the contract forbids
 * — fails the test that exercised it, at the call site, with the offending body in the message.
 *
 * <h2>What is checked</h2>
 * The validator resolves the request against the spec's {@code servers: [{url: /api}]} base path
 * and then checks, in both directions: the path and method exist; path, query, header and cookie
 * parameters match their schemas; the request body matches its schema; the response status is one
 * the operation declares; response headers the spec models are present when required; and the
 * response body matches the schema for that status — including {@code additionalProperties:
 * false}, so an unknown field in a closed response schema is a failure.
 *
 * <h2>The one level override</h2>
 * {@code validation.schema.additionalProperties} is set to {@code IGNORE}. That key is not a
 * validation message: inside the validator it is the on/off switch for
 * {@code AdditionalPropertiesInjectionTransformer}, which otherwise <em>injects</em>
 * {@code additionalProperties: false} into every schema node that declares {@code properties} and
 * does not say otherwise. On a document like this one — where the envelope is composed with
 * {@code allOf}, deliberately, so that generated clients get an exact {@code data} type — that
 * injection closes each {@code allOf} branch against its siblings and every enveloped body on the
 * API fails: the branch that contributes {@code success} rejects {@code data}, and the branch that
 * contributes {@code data} rejects {@code success}. Switching the injection off leaves the
 * <em>spec's own</em> {@code additionalProperties: false} in force and nothing else, which is
 * exactly the strictness the author wrote down: response schemas closed, request schemas open
 * (the server runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, and a closed request schema
 * would describe a stricter server than the one that ships).
 *
 * <h2>Opting out</h2>
 * Two escape hatches, both scoped to a single call, both self-invalidating — each one asserts
 * that the thing it excuses really is true, so an opt-out that has stopped being necessary fails
 * the build instead of quietly hiding a regression:
 * <ul>
 *   <li>{@link #withoutRequestValidation} — the test deliberately sends a request the contract
 *       forbids (a malformed body, an out-of-range field) to prove what the server answers. The
 *       <em>response</em> is still validated in full. If the request turns out to be valid after
 *       all, the call fails and tells you to remove the opt-out.</li>
 *   <li>{@link #outsideTheContract} — the interaction is not described by the document at all:
 *       an unmapped path, or a known path with a method no operation defines. These are the
 *       answers listed under "what is deliberately not in here" in the spec's own header. If the
 *       path <em>is</em> in the contract, the call fails.</li>
 * </ul>
 * Neither hatch can be left switched on: it is consumed by the first interaction inside the
 * lambda, and a lambda that makes no request at all fails.
 */
public final class OpenApiConformance {

    /** The contract. Resolved once; every IT in the JVM validates against this file. */
    public static final Path SPEC = locateSpec();

    /**
     * Built once per JVM. Parsing and schema-compiling the document costs about a quarter of a
     * second, which is fine once and not fine on each of the several thousand interactions a
     * full Failsafe run makes.
     */
    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
        .createFor(SPEC.toAbsolutePath().toString())
        .withLevelResolver(LevelResolver.create()
            .withLevel("validation.schema.additionalProperties", ValidationReport.Level.IGNORE)
            .build())
        .build();

    /** How much of an offending body to quote before the message becomes unreadable. */
    private static final int BODY_EXCERPT = 2000;

    private static final ThreadLocal<Relaxation> RELAXATION = new ThreadLocal<>();

    private OpenApiConformance() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Which half of the check one call is excused from, and the reason it gave. */
    private record Relaxation(Kind kind, String reason) {
        enum Kind { REQUEST, INTERACTION }
    }

    /**
     * Runs one call with <strong>request</strong> validation switched off; the response is still
     * validated against the spec in full.
     *
     * <p>For the tests whose subject is what the server does with input the contract forbids —
     * {@code AuthFlowIT}'s malformed login, the validation tests, the type-confusion probes. The
     * answer to a bad request is part of the contract and stays under test; only the question is
     * excused.
     *
     * @param reason why this particular request is deliberately invalid. Every use must state
     *               its reason here
     * @param call   the single call to make. Must make exactly one request through {@link BaseIT}
     */
    public static <T> T withoutRequestValidation(String reason, Supplier<T> call) {
        return relaxed(new Relaxation(Relaxation.Kind.REQUEST, reason), call);
    }

    /**
     * Runs one call with both halves switched off, for an interaction the document deliberately
     * does not describe: a path no operation serves, or a method no operation defines on a path
     * that exists. The call still fails if the path <em>is</em> in the contract.
     *
     * @param reason why this interaction is outside the contract
     * @param call   the single call to make
     */
    public static <T> T outsideTheContract(String reason, Supplier<T> call) {
        return relaxed(new Relaxation(Relaxation.Kind.INTERACTION, reason), call);
    }

    private static <T> T relaxed(Relaxation relaxation, Supplier<T> call) {
        if (RELAXATION.get() != null) {
            throw new IllegalStateException(
                "A conformance opt-out is already active (" + RELAXATION.get().reason()
                    + "). They do not nest: each one covers exactly one call.");
        }
        RELAXATION.set(relaxation);
        try {
            T result = call.get();
            if (RELAXATION.get() != null) {
                throw new AssertionError(
                    "The conformance opt-out \"" + relaxation.reason() + "\" was never used: the "
                        + "call made no request through BaseIT. Remove it.");
            }
            return result;
        } finally {
            RELAXATION.remove();
        }
    }

    /**
     * The one validation point. Called by {@link BaseIT} for every request it sends to the
     * application port, after the response has arrived.
     *
     * @param request     the request as it went on the wire
     * @param requestBody its body, or {@code null} when it had none
     * @param response    the answer
     */
    static void verify(HttpRequest request, String requestBody, HttpResponse<String> response) {
        Relaxation relaxation = RELAXATION.get();
        if (relaxation != null) {
            RELAXATION.remove();
        }
        SimpleRequest apiRequest = toApiRequest(request, requestBody);
        String path = request.uri().getPath();
        Request.Method method = Request.Method.valueOf(
            request.method().toUpperCase(Locale.ROOT));

        if (relaxation == null) {
            report(VALIDATOR.validate(apiRequest, toApiResponse(response)),
                request, response, null);
            return;
        }
        switch (relaxation.kind()) {
            case REQUEST -> {
                requireRequestIsActuallyInvalid(apiRequest, request, relaxation);
                report(VALIDATOR.validateResponse(path, method, toApiResponse(response)),
                    request, response, relaxation);
            }
            case INTERACTION -> requireInteractionIsActuallyUnknown(apiRequest, request,
                relaxation);
            default -> throw new IllegalStateException("Unknown relaxation " + relaxation.kind());
        }
    }

    private static void requireRequestIsActuallyInvalid(SimpleRequest apiRequest,
                                                        HttpRequest request,
                                                        Relaxation relaxation) {
        if (!VALIDATOR.validateRequest(apiRequest).hasErrors()) {
            throw new AssertionError(describe(request, null)
                + "\n\nThis call is wrapped in withoutRequestValidation(\"" + relaxation.reason()
                + "\"), but the request conforms to openapi/trove-core.yaml. The opt-out is not "
                + "needed — remove it, so that a request which later stops conforming is caught.");
        }
    }

    private static void requireInteractionIsActuallyUnknown(SimpleRequest apiRequest,
                                                            HttpRequest request,
                                                            Relaxation relaxation) {
        boolean unknown = VALIDATOR.validateRequest(apiRequest).getMessages().stream()
            .map(ValidationReport.Message::getKey)
            .anyMatch(key -> key.equals("validation.request.path.missing")
                || key.equals("validation.request.operation.notAllowed"));
        if (!unknown) {
            throw new AssertionError(describe(request, null)
                + "\n\nThis call is wrapped in outsideTheContract(\"" + relaxation.reason()
                + "\"), but openapi/trove-core.yaml does describe this route. The opt-out hides a "
                + "real contract — remove it and let the interaction be validated.");
        }
    }

    private static void report(ValidationReport validation, HttpRequest request,
                               HttpResponse<String> response, Relaxation relaxation) {
        if (!validation.hasErrors()) {
            return;
        }
        StringBuilder message = new StringBuilder("openapi/trove-core.yaml does not describe "
            + "what the server did.\n\n").append(describe(request, response));
        if (relaxation != null) {
            message.append("\n\nRequest validation was waived: ").append(relaxation.reason());
        }
        message.append("\n\nFailures:");
        for (ValidationReport.Message failure : validation.getMessages()) {
            if (failure.getLevel() == ValidationReport.Level.ERROR) {
                message.append("\n  - [").append(failure.getKey()).append("] ")
                    .append(failure.getMessage());
            }
        }
        message.append("\n\nEither the server is wrong and the code must change, or the spec is "
            + "wrong and it must change — decide against the shipped clients' contract, "
            + "and record the decision.");
        throw new AssertionError(message.toString());
    }

    private static String describe(HttpRequest request, HttpResponse<String> response) {
        StringBuilder text = new StringBuilder(request.method())
            .append(' ').append(request.uri().getPath());
        if (request.uri().getRawQuery() != null) {
            text.append('?').append(request.uri().getRawQuery());
        }
        if (response != null) {
            text.append("  ->  ").append(response.statusCode())
                .append("\nResponse body: ").append(excerpt(response.body()));
        }
        return text.toString();
    }

    private static String excerpt(String body) {
        if (body == null || body.isEmpty()) {
            return "(none)";
        }
        return body.length() <= BODY_EXCERPT
            ? body
            : body.substring(0, BODY_EXCERPT) + "… (" + body.length() + " chars)";
    }

    private static SimpleRequest toApiRequest(HttpRequest request, String body) {
        SimpleRequest.Builder builder =
            new SimpleRequest.Builder(request.method(), request.uri().getPath());
        request.headers().map()
            .forEach((name, values) -> builder.withHeader(name, values));
        queryParameters(request.uri())
            .forEach((name, values) -> builder.withQueryParam(name, values));
        // An empty string is what BaseIT sends for a zero-length body with a JSON content type
        // (what axios' post(url, null) puts on the wire). There is no body to validate in that
        // case, and handing the validator "" makes it report unparseable JSON for a request the
        // server is required to tolerate.
        if (body != null && !body.isEmpty()) {
            builder.withBody(body);
        }
        return builder.build();
    }

    private static SimpleResponse toApiResponse(HttpResponse<String> response) {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.statusCode());
        response.headers().map().forEach((name, values) -> builder.withHeader(name, values));
        String body = response.body();
        if (body != null && !body.isEmpty()) {
            builder.withBody(body);
        }
        return builder.build();
    }

    private static Map<String, List<String>> queryParameters(URI uri) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters
                .computeIfAbsent(decode(name), key -> new ArrayList<>())
                .add(decode(value));
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Finds {@code openapi/trove-core.yaml} by walking up from the compiled test classes, so the
     * lookup works the same under Failsafe, a {@code -pl core} reactor build and an IDE — none of
     * which agree on the working directory.
     */
    private static Path locateSpec() {
        Path start;
        try {
            start = Paths.get(OpenApiConformance.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            // swallow-ok: the working directory is the fallback and the failure below names the
            // real problem (the file, not the class loader) if neither route finds the spec.
            start = Paths.get("").toAbsolutePath();
        }
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("openapi").resolve("trove-core.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
            "openapi/trove-core.yaml not found above " + start + ". It is the API contract every "
                + "integration test validates against; the build cannot prove conformance "
                + "without it.");
    }
}
