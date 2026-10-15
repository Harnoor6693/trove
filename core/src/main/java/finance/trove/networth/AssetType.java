package finance.trove.networth;

/**
 * What kind of asset a row is, for display and reporting
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V27).
 */
public enum AssetType {
    CASH,
    INVESTMENT,
    REAL_ESTATE,
    VEHICLE,
    OTHER
}
