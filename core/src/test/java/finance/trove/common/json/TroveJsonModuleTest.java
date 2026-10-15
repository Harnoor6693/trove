package finance.trove.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire contract of {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.4, asserted on a mapper
 * configured exactly as {@code finance.trove.app.JacksonConfig} configures the application's.
 *
 * <p>The mapper is built here rather than injected because these assertions are about the
 * serializer's behaviour on values a running application would rarely produce on demand —
 * {@code 1E+2}, a bare {@code BigDecimal} field with no {@code Money} wrapper — and a unit test
 * can enumerate them where an integration test would need an endpoint per case.
 * {@code CommonFoundationIT} then proves the same mapper is the one the application actually
 * serves with.
 */
class TroveJsonModuleTest {

    /** Mirrors JacksonConfig; kept in sync by CommonFoundationIT, which asserts the live one. */
    private final JsonMapper mapper = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .addModule(new TroveJsonModule())
        .build();

    @Test
    @DisplayName("Money round-trips as {\"amount\":\"12.34\",\"currency\":\"CAD\"}")
    void moneyRoundTripsAsADecimalString() {
        Money original = Money.of("12.34", CurrencyCode.CAD);

        String json = mapper.writeValueAsString(original);
        assertThat(json).isEqualTo("{\"amount\":\"12.34\",\"currency\":\"CAD\"}");

        assertThat(mapper.readValue(json, Money.class)).isEqualTo(original);
    }

    @Test
    @DisplayName("a raw BigDecimal field is a string too — most amounts have no Money wrapper")
    void rawBigDecimalFieldsAreAlsoStrings() {
        String json = mapper.writeValueAsString(
            new Transaction(new BigDecimal("1234.50"), "Coffee"));

        assertThat(json).isEqualTo("{\"amount\":\"1234.50\",\"merchantName\":\"Coffee\"}");
    }

    @ParameterizedTest(name = "{0} never serialises in scientific notation")
    @ValueSource(strings = {"1E+2", "1E-2", "1.2345E+10", "0.00000001", "100000000000000000000"})
    @DisplayName("no BigDecimal ever reaches the wire as 1E+2")
    void scientificNotationNeverReachesTheWire(String value) {
        String json = mapper.writeValueAsString(new Transaction(new BigDecimal(value), "x"));

        assertThat(json).doesNotContain("E+").doesNotContain("E-").doesNotContain("e+");
        assertThat(json).contains("\"" + new BigDecimal(value).toPlainString() + "\"");
    }

    @Test
    @DisplayName("field names are camelCase, not snake_case (§4.13: the gateway's casing changes)")
    void propertyNamesAreCamelCase() {
        String json = mapper.writeValueAsString(new Transaction(BigDecimal.ONE, "x"));

        assertThat(json).contains("merchantName").doesNotContain("merchant_name");
    }

    @Test
    @DisplayName("dates are ISO-8601 strings, not epoch numbers")
    void datesAreIso8601() {
        String json = mapper.writeValueAsString(new Timestamped(Instant.parse("2026-09-03T12:00:00Z")));

        assertThat(json).isEqualTo("{\"at\":\"2026-09-03T12:00:00Z\"}");
    }

    @Test
    @DisplayName("an unknown property from a newer client does not 400 the request")
    void unknownPropertiesAreIgnored() {
        Money parsed = mapper.readValue(
            "{\"amount\":\"1.00\",\"currency\":\"USD\",\"displayHint\":\"bold\"}", Money.class);

        assertThat(parsed).isEqualTo(Money.of("1.00", CurrencyCode.USD));
    }

    /** Stands in for Transaction/Envelope/BudgetPeriod: a raw BigDecimal with no wrapper. */
    private record Transaction(BigDecimal amount, String merchantName) {
    }

    private record Timestamped(Instant at) {
    }
}
