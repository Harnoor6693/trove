package finance.trove.budgets.period;

/**
 * How a {@link BudgetPeriod} constrains the envelopes inside it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 D1).
 *
 * <p>The value set is unchanged from the pre-collapse service; what changed is that two of the
 * three now mean something. {@code ZERO_BASED}'s invariant was enforced against a real allocation
 * sum but rejected with HTTP 422, a status this contract has no slug for at all — it is a 400
 * {@code validation-error} here. {@code ROLLOVER}'s carry-forward was computed against an
 * {@code Envelope.spent} that nothing ever wrote (finding D1), so it carried the full allocation
 * forward every month and compounded without bound; the spend writer this step adds is what makes
 * the arithmetic mean what it says.
 */
public enum BudgetType {

    /** No allocation constraint: the envelopes may total anything at all. */
    FLEXIBLE,

    /**
     * Every dollar allocated. After any envelope write the period's allocations must sum to
     * exactly {@link BudgetPeriod#getTotalIncome()}; one that would break that is refused.
     */
    ZERO_BASED,

    /**
     * Unspent balance carries into the next month — {@code allocated + rolloverAmount - spent},
     * floored at zero — when {@code EnvelopeRolloverJob} processes the finished month.
     *
     * <p>The floor is deliberate and inherited: an overspent envelope carries nothing rather than
     * carrying a debt. It is also the reason gap P2-39 calls this "forgiving overspend silently",
     * and that trade-off is recorded rather than changed here, because a per-budget overspend
     * policy is a product decision this step is not the place to make.
     */
    ROLLOVER
}
