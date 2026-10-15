package finance.trove.networth;

/**
 * What kind of liability a row is, for display and reporting
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V28).
 */
public enum LiabilityType {
    MORTGAGE,
    CAR_LOAN,
    STUDENT_LOAN,
    CREDIT_CARD,
    OTHER
}
