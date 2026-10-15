package finance.trove.receipts.extraction;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What {@link HeuristicExtractor} read off a block of text
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p>Any of the four fields may be null: the regexes found no match, and a null is honest where a
 * default would be a fabrication. {@code lineItems} is never null and is empty when fewer than two
 * candidates were found — one price line on a receipt is far more likely to be the total than a
 * genuine item list.
 *
 * <p>{@code confidence} is a {@link BigDecimal} in {@code [0.000, 1.000]} rather than a
 * {@code double}: {@code ArchitectureTest} bans binary floating point on domain types (§9 risk 6),
 * and it ends up in a {@code NUMERIC(4,3)} column.
 */
public record HeuristicExtractionResult(String merchant, BigDecimal amount, CurrencyCode currency,
                                        LocalDate date, List<LineItemCandidate> lineItems,
                                        BigDecimal confidence) {

    public HeuristicExtractionResult {
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    /** Nothing was readable. */
    public static HeuristicExtractionResult empty() {
        return new HeuristicExtractionResult(null, null, null, null, List.of(), BigDecimal.ZERO);
    }
}
