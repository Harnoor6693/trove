package finance.trove.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The closed set of currencies Trove supports (owner decision 3: Canada and the USA are both
 * first-class; other currencies come later).
 *
 * <p>Constraining currency to a compile-time enum rather than a free-form string keeps invalid
 * codes out of the ledger and makes currency-specific logic exhaustively checkable in a
 * {@code switch}. It is also the one place a currency literal may appear —
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 5 makes that an ArchUnit rule.
 *
 * <p>{@link #minorUnits()} is the authority on rounding: every {@link Money} amount is
 * normalised to this scale, and §9 risk 6 pins the database columns to {@code NUMERIC(19,4)}
 * so that a stored amount can always be represented exactly at this scale.
 */
public enum CurrencyCode {

    /** Canadian dollar; 100 cents. Default currency for {@link Jurisdiction#CA}. */
    CAD(2),

    /** United States dollar; 100 cents. Default currency for {@link Jurisdiction#US}. */
    USD(2);

    private final int minorUnits;

    CurrencyCode(int minorUnits) {
        this.minorUnits = minorUnits;
    }

    /** Number of decimal places this currency is quoted and rounded to. */
    public int minorUnits() {
        return minorUnits;
    }

    /**
     * Rounds {@code amount} to this currency's minor-unit scale, half-up.
     *
     * @param amount the unrounded amount; never {@code null}
     * @return the same value at exactly {@link #minorUnits()} decimal places
     */
    public BigDecimal normalise(BigDecimal amount) {
        return amount.setScale(minorUnits, RoundingMode.HALF_UP);
    }

    /** The jurisdiction whose default currency this is. */
    public Jurisdiction defaultJurisdiction() {
        return switch (this) {
            case CAD -> Jurisdiction.CA;
            case USD -> Jurisdiction.US;
        };
    }
}
