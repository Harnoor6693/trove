package finance.trove.common.json;

import finance.trove.common.domain.Money;
import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.jdk.NumberDeserializers;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * The Jackson module that puts every monetary value on the wire as a decimal
 * <strong>string</strong> ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.4).
 *
 * <p>The serializer is registered for {@link BigDecimal} <em>globally</em>, not for
 * {@code finance.trove.common.domain.Money} alone. That is the whole point of §5.4: most amounts
 * in the schema — {@code Transaction.amount}, {@code Envelope.allocated/spent},
 * {@code BudgetPeriod.totalIncome}, every dashboard aggregate — are raw {@code BigDecimal}
 * fields with no {@code Money} wrapper, so a {@code Money}-scoped serializer would leave them as
 * JSON numbers and both clients would parse them into IEEE-754 doubles. A finance ledger cannot
 * round-trip {@code 0.1 + 0.2} through a double.
 *
 * <p>{@link BigDecimal#toPlainString()} rather than {@code toString()} is load-bearing:
 * {@code new BigDecimal("1E+2").toString()} is {@code "1E+2"}, which is a valid JSON string but
 * not a number any client's decimal parser accepts, and it is exactly what a
 * {@code SUM()}/{@code stripTrailingZeros()} result looks like coming out of Postgres.
 * {@code TroveJsonModuleTest} asserts that {@code E+} never appears in serialised output.
 *
 * <p>On the way in, every {@code BigDecimal} is bounded (B2-02): {@code "1e99999999"} parses in
 * microseconds and then costs a minute of CPU in the first {@code setScale}, and
 * {@code @DecimalMin} cannot see it coming. A value the {@code NUMERIC(19,4)} columns cannot hold
 * ({@link Money#fitsColumn}) is refused here, so the request is a 400 before any service runs.
 */
public final class TroveJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public TroveJsonModule() {
        super("trove");
        addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
        addDeserializer(BigDecimal.class, new BoundedBigDecimalDeserializer());
    }

    /** Reads a {@code BigDecimal} only when it is short and fits {@code NUMERIC(19,4)}. */
    public static final class BoundedBigDecimalDeserializer
        extends NumberDeserializers.BigDecimalDeserializer {

        private static final long serialVersionUID = 1L;

        /** Far longer than any amount the column holds, however it is padded with zeros. */
        private static final int MAX_CHARS = 64;

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            JsonToken token = parser.currentToken();
            if ((token == JsonToken.VALUE_STRING || token != null && token.isNumeric())
                && parser.getStringLength() > MAX_CHARS) {
                throw new AmountOutOfRangeException(parser, "is longer than the "
                    + MAX_CHARS + " characters any amount needs");
            }
            BigDecimal value = super.deserialize(parser, context);
            if (value != null && !Money.fitsColumn(value)) {
                throw new AmountOutOfRangeException(parser, "has at most "
                    + Money.MAX_INTEGER_DIGITS + " digits before the decimal point and "
                    + Money.MAX_FRACTION_DIGITS + " after it");
            }
            return value;
        }
    }

    /**
     * A well-formed number this server cannot store. Its message names the rule and never the
     * value, so {@code GlobalExceptionHandler} can answer it as a field error on the property.
     */
    public static final class AmountOutOfRangeException extends InvalidFormatException {

        private static final long serialVersionUID = 1L;

        AmountOutOfRangeException(JsonParser parser, String rule) {
            super(parser, rule, null, BigDecimal.class);
        }
    }

    /** Writes a {@code BigDecimal} as a JSON string in plain (non-scientific) notation. */
    public static final class PlainStringBigDecimalSerializer extends StdSerializer<BigDecimal> {

        private static final long serialVersionUID = 1L;

        public PlainStringBigDecimalSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator generator,
                              SerializationContext context) {
            generator.writeString(value.toPlainString());
        }
    }
}
