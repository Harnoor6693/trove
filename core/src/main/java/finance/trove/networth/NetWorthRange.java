package finance.trove.networth;

/**
 * The window {@code GET /v1/networth/series} reports
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.7, §6 "Net worth").
 *
 * <p>The pre-collapse controller {@code @Pattern}-validated a free {@code String} against exactly
 * these four literals ({@code "3M"}, {@code "6M"}, {@code "1Y"}, {@code "ALL"}) — none of which is
 * a legal Java identifier, which is why the constants here are spelled out and
 * {@link #fromWireValue(String)} does the mapping. {@link NetWorthRangeConverter} is what lets
 * Spring bind the query parameter straight to this type (and default it), so an out-of-range
 * value never reaches the controller at all: it fails at argument resolution as a
 * {@code MethodArgumentTypeMismatchException}, which {@code GlobalExceptionHandler} turns into a
 * 400 {@code validation-error} — the same outcome the source's {@code @Pattern} produced, now
 * predicted by the OpenAPI document as a real enum rather than discovered at runtime.
 */
public enum NetWorthRange {

    THREE_MONTHS,
    SIX_MONTHS,
    ONE_YEAR,
    ALL;

    /**
     * @param wireValue one of {@code "3M"}, {@code "6M"}, {@code "1Y"}, {@code "ALL"}
     * @throws IllegalArgumentException for anything else — the signal
     *         {@link NetWorthRangeConverter} turns into a 400
     */
    public static NetWorthRange fromWireValue(String wireValue) {
        return switch (wireValue) {
            case "3M" -> THREE_MONTHS;
            case "6M" -> SIX_MONTHS;
            case "1Y" -> ONE_YEAR;
            case "ALL" -> ALL;
            default -> throw new IllegalArgumentException(
                "range must be one of: 3M, 6M, 1Y, ALL");
        };
    }
}
