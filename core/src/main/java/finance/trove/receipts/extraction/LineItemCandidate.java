package finance.trove.receipts.extraction;

import java.math.BigDecimal;

/**
 * One line an extractor believes it read off a receipt, before anything is persisted
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p>Only the scoring stage promotes these to {@code line_item} rows, and only when the composite
 * confidence clears its threshold — so a candidate is always a suggestion, never a fact.
 *
 * <p>{@link BigDecimal} throughout: {@code ArchitectureTest} bans binary floating point on domain
 * types (§9 risk 6), and money that has been through a {@code double} is money that has been
 * rounded by something nobody chose.
 *
 * @param description the item as printed; never null, never blank
 * @param quantity    how many; one when the receipt did not say
 * @param unitPrice   price each; zero when the receipt did not say
 * @param totalPrice  the line total as printed
 * @param taxable     whether the extractor believed the line attracts sales tax
 */
public record LineItemCandidate(String description, BigDecimal quantity, BigDecimal unitPrice,
                                BigDecimal totalPrice, boolean taxable) {

    /** The shape the heuristic parser can produce: a description and a total, nothing more. */
    public static LineItemCandidate of(String description, BigDecimal totalPrice) {
        return new LineItemCandidate(description, BigDecimal.ONE, BigDecimal.ZERO, totalPrice,
            true);
    }
}
