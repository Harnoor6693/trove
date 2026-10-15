package finance.trove.app;

import finance.trove.auth.AuthMode;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.SessionResponseWriter;
import finance.trove.auth.jwt.AuthCookies;
import finance.trove.auth.jwt.CookieOrBearerJwtAuthFilter;
import finance.trove.auth.jwt.CsrfCookieFilter;
import finance.trove.auth.jwt.CsrfRequiresHeaderFilter;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.auth.jwt.SpaCsrfTokenRequestHandler;
import finance.trove.auth.oidc.CookieAuthorizationRequestRepository;
import finance.trove.auth.oidc.OidcAccountLinker;
import finance.trove.auth.oidc.TroveOidcFailureHandler;
import finance.trove.auth.oidc.TroveOidcSuccessHandler;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.auth.session.SessionValidity;
import finance.trove.app.audit.AuditLogFilter;
import finance.trove.app.audit.AuditLogWriter;
import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.ratelimit.RateLimitFilter;
import finance.trove.app.ratelimit.RateLimitProperties;
import finance.trove.app.ratelimit.RateLimitService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.exception.SecurityErrorWriter;
import finance.trove.common.net.ClientIpResolver;
import finance.trove.common.exception.TroveAccessDeniedHandler;
import finance.trove.common.exception.TroveAuthenticationEntryPoint;
import finance.trove.common.observability.RequestIdMdcFilter;
import finance.trove.common.time.UserClock;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The one and only {@code SecurityFilterChain} in the monolith
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1). No other class may declare one and no package
 * may opt itself out of it — the M1 pattern of §9 risk 1.
 *
 * <h2>Filter order — final, §5.1</h2>
 * <pre>
 *   RequestIdMdcFilter        (outside the chain, highest precedence — ObservabilityConfig)
 *   CorsFilter                (Spring, order 800)
 *   CookieOrBearerJwtAuthFilter   801  — sets the principal AND trove.authSource
 *   RateLimitFilter                    — after the JWT filter, so it can key on a principal
 *   CsrfRequiresHeaderFilter      899  — needs authSource, so it must be after the JWT filter
 *   CsrfFilter                (Spring, order 900)
 *   CsrfCookieFilter              901  — materialises the token cookie, so it must be AFTER
 *   AuditLogFilter                     — last, so the status it records is the final one
 * </pre>
 *
 * <p><strong>Each custom filter is anchored to a different neighbour</strong>, and that is not
 * cosmetic: two filters registered "after X" receive the <em>same</em> order value and their
 * relative order then depends on sort stability, i.e. on nothing anybody wrote down. So the four
 * anchors are {@code CorsFilter}, {@code CookieOrBearerJwtAuthFilter}, {@code CsrfFilter} and
 * {@code CsrfCookieFilter} — four distinct classes for four filters. {@code FilterOrderIT} asserts
 * the resulting sequence.
 *
 * <p>The limiter's position is §5.1's correction of the original draft, which put it <em>before</em>
 * the JWT filter: there it never sees a principal, every authenticated request falls into one flat
 * anonymous bucket, and the per-route rows V12 seeds are unreachable. The audit filter's position
 * is the other end of the same argument — it needs the principal too, and it needs the status the
 * client actually received, so it wraps everything inside it and nothing outside.
 *
 * <h2>CSRF</h2>
 * Three cooperating pieces, each of which is a blocker on its own (§1.5, §5.10):
 * <ul>
 *   <li>{@link CookieCsrfTokenRepository#withHttpOnlyFalse()} with <strong>cookie path
 *       {@code /}</strong>. The default is the servlet context path, {@code /api} — and the SPA is
 *       served at {@code /}, so a context-path-scoped cookie is invisible to it and every
 *       cookie-mode mutation 403s.</li>
 *   <li>{@link SpaCsrfTokenRequestHandler}. Spring Security 7's default XOR handler rejects the
 *       raw cookie value an XHR client echoes back.</li>
 *   <li>{@link CsrfCookieFilter}. Since Spring Security 6 the token is deferred and is only
 *       generated if something asks for it; nothing does on a plain GET. But
 *       {@code POST /v1/auth/login} is itself a protected mutation, so without this filter the
 *       first login 403s against a cookie that was never written. The web client has no CSRF
 *       bootstrap call and relies on this ({@code apps/web/src/api/client.ts:15-17}).</li>
 * </ul>
 *
 * <p><strong>CSRF applies only to requests that carry an ambient credential.</strong> See
 * {@link #csrfProtected()} — a bearer caller has nothing a third-party page could ride.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Everything reachable without a session (§5.1's permit list).
     *
     * <p><strong>Public, immutable, and read by a test on purpose.</strong> These two lists are
     * the anonymous surface of the API, and {@code openapi/trove-core.yaml} states the same fact
     * a second time as {@code security: []} on ten operations. Nothing in the validator enforces
     * that: {@code SecurityValidator} short-circuits on an operation whose own {@code security}
     * is absent, so a route quietly added here while the document keeps inheriting
     * {@code cookieAuth|bearerAuth} — the exact shape of an accidental auth bypass — would pass
     * every integration test. {@code OpenApiRouteDriftIT} compares these lists against the
     * document instead, which is why they are readable from outside this class. A {@code List}
     * rather than an array because a public array constant is not a constant.
     */
    public static final List<String> PUBLIC_POST = List.of(
        "/v1/auth/register",
        "/v1/auth/login",
        "/v1/auth/login/totp",
        "/v1/auth/refresh",
        "/v1/auth/logout",
        "/v1/auth/password/reset");

    /** @see #PUBLIC_POST */
    public static final List<String> PUBLIC_GET = List.of(
        "/v1/status",
        "/v1/auth/providers",
        "/v1/auth/start",
        "/v1/auth/invites/*");

    /**
     * The one matcher that fences the administration surface. Read by
     * {@code OpenApiRouteDriftIT}, which holds it against the operations the document marks
     * {@code x-trove-required-role: ADMIN}.
     */
    public static final String ADMIN_PATHS = "/v1/admin/**";

    /**
     * The one administrative route that is not under {@link #ADMIN_PATHS}. §4.2 and §5.1 both give
     * the path as {@code /v1/audit} and both fence it with {@code hasRole('ADMIN')}; the path stays
     * as specified because the clients are generated from {@code openapi/trove-core.yaml}, so the
     * guard gets an explicit matcher of its own rather than the path being bent to fit the
     * wildcard.
     */
    public static final String AUDIT_PATH = "/v1/audit";

    /**
     * The actuator endpoints the Docker {@code HEALTHCHECK} and any orchestrator must reach with
     * no credential ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.9, gap P1-1). A probe that needs
     * a secret is a probe that reports "unhealthy" the day the secret rotates.
     */
    public static final List<String> PUBLIC_ACTUATOR = List.of(
        "/actuator/health",
        "/actuator/health/liveness",
        "/actuator/health/readiness");

    /**
     * Everything else on the management port: the metrics surface. Restricted to the compose
     * network, or to a caller holding {@code TROVE_METRICS_TOKEN}.
     */
    public static final String MANAGEMENT_PATHS = "/actuator/**";

    /**
     * §5.1's policy, verbatim. {@code 'unsafe-inline'} survives on {@code style-src} alone and for
     * one reason: the SPA's bundler emits inline styles, and the alternative is a nonce the API
     * cannot mint for a document it does not serve. Scripts have no such exemption, which is the
     * half that matters.
     */
    static final String CONTENT_SECURITY_POLICY =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'self'; form-action 'self'";

    /** Every browser capability this application never asks for, denied to everyone. */
    static final String PERMISSIONS_POLICY =
        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), "
            + "microphone=(), payment=(), usb=()";

    private final TroveAuthenticationEntryPoint authenticationEntryPoint;
    private final TroveAccessDeniedHandler accessDeniedHandler;
    private final SecurityErrorWriter errorWriter;
    private final JwtVerifier jwtVerifier;
    private final SessionValidity sessionValidity;
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final AuditLogWriter auditLogWriter;
    private final ManagementAccessProperties managementAccess;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public SecurityConfig(TroveAuthenticationEntryPoint authenticationEntryPoint,
                          TroveAccessDeniedHandler accessDeniedHandler,
                          SecurityErrorWriter errorWriter, JwtVerifier jwtVerifier,
                          SessionValidity sessionValidity, AuthProperties authProperties,
                          JwtProperties jwtProperties, RateLimitService rateLimitService,
                          RateLimitProperties rateLimitProperties, AuditLogWriter auditLogWriter,
                          ManagementAccessProperties managementAccess) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.errorWriter = errorWriter;
        this.jwtVerifier = jwtVerifier;
        this.sessionValidity = sessionValidity;
        this.authProperties = authProperties;
        this.jwtProperties = jwtProperties;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.auditLogWriter = auditLogWriter;
        this.managementAccess = managementAccess;
    }

    @Bean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> clients,
                                           ObjectProvider<OidcAccountLinker> linkers,
                                           ObjectProvider<JwtIssuerService> issuers,
                                           ObjectProvider<RefreshTokenService> sessions,
                                           ObjectProvider<TotpService> totp,
                                           ObjectProvider<SessionResponseWriter> writers,
                                           ObjectProvider<FieldEncryptionService> encryption,
                                           ObjectProvider<ObjectMapper> mappers,
                                           ObjectProvider<UserClock> clocks,
                                           ObjectProvider<SecurityEventRecorder> events)
        throws Exception {

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath(AuthCookies.PATH);

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                .requireCsrfProtectionMatcher(csrfProtected()))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .headers(this::securityHeaders)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, PUBLIC_GET.toArray(String[]::new)).permitAll()
                .requestMatchers(HttpMethod.POST, PUBLIC_POST.toArray(String[]::new)).permitAll()
                .requestMatchers(PUBLIC_ACTUATOR.toArray(String[]::new)).permitAll()
                .requestMatchers(MANAGEMENT_PATHS).access(managementAccess())
                .requestMatchers("/error").permitAll()
                .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                // Belt to the @PreAuthorize braces on AdminController and AuditController:
                // whichever is edited, the other still refuses, and both produce the JSON
                // envelope rather than the bare 500 that gap P2-7 records.
                .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
                .requestMatchers(AUDIT_PATH).hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterAfter(new CookieOrBearerJwtAuthFilter(jwtVerifier, sessionValidity),
                CorsFilter.class)
            .addFilterAfter(
                new RateLimitFilter(rateLimitService, rateLimitProperties, errorWriter),
                CookieOrBearerJwtAuthFilter.class)
            .addFilterBefore(new CsrfRequiresHeaderFilter(errorWriter), CsrfFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterAfter(new AuditLogFilter(auditLogWriter), CsrfCookieFilter.class);

        ClientRegistrationRepository registrations = clients.getIfAvailable();
        if (registrations != null) {
            TroveOidcSuccessHandler success = new TroveOidcSuccessHandler(
                linkers.getObject(), issuers.getObject(), sessions.getObject(), totp.getObject(),
                writers.getObject(), authProperties, jwtProperties, events.getObject(),
                clocks.getObject());
            http.oauth2Login(oauth -> oauth
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(
                    new CookieAuthorizationRequestRepository(encryption.getObject(),
                        mappers.getObject())))
                .successHandler(success)
                .failureHandler(new TroveOidcFailureHandler(authProperties)));
        }

        return http.build();
    }

    /**
     * The response headers this application sets, and the two it deliberately does not
     * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1; readiness P5).
     *
     * <h2>Set here</h2>
     * <ul>
     *   <li><strong>{@code Content-Security-Policy}.</strong> §5.1's policy verbatim. It matters
     *       far more on the SPA's {@code index.html} than on a JSON API — a policy that decorates
     *       only the API leaves the actual XSS surface undefended, which is why §5.1 records the
     *       same header as a follow-up for the Traefik middleware in front of {@code /} (step 19,
     *       and it is in this step's merge notes). {@code frame-ancestors 'none'} is inside it,
     *       which is the modern statement of the framing rule.</li>
     *   <li><strong>{@code Referrer-Policy: strict-origin-when-cross-origin}</strong> and
     *       <strong>{@code Permissions-Policy}</strong> denying camera, microphone, geolocation
     *       and interest-cohort — this application asks for none of them, and saying so is what
     *       stops an injected script asking on its behalf.</li>
     *   <li><strong>{@code X-Content-Type-Options: nosniff}</strong>, Spring Security's default,
     *       kept. An uploaded receipt served back with a guessed content type is a stored-XSS
     *       vector.</li>
     *   <li><strong>{@code Cache-Control: no-store}</strong> on every response, Spring Security's
     *       default, kept: every body here is either a private ledger or a session.</li>
     * </ul>
     *
     * <h2>Left to Traefik, on purpose</h2>
     * {@code Strict-Transport-Security} and {@code X-Frame-Options}. Traefik's
     * {@code security-headers} middleware sets both for every route it serves (step 19), and it is
     * the only thing that terminates TLS — HSTS asserted by an application reachable over plain
     * HTTP inside the compose network is a statement it is not in a position to make, and two
     * sources for one header is how they drift. Emitting them here as well would put two
     * {@code Strict-Transport-Security} lines on every response with no way to tell which one an
     * operator meant to change. Spring Security sets both by default, so both are switched off
     * explicitly below rather than by omission; {@code SecurityHeadersIT} asserts their absence so
     * that a later step cannot silently reintroduce the duplicate.
     */
    private void securityHeaders(
        org.springframework.security.config.annotation.web.configurers.HeadersConfigurer<
            HttpSecurity> headers) {

        headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
            .referrerPolicy(referrer -> referrer.policy(
                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                    .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
            .frameOptions(frame -> frame.disable())
            .httpStrictTransportSecurity(hsts -> hsts.disable());
    }

    /**
     * Who may read {@code /actuator/prometheus} and the rest of the metrics surface.
     *
     * <p>Two sufficient conditions, neither of which is a session: the peer is inside the compose
     * network, or it holds {@code TROVE_METRICS_TOKEN}. A session is deliberately not one of them
     * — the scrape is machinery, has no user, and making it authenticate as one would mean a
     * long-lived account existing for Prometheus to be.
     *
     * <p>The address comes from {@link ClientIpResolver}, so it is the same value every other
     * per-address control in this application uses, and it is post-valve: a caller cannot claim to
     * be on the compose network with a header.
     */
    private AuthorizationManager<RequestAuthorizationContext> managementAccess() {
        return (authentication, context) ->
            new AuthorizationDecision(mayReadMetrics(context.getRequest(), managementAccess));
    }

    /**
     * The decision itself, static and package-private so that {@code ManagementAccessTest} can
     * exercise both halves without standing up a filter chain — including the token half, which
     * no integration test can reach: the shared test context has no token configured, and
     * configuring one would mean a second Spring context.
     */
    static boolean mayReadMetrics(HttpServletRequest request,
                                  ManagementAccessProperties properties) {
        return fromTrustedNetwork(request, properties.allowedNetworks())
            || carriesMetricsToken(request, properties);
    }

    private static boolean fromTrustedNetwork(HttpServletRequest request,
                                              List<String> allowedNetworks) {
        List<org.springframework.security.web.util.matcher.IpAddressMatcher> networks =
            allowedNetworks.stream()
                .map(org.springframework.security.web.util.matcher.IpAddressMatcher::new)
                .toList();
        String address = ClientIpResolver.resolve(request);
        if (address == null) {
            return false;
        }
        for (org.springframework.security.web.util.matcher.IpAddressMatcher network : networks) {
            try {
                if (network.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // swallow-ok: an address the matcher cannot parse is not a member of the network,
                // which is the safe answer and the next matcher may still parse it (an IPv4
                // literal against an IPv6 block, for instance).
                continue;
            }
        }
        return false;
    }

    /**
     * Constant-time comparison, and closed while no token is configured. A blank configured secret
     * matching a blank header is the classic way an "optional" credential becomes no credential.
     */
    private static boolean carriesMetricsToken(HttpServletRequest request,
                                               ManagementAccessProperties properties) {
        if (!properties.hasToken()) {
            return false;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            header.substring("Bearer ".length()).strip()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
            properties.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * CORS for the SPA's origin only.
     *
     * <p>Credentials are allowed, so the origin list cannot be {@code *} — the browser refuses
     * that combination, and the refusal would be the first thing anyone noticed. Under the shipped
     * deployment the SPA and the API are the same origin behind Traefik and no preflight happens
     * at all; this exists for the split-origin dev setup and for a future separate front end.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(authProperties.publicUrl()));
        configuration.setAllowedMethods(
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
            HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
            "X-XSRF-TOKEN", "X-Requested-With", AuthMode.HEADER,
            RequestIdMdcFilter.REQUEST_ID_HEADER));
        configuration.setExposedHeaders(List.of(RequestIdMdcFilter.REQUEST_ID_HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Which requests the double-submit token is demanded on.
     *
     * <p>Spring's default is "every mutating request", which would 403 mobile's bearer-only
     * {@code POST /v1/auth/login} the moment the mobile client lands (§2.1, major). The rule here
     * is narrower and states the actual threat model: <strong>CSRF is about credentials the
     * browser attaches by itself.</strong>
     *
     * <ul>
     *   <li>Safe methods are never checked — Spring's own default, kept.</li>
     *   <li>An {@code Authorization: Bearer} header means the caller wrote the request in code.
     *       A third-party page cannot set that header, so there is nothing to ride. Skipped.</li>
     *   <li>A request carrying either {@code __Host-} session cookie is riding an ambient
     *       credential. Checked.</li>
     *   <li>{@code X-Trove-Auth-Mode: cookie} is checked even with no cookie yet: that is the
     *       browser's login and register, and login CSRF — logging a victim into the attacker's
     *       account — is a real attack that only the token stops.</li>
     *   <li>Everything else — a bearer-mode login, a mobile refresh — has no ambient credential
     *       and is skipped.</li>
     * </ul>
     */
    static RequestMatcher csrfProtected() {
        return request -> {
            String method = request.getMethod();
            boolean mutating = !("GET".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method) || "TRACE".equals(method));
            if (!mutating) {
                return false;
            }
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                return false;
            }
            return hasSessionCookie(request)
                || AuthMode.from(request.getHeader(AuthMode.HEADER)).isCookie();
        };
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        return AuthCookies.readAccessCookie(request).isPresent()
            || AuthCookies.readRefreshCookie(request).isPresent();
    }
}
