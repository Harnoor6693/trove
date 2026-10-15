package finance.trove.common.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An immutable amount in a single {@link CurrencyCode}.
 *
 * <p>Three invariants hold for every instance: the amount is non-null, the currency is non-null,
 * and the amount carries exactly {@link CurrencyCode#minorUnits()} decimal places (half-up).
 * Every operation returns a new instance, so a {@code Money} is safe to share across threads
 * and to hand out of a service without defensive copying.
 *
 * <p><strong>No floating point, ever.</strong> There is no {@code double} or {@code float} on
 * this type's API — {@code ArchitectureTest} enforces that for the whole {@code domain} package
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 6). Cross-currency arithmetic throws rather
 * than silently mixing CAD and USD, which is the bug that would corrupt envelope balances and
 * net worth (§9 risk 5).
 *
 * <p>On the wire, {@code amount} is a decimal <em>string</em> — {@code {"amount":"12.34",
 * "currency":"CAD"}}. That is not a property of this class: §5.4 installs a <em>global</em>
 * {@code BigDecimal} serializer in {@code finance.trove.common.json.TroveJsonModule}, because
 * most amounts in the schema are raw {@code BigDecimal} columns with no {@code Money} wrapper
 * and a serializer scoped to {@code Money} alone would leave those as JSON numbers.
 *
 * <p>The visibility annotation is load-bearing rather than decorative: with Jackson's default
 * bean introspection this type serialises as
 * {@code {"negative":false,"positive":true,"zero":false}}, because {@link #isNegative()},
 * {@link #isPositive()} and {@link #isZero()} look like bean is-getters while {@code amount()}
 * and {@code currency()} do not look like getters at all. Pinning detection to the two fields
 * makes the wire shape a property of the declaration instead of an accident of method naming, so
 * adding another {@code isSomething()} helper later cannot silently change the contract.
 */
@JsonAutoDetect(
    fieldVisibility = JsonAutoDetect.Visibility.ANY,
    getterVisibility = JsonAutoDetect.Visibility.NONE,
    isGetterVisibility = JsonAutoDetect.Visibility.NONE,
    setterVisibility = JsonAutoDetect.Visibility.NONE,
    creatorVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Money implements Comparable<Money> {

    private final BigDecimal amount;
    private final CurrencyCode currency;

    @JsonCreator
    public Money(@JsonProperty("amount") BigDecimal amount,
                 @JsonProperty("currency") CurrencyCode currency) {
        Objects.requireNonNull(amount, "amount cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.amount = currency.normalise(amount);
    }

    /** {@code Money.of("12.34", CAD)} — the string form keeps a literal exact by construction. */
    public static Money of(String amount, CurrencyCode currency) {
        return new Money(new BigDecimal(Objects.requireNonNull(amount, "amount cannot be null")),
            currency);
    }

    /** {@code Money.of(1234L, CAD)} — a whole number of major units, not cents. */
    public static Money of(long majorUnits, CurrencyCode currency) {
        return new Money(BigDecimal.valueOf(majorUnits), currency);
    }

    /** Reconstitutes an amount stored as an integral number of minor units (cents). */
    public static Money ofMinorUnits(long minorUnits, CurrencyCode currency) {
        return new Money(
            BigDecimal.valueOf(minorUnits).movePointLeft(currency.minorUnits()), currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public CurrencyCode currency() {
        return currency;
    }

    /** The amount as an integral number of minor units — for storage and for wire formats. */
    public long toMinorUnits() {
        return amount.movePointRight(currency.minorUnits()).longValueExact();
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * Multiplies by an exact decimal factor (a share, a rate, a count) and re-rounds to the
     * currency's scale. The factor is a {@link BigDecimal} rather than a {@code double} so that
     * "10% of 0.35" cannot pick up an IEEE-754 error on the way through.
     */
    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor cannot be null");
        return new Money(amount.multiply(factor), currency);
    }

    /** Divides by an exact divisor, rounding half-up to the currency's scale. */
    public Money divide(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor cannot be null");
        if (divisor.signum() == 0) {
            throw new IllegalArgumentException("Cannot divide " + this + " by zero");
        }
        return new Money(
            amount.divide(divisor, currency.minorUnits(), RoundingMode.HALF_UP), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /**
     * Orders two amounts of the <em>same</em> currency. Consistent with {@link #equals(Object)}
     * because the scale is fixed at construction, so {@code 1.5} and {@code 1.50} are the same
     * {@link BigDecimal} here.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return currency == other.currency && amount.equals(other.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    /** {@code "12.34 CAD"} — plain notation, never {@code 1.234E+1}. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other cannot be null");
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                "Cannot combine amounts in different currencies: " + currency + " and "
                    + other.currency);
        }
    }
}
