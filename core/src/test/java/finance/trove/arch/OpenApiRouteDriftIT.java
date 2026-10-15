package finance.trove.arch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.SecurityConfig;
import finance.trove.app.support.BaseIT;
import finance.trove.app.support.OpenApiConformance;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.NameValueExpression;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The two-way drift check between what the application serves and what
 * {@code openapi/trove-core.yaml} says it serves.
 *
 * <p>{@link finance.trove.app.support.OpenApiConformance} proves that the routes the integration
 * tests <em>exercise</em> match the contract. That is not the same claim: a route nobody tests is
 * invisible to it, and so is an operation the document invents. This test closes both gaps by
 * comparing sets rather than interactions — every {@code (method, path)} Spring will dispatch
 * against every {@code (method, path)} the document declares — so a controller added without a
 * spec change fails, and a spec entry with no controller behind it fails too.
 *
 * <p>The route table comes from {@link RequestMappingHandlerMapping}, which is the same object
 * the dispatcher consults, rather than from a source scan: an annotation the framework ignores
 * would pass a scan and 404 in production.
 *
 * <p><strong>Who may call a route is compared here too</strong>, because nothing else compares
 * it. The validator ships a {@code SecurityValidator}, but it returns an empty report for any
 * operation whose own {@code security} is null or empty, and swagger-parser does not merge the
 * document's root {@code security} onto operations — so the root declaration every authenticated
 * operation inherits is enforced by no test at all, and a route added to
 * {@link SecurityConfig#PUBLIC_POST} while the document still claims it needs a session would go
 * green across the whole suite. The last two comparisons close that: the operations marked
 * {@code security: []} against the permit list, and the operations marked
 * {@code x-trove-required-role: ADMIN} against the routes the role guard actually covers.
 *
 * <p><strong>Two handlers on one {@code (method, path)} are reconciled, not collapsed.</strong>
 * The two set comparisons above key on method and path alone, so a second handler that Spring
 * tells apart by a {@code params} condition folds silently into the first — {@code ChatController}
 * splits {@code POST /v1/chat/completions} that way on {@code stream=true}, and a route added the
 * same way would have gone unnoticed in both directions (collapse step 13, finding 7). The params
 * comparisons below close that: every parameter a {@code params} condition names must be a query
 * parameter of the operation it lands on, and handlers sharing a route may differ by nothing
 * else — a split on a header, a content type or a custom condition is refused outright, because
 * nothing here would compare it with the document.
 *
 * <p><strong>No springdoc.</strong> The contract is hand-written and versioned with the code
 * (owner decision, 2026-09-04). Generating it from annotations at runtime would make the code the
 * source of truth again, put a documentation framework on the production classpath, and turn this
 * test into a tautology.
 */
class OpenApiRouteDriftIT extends BaseIT {

    /**
     * Served paths that answer requests matching no operation and therefore cannot be described
     * as one: Spring's internal error dispatch, and the actuator, which lives on the management
     * port and is Spring Boot's contract rather than Trove's.
     */
    private static final Set<String> NOT_THIS_CONTRACT = Set.of("/error", "/actuator");

    /**
     * How the document states a role requirement. OpenAPI has no vocabulary for one, so this
     * extension plus the 403 response's slug list is the whole contract a client author can read.
     */
    private static final String ROLE_EXTENSION = "x-trove-required-role";

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("Every route the application serves is in openapi/trove-core.yaml")
    void noServedRouteIsMissingFromTheSpec() {
        Set<String> undocumented = new TreeSet<>(servedRoutes().keySet());
        undocumented.removeAll(specifiedRoutes().keySet());

        assertThat(undocumented)
            .as("these routes answer requests but no operation describes them. The spec is the "
                + "single source of truth for every /api/v1 route and both clients generate "
                + "their types from it, so a route it does not mention is a route no client can "
                + "call correctly. Add the operation to openapi/trove-core.yaml")
            .isEmpty();
    }

    @Test
    @DisplayName("Every operation in openapi/trove-core.yaml is served by a controller")
    void noSpecOperationIsUnserved() {
        Set<String> unserved = new TreeSet<>(specifiedRoutes().keySet());
        unserved.removeAll(servedRoutes().keySet());

        assertThat(unserved)
            .as("openapi/trove-core.yaml declares these operations but nothing serves them: a "
                + "client generated from this document would call them and get 404 or 405. "
                + "Either implement them or take them out of the spec")
            .isEmpty();
    }

    @Test
    @DisplayName("The operations marked anonymous are exactly SecurityConfig's permit list")
    void theAnonymousSurfaceMatchesThePermitList() {
        Set<String> specified = new TreeSet<>(anonymousOperations());
        Set<String> permitted = new TreeSet<>(permittedRoutes());

        Set<String> permittedButNotAnonymousInTheSpec = new TreeSet<>(permitted);
        permittedButNotAnonymousInTheSpec.removeAll(specified);
        assertThat(permittedButNotAnonymousInTheSpec)
            .as("SecurityConfig lets these through with no credential, but "
                + "openapi/trove-core.yaml does not mark them `security: []` — so the document "
                + "says they need a session and the server does not ask for one. That is the "
                + "exact shape of an accidental auth bypass, and nothing else catches it: the "
                + "request validator's SecurityValidator returns empty for any operation whose "
                + "own `security` is absent, so root-level security is enforced by no test. "
                + "Either take the route out of SecurityConfig's permit list or write "
                + "`security: []` on the operation and mean it")
            .isEmpty();

        Set<String> anonymousInTheSpecButNotPermitted = new TreeSet<>(specified);
        anonymousInTheSpecButNotPermitted.removeAll(permitted);
        assertThat(anonymousInTheSpecButNotPermitted)
            .as("openapi/trove-core.yaml declares these anonymous (`security: []`) but "
                + "SecurityConfig requires a session for them: a client generated from this "
                + "document will call them signed out and get 401. Either add the route to the "
                + "permit list or give the operation the real security requirement")
            .isEmpty();
    }

    @Test
    @DisplayName("The operations marked ADMIN are exactly the routes the role guard covers")
    void theAdminSurfaceMatchesTheRoleGuard() {
        Set<String> specified = new TreeSet<>(adminOperations());
        Set<String> guarded = new TreeSet<>(adminGuardedRoutes());

        Set<String> guardedButNotMarked = new TreeSet<>(guarded);
        guardedButNotMarked.removeAll(specified);
        assertThat(guardedButNotMarked)
            .as("these routes demand the ADMIN role in code but the document does not say so. "
                + "OpenAPI has no role vocabulary, so `x-trove-required-role: ADMIN` plus the "
                + "403 `forbidden` response is the only place a client author can learn it — "
                + "without it a member's client offers an action that can only fail")
            .isEmpty();

        Set<String> markedButNotGuarded = new TreeSet<>(specified);
        markedButNotGuarded.removeAll(guarded);
        assertThat(markedButNotGuarded)
            .as("openapi/trove-core.yaml marks these `x-trove-required-role: ADMIN` but neither "
                + "SecurityConfig's " + SecurityConfig.ADMIN_PATHS + " matcher nor a "
                + "@PreAuthorize(\"hasRole('ADMIN')\") covers them — every signed-in member can "
                + "call them. Restore the guard rather than the annotation")
            .isEmpty();
    }

    @Test
    @DisplayName("Both sides of the comparison actually found routes")
    void theComparisonHasSomethingToCompare() {
        // Two empty sets are equal, so a broken collector on either side would make both tests
        // above pass while proving nothing.
        assertThat(servedRoutes())
            .as("the application serves the collapse step-3 surface")
            .hasSizeGreaterThanOrEqualTo(25);
        assertThat(specifiedRoutes())
            .as("the contract describes the collapse step-3 surface")
            .hasSizeGreaterThanOrEqualTo(25);
        assertThat(anonymousOperations())
            .as("the document marks the anonymous surface with `security: []`; an empty set here "
                + "would make the permit-list comparison pass by finding nothing on either side")
            .hasSizeGreaterThanOrEqualTo(10);
        assertThat(adminOperations())
            .as("the document marks the administration surface with `x-trove-required-role`; "
                + "the same trap")
            .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Every params-conditioned handler names only query parameters its operation declares")
    void everyParamsConditionIsDeclaredOnItsOperation() {
        Collection<RequestMappingInfo> served = handlerMapping.getHandlerMethods().keySet();

        assertThat(paramsConditions(served))
            .as("ChatController answers POST /v1/chat/completions with two handlers, one "
                + "conditioned on params=\"stream=true\"; a collector that cannot see that "
                + "condition cannot compare it with the document, which is the hole this test "
                + "exists to close")
            .containsEntry("POST /v1/chat/completions", Set.of("stream"));

        assertThat(undeclaredParamsConditions(served, specification()))
            .as("these handlers are selected by a request parameter that their operation in "
                + "openapi/trove-core.yaml does not declare, so a client generated from the "
                + "document cannot know the parameter exists or that it changes the response. "
                + "Declare it as a query parameter on the operation")
            .isEmpty();
    }

    @Test
    @DisplayName("Handlers sharing a route are told apart by a params condition and nothing else")
    void handlersSharingARouteDifferOnlyByDeclaredParams() {
        assertThat(unreconciledSplits(handlerMapping.getHandlerMethods().keySet()))
            .as("more than one handler without a params condition answers these routes, so "
                + "they are split by a header, a content type or a custom condition that "
                + "neither side of the drift comparison can see. Split on a query parameter "
                + "the operation declares, or reconcile this test deliberately")
            .isEmpty();
    }

    @Test
    @DisplayName("A params-conditioned route the document does not describe is caught")
    void theParamsComparisonCatchesAnUndescribedSplit() {
        // A handler nobody serves, built the way Spring builds one: if the collector went back
        // to method-and-path only, this route would vanish into the existing POST /v1/accounts
        // key exactly as the stream=true handler used to, and both assertions would fail.
        List<RequestMappingInfo> served = new ArrayList<>(handlerMapping.getHandlerMethods().keySet());
        served.add(mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").build());
        assertThat(undeclaredParamsConditions(served, specification()))
            .containsExactly("POST /v1/accounts ?bulk");

        List<RequestMappingInfo> headerSplit = List.of(
            mapping("/v1/accounts", RequestMethod.POST).build(),
            mapping("/v1/accounts", RequestMethod.POST).headers("X-Bulk=true").build());
        assertThat(unreconciledSplits(headerSplit)).containsExactly("POST /v1/accounts");

        // A header or media type riding along with a params condition is still invisible, and
        // so is a second handler on the same params condition.
        List<RequestMappingInfo> paramsPlusHeader = List.of(
            mapping("/v1/accounts", RequestMethod.POST).build(),
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").headers("X-Bulk=true").build());
        assertThat(unreconciledSplits(paramsPlusHeader)).containsExactly("POST /v1/accounts");

        List<RequestMappingInfo> sameParamsByMediaType = List.of(
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").build(),
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").produces("text/csv").build());
        assertThat(unreconciledSplits(sameParamsByMediaType)).containsExactly("POST /v1/accounts");

        List<RequestMappingInfo> sameParams = List.of(
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").build(),
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").build());
        assertThat(unreconciledSplits(sameParams)).containsExactly("POST /v1/accounts");

        // ChatController's shape — one plain handler, one on a distinct params condition — is
        // the reconciled split, and must stay allowed.
        List<RequestMappingInfo> paramsSplit = List.of(
            mapping("/v1/accounts", RequestMethod.POST).build(),
            mapping("/v1/accounts", RequestMethod.POST).params("bulk=true").build());
        assertThat(unreconciledSplits(paramsSplit)).isEmpty();
    }

    private static RequestMappingInfo.Builder mapping(String path, RequestMethod method) {
        RequestMappingInfo.BuilderConfiguration options = new RequestMappingInfo.BuilderConfiguration();
        options.setPatternParser(PathPatternParser.defaultInstance);
        return RequestMappingInfo.paths(path).methods(method).options(options);
    }

    /**
     * The request parameters each served route's {@code params} conditions name, by route key.
     * A negated expression ({@code !stream}) names its parameter too: it still makes the
     * parameter part of what selects the handler.
     */
    private static Map<String, Set<String>> paramsConditions(Collection<RequestMappingInfo> infos) {
        Map<String, Set<String>> named = new TreeMap<>();
        for (RequestMappingInfo info : infos) {
            Set<NameValueExpression<String>> expressions = info.getParamsCondition().getExpressions();
            if (expressions.isEmpty()) {
                continue;
            }
            for (String route : routeKeys(info)) {
                Set<String> names = named.computeIfAbsent(route, ignored -> new TreeSet<>());
                expressions.forEach(expression -> names.add(expression.getName()));
            }
        }
        return named;
    }

    /** {@code METHOD /path ?name} for every params-condition name the operation does not declare. */
    private static Set<String> undeclaredParamsConditions(Collection<RequestMappingInfo> infos,
                                                          OpenAPI spec) {
        Map<String, Set<String>> declared = queryParameters(spec);
        Set<String> undeclared = new TreeSet<>();
        paramsConditions(infos).forEach((route, names) -> names.stream()
            .filter(name -> !declared.getOrDefault(route, Set.of()).contains(name))
            .forEach(name -> undeclared.add(route + " ?" + name)));
        return undeclared;
    }

    /**
     * Routes answered by more than one handler that the params comparison cannot tell apart:
     * two handlers with the same params condition (the empty one included), or any handler on a
     * shared route that is also selected by a header, a media type, an API version or a custom
     * condition — none of which either side of the drift comparison can see.
     */
    private static Set<String> unreconciledSplits(Collection<RequestMappingInfo> infos) {
        Map<String, List<RequestMappingInfo>> byRoute = new TreeMap<>();
        for (RequestMappingInfo info : infos) {
            routeKeys(info).forEach(route ->
                byRoute.computeIfAbsent(route, ignored -> new ArrayList<>()).add(info));
        }
        Set<String> split = new TreeSet<>();
        byRoute.forEach((route, handlers) -> {
            if (handlers.size() < 2) {
                return;
            }
            Set<Set<NameValueExpression<String>>> seen = new HashSet<>();
            for (RequestMappingInfo info : handlers) {
                if (!seen.add(info.getParamsCondition().getExpressions())
                    || hasAConditionBesidesParams(info)) {
                    split.add(route);
                }
            }
        });
        return split;
    }

    private static boolean hasAConditionBesidesParams(RequestMappingInfo info) {
        return !info.getHeadersCondition().isEmpty()
            || !info.getConsumesCondition().isEmpty()
            || !info.getProducesCondition().isEmpty()
            || !info.getVersionCondition().isEmpty()
            || info.getCustomCondition() != null;
    }

    private static Set<String> routeKeys(RequestMappingInfo info) {
        Set<String> keys = new TreeSet<>();
        for (String pattern : patternsOf(info)) {
            if (isOutsideThisContract(pattern)) {
                continue;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                keys.add(key(method.name(), pattern));
            }
        }
        return keys;
    }

    /**
     * The query parameters each operation declares, by route key, from both the operation and
     * its path item, with {@code #/components/parameters} references followed by hand —
     * {@code setResolve} resolves remote references only.
     */
    private static Map<String, Set<String>> queryParameters(OpenAPI spec) {
        Map<String, Parameter> components = spec.getComponents() == null
            || spec.getComponents().getParameters() == null
            ? Map.of() : spec.getComponents().getParameters();
        Map<String, Set<String>> declared = new TreeMap<>();
        spec.getPaths().forEach((path, item) ->
            item.readOperationsMap().forEach((method, operation) -> {
                List<Parameter> parameters = new ArrayList<>();
                if (item.getParameters() != null) {
                    parameters.addAll(item.getParameters());
                }
                if (operation.getParameters() != null) {
                    parameters.addAll(operation.getParameters());
                }
                Set<String> names = declared.computeIfAbsent(
                    key(method.name(), path), ignored -> new TreeSet<>());
                for (Parameter parameter : parameters) {
                    Parameter resolved = parameter.get$ref() == null ? parameter
                        : components.get(parameter.get$ref()
                            .substring(parameter.get$ref().lastIndexOf('/') + 1));
                    if (resolved != null && "query".equals(resolved.getIn())) {
                        names.add(resolved.getName());
                    }
                }
            }));
        return declared;
    }

    /**
     * Every {@code (method, path)} pair the dispatcher will match, keyed by a normalised form so
     * that a path variable renamed on one side of the comparison is not read as drift. The value
     * is the handler's own spelling, which is what the failure message should show.
     */
    private Map<String, String> servedRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : patternsOf(info)) {
                if (isOutsideThisContract(pattern)) {
                    continue;
                }
                for (org.springframework.web.bind.annotation.RequestMethod method
                    : info.getMethodsCondition().getMethods()) {
                    routes.put(key(method.name(), pattern), method.name() + " " + pattern);
                }
            }
        }
        return routes;
    }

    /** Every {@code (method, path)} pair the document declares, keyed the same way. */
    private Map<String, String> specifiedRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        specification().getPaths().forEach((path, item) -> {
            for (Map.Entry<PathItem.HttpMethod, Operation> operation
                : item.readOperationsMap().entrySet()) {
                routes.put(key(operation.getKey().name(), path),
                    operation.getKey().name() + " " + path);
            }
        });
        return routes;
    }

    /**
     * Every operation the document declares anonymous, as {@code METHOD /path} with path
     * variables written the way a Spring pattern writes them ({@code *}).
     *
     * <p>{@code security: []} on an operation is the only way OpenAPI can say "no credential":
     * the document's root {@code security} applies to everything else. swagger-parser does not
     * merge the root onto an operation, which is exactly why {@code SecurityValidator} never
     * fires and why this comparison has to exist — an operation with no {@code security} key of
     * its own comes back {@code null} here and is read as authenticated, which is what the
     * server does with it too.
     */
    private static Set<String> anonymousOperations() {
        Set<String> anonymous = new TreeSet<>();
        specification().getPaths().forEach((path, item) ->
            item.readOperationsMap().forEach((method, operation) -> {
                if (operation.getSecurity() != null && operation.getSecurity().isEmpty()) {
                    anonymous.add(anonymousKey(method.name(), path));
                }
            }));
        return anonymous;
    }

    /**
     * {@link SecurityConfig}'s permit list, in the same form. The comparison is against the
     * patterns as written: a broader wildcard than the document's path (a hypothetical
     * {@code /v1/auth/*}) does not silently match several operations, it fails and has to be
     * reconciled deliberately — which is the right outcome for a widened permit.
     *
     * <p>The chain's other {@code permitAll} matchers are deliberately not here:
     * {@code /actuator/health/**} is on the management port, {@code /error} is Spring's internal
     * dispatch, and {@code /oauth2/**} plus {@code /login/oauth2/**} are Spring Security's own
     * browser redirects. All four are outside this contract and the document says so in its
     * header.
     */
    private static Set<String> permittedRoutes() {
        Set<String> permitted = new TreeSet<>();
        SecurityConfig.PUBLIC_GET.forEach(pattern -> permitted.add(anonymousKey("GET", pattern)));
        SecurityConfig.PUBLIC_POST.forEach(pattern -> permitted.add(anonymousKey("POST", pattern)));
        return permitted;
    }

    /** Every operation the document marks {@code x-trove-required-role: ADMIN}. */
    private static Set<String> adminOperations() {
        Set<String> admin = new TreeSet<>();
        specification().getPaths().forEach((path, item) ->
            item.readOperationsMap().forEach((method, operation) -> {
                Map<String, Object> extensions = operation.getExtensions();
                if (extensions != null && "ADMIN".equals(extensions.get(ROLE_EXTENSION))) {
                    admin.add(key(method.name(), path));
                }
            }));
        return admin;
    }

    /**
     * Every served route the ADMIN role actually fences, by either of the two mechanisms the
     * code uses on purpose: the chain matcher {@link SecurityConfig#ADMIN_PATHS}, and
     * {@code @PreAuthorize("hasRole('ADMIN')")} on the handler or its controller. Both are
     * counted because either one alone refuses the request — {@code AdminController}'s comment
     * calls them belt and braces — and because {@code POST /v1/auth/password/reset-request} is
     * admin-only through the annotation alone, its path being nowhere near {@code /v1/admin}.
     */
    private Set<String> adminGuardedRoutes() {
        String prefix = SecurityConfig.ADMIN_PATHS.substring(
            0, SecurityConfig.ADMIN_PATHS.indexOf("/**"));
        Set<String> guarded = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            boolean byAnnotation = requiresAdminRole(handler.getMethod())
                || requiresAdminRole(handler.getBeanType());
            for (String pattern : patternsOf(info)) {
                if (isOutsideThisContract(pattern)) {
                    continue;
                }
                boolean byMatcher = pattern.equals(prefix) || pattern.startsWith(prefix + "/");
                if (!byMatcher && !byAnnotation) {
                    continue;
                }
                for (org.springframework.web.bind.annotation.RequestMethod method
                    : info.getMethodsCondition().getMethods()) {
                    guarded.add(key(method.name(), pattern));
                }
            }
        });
        return guarded;
    }

    private static boolean requiresAdminRole(AnnotatedElement element) {
        PreAuthorize annotation =
            AnnotatedElementUtils.findMergedAnnotation(element, PreAuthorize.class);
        return annotation != null
            && annotation.value().replace(" ", "").contains("hasRole('ADMIN')");
    }

    private static Set<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
            ? Set.of()
            : info.getPathPatternsCondition().getPatternValues();
    }

    private static OpenAPI specification() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser()
            .readLocation(OpenApiConformance.SPEC.toAbsolutePath().toString(), null, options);
        assertThat(result.getOpenAPI())
            .as("openapi/trove-core.yaml must parse: %s", result.getMessages())
            .isNotNull();
        return result.getOpenAPI();
    }

    private static boolean isOutsideThisContract(String pattern) {
        return NOT_THIS_CONTRACT.stream()
            .anyMatch(prefix -> pattern.equals(prefix) || pattern.startsWith(prefix + "/"));
    }

    /**
     * {@code POST /v1/admin/users/{}/unlock}. Path-variable names carry no meaning for routing —
     * {@code {id}} and {@code {userId}} dispatch identically — so they are erased before the two
     * sides are compared and a rename cannot be mistaken for a missing route.
     */
    private static String key(String method, String path) {
        return method + " " + path.replaceAll("\\{[^/}]*}", "{}");
    }

    /**
     * The same erasure, but spelled the way {@code SecurityConfig}'s permit list spells a path
     * variable — {@code /v1/auth/invites/*} and {@code /v1/auth/invites/{token}} are the same
     * route, and a single-segment wildcard is what the matcher list can express.
     */
    private static String anonymousKey(String method, String path) {
        return method + " " + path.replaceAll("\\{[^/}]*}", "*");
    }
}
