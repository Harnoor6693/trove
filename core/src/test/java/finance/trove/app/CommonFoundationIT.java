package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import finance.trove.app.support.BaseIT;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.exception.TroveAccessDeniedHandler;
import finance.trove.common.exception.TroveAuthenticationEntryPoint;
import finance.trove.common.net.EgressProperties;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.observability.RequestIdMdcFilter;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.UserClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves that the collapse step-2 foundation is wired into the running application, not merely
 * present on the classpath.
 *
 * <p>The unit tests assert behaviour; this asserts <em>wiring</em>, which is the part that breaks
 * silently: a {@code JsonMapperBuilderCustomizer} that is never picked up, a filter registered
 * zero or two times, an {@code AuthenticationEntryPoint} bean the security chain does not use, a
 * {@code @ConfigurationProperties} record that binds to nothing.
 */
class CommonFoundationIT extends BaseIT {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LocalDiskStorage storage;

    @Autowired
    private FieldEncryptionService encryption;

    @Autowired
    private UserClock userClock;

    @Autowired
    private OutboundUrlPolicy outboundUrlPolicy;

    @Autowired
    private EgressProperties egressProperties;

    @Autowired
    private HttpClientProperties httpClientProperties;

    @Autowired
    private TroveAuthenticationEntryPoint authenticationEntryPoint;

    @Autowired
    private TroveAccessDeniedHandler accessDeniedHandler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("the 401 the chain writes is the shared ErrorResponse, with a requestId")
    void theSecurityChainEmitsTheSharedErrorBody() {
        HttpResponse<String> response = outsideTheContract(
            "an unmatched /api/v1 path: the refusal happens in the security chain before any "
                + "handler is matched, so there is no operation for it to conform to",
            () -> get(appUrl("/v1/households")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/json");
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");

        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<Boolean>read("$.success")).isFalse();
        assertThat(body.<Integer>read("$.error.status")).isEqualTo(401);
        assertThat(body.<String>read("$.error.title")).isEqualTo("Unauthorized");
        assertThat(body.<String>read("$.error.type")).endsWith("/unauthorized");
        assertThat(body.<String>read("$.error.instance"))
            .as("the security chain refuses before any handler is matched, so there is no route "
                + "template to name and the last segment is redacted rather than echoed: two "
                + "paths on this application carry a live credential in a path segment")
            .isEqualTo("/api/v1/*");
        assertThat(body.<String>read("$.error.detail")).isEqualTo(TroveAuthenticationEntryPoint.DETAIL);

        // The correlation id in the body is the one in the response header — that pairing is the
        // whole point of carrying it (§5.3).
        String headerId = response.headers()
            .firstValue(RequestIdMdcFilter.REQUEST_ID_HEADER).orElseThrow();
        assertThat(body.<String>read("$.error.requestId")).isEqualTo(headerId);
        assertThat(UUID.fromString(headerId)).isNotNull();

        assertThat(response.body())
            .doesNotContain("Exception").doesNotContain("finance.trove").doesNotContain("\\tat ");
    }

    @Test
    @DisplayName("RequestIdMdcFilter is registered exactly once and covers unauthenticated routes")
    void requestIdFilterRunsOncePerRequest() {
        HttpResponse<String> unauthenticated = outsideTheContract(
            "the same unmatched path, here to prove the filter runs on routes no handler owns",
            () -> get(appUrl("/v1/households")));
        HttpResponse<String> publicRoute = get(appUrl("/v1/status"));

        // Two registrations of the same filter would put two values in the header list.
        assertThat(unauthenticated.headers().allValues(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .hasSize(1);
        assertThat(publicRoute.headers().allValues(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .hasSize(1);
        assertThat(unauthenticated.headers().firstValue(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .isNotEqualTo(publicRoute.headers().firstValue(RequestIdMdcFilter.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("a caller-supplied X-Request-Id is echoed when it is a UUID and replaced when not")
    void requestIdIsEchoedOnlyWhenWellFormed() {
        String supplied = UUID.randomUUID().toString();

        assertThat(getWithHeader(appUrl("/v1/status"), RequestIdMdcFilter.REQUEST_ID_HEADER,
            supplied).headers().firstValue(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .contains(supplied);
        assertThat(getWithHeader(appUrl("/v1/status"), RequestIdMdcFilter.REQUEST_ID_HEADER,
            "not-a-uuid").headers().firstValue(RequestIdMdcFilter.REQUEST_ID_HEADER))
            .isPresent()
            .get().asString().isNotEqualTo("not-a-uuid");
    }

    @Test
    @DisplayName("the application's own ObjectMapper is the one JacksonConfig configured")
    void theRunningMapperIsTheConfiguredOne() {
        assertThat(objectMapper.writeValueAsString(Money.of("12.34", CurrencyCode.CAD)))
            .isEqualTo("{\"amount\":\"12.34\",\"currency\":\"CAD\"}");
        assertThat(objectMapper.writeValueAsString(new BigDecimal("1E+2")))
            .isEqualTo("\"100\"")
            .doesNotContain("E+");
        assertThat(objectMapper.readValue("{\"amount\":\"1.00\",\"currency\":\"USD\",\"x\":1}",
            Money.class)).isEqualTo(Money.of("1.00", CurrencyCode.USD));
    }

    @Test
    @DisplayName("the storage bean writes into the volume StorageConfig created")
    void storageIsWiredToTheDataDirectory() throws IOException {
        UUID household = UUID.randomUUID();

        String key = storage.put(StorageArea.RECEIPTS, household, new byte[64], "jpg");

        assertThat(key).startsWith("receipts/" + household + "/");
        assertThat(Files.exists(storage.pathOf(key))).isTrue();
        assertThat(storage.pathOf(key)).startsWith(storage.root());
        assertThat(storage.usage(household)).isEqualTo(64);
        assertThat(storage.delete(key)).isTrue();
    }

    @Test
    @DisplayName("the encryption key bound from TROVE_ENCRYPTION_KEY produces v1: ciphertext")
    void encryptionIsWiredFromConfiguration() {
        String ciphertext = encryption.encrypt("simplefin-access-url");

        assertThat(ciphertext).startsWith(FieldEncryptionService.CURRENT_VERSION + ":");
        assertThat(encryption.decrypt(ciphertext)).isEqualTo("simplefin-access-url");
    }

    @Test
    @DisplayName("every @ConfigurationProperties record bound, with the documented defaults")
    void configurationPropertiesAreBound() {
        assertThat(egressProperties.allowPrivate())
            .as("private egress is off unless an operator turns it on")
            .isFalse();
        assertThat(httpClientProperties.connectTimeout().toMillis()).isEqualTo(5_000);
        assertThat(httpClientProperties.readTimeout().toMillis()).isEqualTo(30_000);
        assertThat(userClock.zoneOf(UUID.randomUUID()).getId()).isEqualTo("UTC");
    }

    @Test
    @DisplayName("the outbound policy bean refuses a private target with egress off")
    void outboundPolicyIsWiredWithTheInstallDefault() {
        assertThatThrownBy(() -> outboundUrlPolicy.check("http://localhost:11434/v1", true))
            .isInstanceOf(OutboundUrlPolicyException.class);
        assertThatThrownBy(() -> outboundUrlPolicy.check("https://localhost/v1"))
            .isInstanceOf(OutboundUrlPolicyException.class);
    }

    @Test
    @DisplayName("the chain's entry point and access-denied handler are the shared beans")
    void theSecurityChainUsesTheSharedRefusalBeans() {
        assertThat(authenticationEntryPoint).isNotNull();
        assertThat(accessDeniedHandler).isNotNull();
    }

    @Test
    @DisplayName("Micrometer carries application=trove-core and one honest custom meter")
    void metricsCarryTheCommonTagAndTheBuildGauge() {
        // Asserted against the registry rather than GET /actuator/prometheus: every management
        // endpoint other than health still answers 401, which collapse step 4 decides on
        // (§5.9/§9). The registry is the same object that endpoint would render.
        Gauge buildInfo = meterRegistry.find("trove.build.info").gauge();

        assertThat(buildInfo).isNotNull();
        assertThat(buildInfo.value()).isEqualTo(1.0);
        assertThat(buildInfo.getId().getTag("version")).isNotBlank();
        assertThat(buildInfo.getId().getTag("application")).isEqualTo("trove-core");

        assertThat(meterRegistry.getMeters())
            .as("the common tag is on every meter, not only the custom one")
            .isNotEmpty()
            .allSatisfy(meter ->
                assertThat(meter.getId().getTag("application")).isEqualTo("trove-core"));

        assertThat(meterRegistry.getMeters())
            .as("the build gauge is always there, whatever else has recorded by now")
            .extracting(meter -> meter.getId().getName())
            .contains("trove.build.info");

        // §5.9's actual rule, stated as itself rather than as a list of names.
        //
        // This used to read `containsExactly("trove.build.info")`, which said the same thing ONLY
        // because no other trove.* meter had been recorded into by the time this class ran: a
        // Micrometer counter does not exist in the registry until something increments it, so at
        // that point in the suite the set of trove.* meters happened to be the single eager gauge.
        // That made the assertion a fact about SUITE ORDER rather than about the application —
        // collapse step 11's `trove.notification.sent` is incremented by an integration test that
        // runs before this one, and a correct meter, doing exactly what §5.9 asks, turned this
        // red. Asserting the rule directly is both stabler and stricter: a meter registered
        // eagerly and never recorded into — the dead-meter case §5.9 exists to catch — still
        // fails, and it now fails whatever order the classes run in.
        assertThat(meterRegistry.getMeters())
            .as("no trove.* meter is registered that nothing records into (§5.9)")
            .filteredOn(meter -> meter.getId().getName().startsWith("trove."))
            .isNotEmpty()
            .allSatisfy(meter -> assertThat(recordedInto(meter))
                .as("%s is registered but nothing has ever recorded into it",
                    meter.getId().getName())
                .isTrue());
    }

    /**
     * Whether anything has actually been recorded into a meter.
     *
     * <p>Read from {@link Meter#measure()} rather than by casting to each meter type, so it holds
     * for a counter, a timer, a summary and a gauge alike: a meter nobody has recorded into
     * measures zero (or NaN, for a gauge whose reference has gone) on every one of its
     * measurements, and one that has been recorded into does not.
     */
    private static boolean recordedInto(Meter meter) {
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (value != 0.0 && !Double.isNaN(value)) {
                return true;
            }
        }
        return false;
    }
}
