package finance.trove.common.json;

import java.math.BigDecimal;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
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
 */
public final class TroveJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    public TroveJsonModule() {
        super("trove");
        addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
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
