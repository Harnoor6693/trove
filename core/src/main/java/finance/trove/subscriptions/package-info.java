/**
 * Recurring charges detected in the household's ledger, the alerts they raise, and the three
 * triggers that run detection ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.7, §4.8,
 * §6 D2).
 *
 * <h2>What this package replaces</h2>
 * <p>It is the collapse of {@code services/subscription-detector}, whose own application class
 * carried — unusually for that repository — an accurate description of the detection algorithm.
 * The class is deleted; the description is kept here, corrected where the collapse changed the
 * algorithm, because it is the only written specification the feature ever had.
 *
 * <h2>The algorithm</h2>
 * <ol>
 *   <li>Read the household's DEBIT transactions of the last thirteen months through
 *       {@code TransactionQueryService.debitsSince}, which already excludes transfer legs (gap
 *       P2-14). The window is thirteen months, not the source's ninety days, so that an ANNUAL
 *       cadence can be seen at all — ninety days cannot contain two annual charges, and the
 *       source could therefore never detect one.</li>
 *   <li>Group by {@code finance.trove.categorisation.MerchantNormaliser}-normalised merchant
 *       name. That is the categorisation package's normaliser, deliberately, and NOT the source's
 *       own {@code MerchantNameNormalizer}: two normalisers in one JVM means the same merchant
 *       becomes two subscriptions on one screen and one on another.</li>
 *   <li>Split each merchant by currency and treat each currency as its own candidate — see
 *       {@code SubscriptionDetectionService} for what happens when a merchant has two.</li>
 *   <li>Compute the day gaps between consecutive charges and classify them with
 *       {@code IntervalClassifier}: at least 70% of the gaps must fall inside one
 *       {@code BillingCycle}'s tolerance window.</li>
 *   <li>Check the amounts with {@code AmountLevels}: the charges must form at most two price
 *       levels (a constant price, or one price change). Three or more levels is variable
 *       spending, not a subscription.</li>
 *   <li>Score the candidate with {@code ConfidenceCalculator} and discard it below 0.75.</li>
 *   <li>Upsert one row per merchant with
 *       {@code INSERT … ON CONFLICT (household_id, merchant_name) DO UPDATE}, and link the
 *       contributing transactions through {@code subscription_transaction}.</li>
 *   <li>When the upsert sees a new amount in the same currency, publish
 *       {@code SubscriptionPriceChangedEvent} and, when the amount went UP, write a
 *       {@code PRICE_INCREASE} alert.</li>
 * </ol>
 *
 * <h2>The three triggers, one method</h2>
 * <p>{@code SubscriptionDetectionService.detectForHousehold(householdId)} is called by all three:
 * the nightly {@code subscription-scan} job, {@code POST /v1/subscriptions/scan}, and the listener
 * on {@code TransactionsIngestedEvent}. Because the write is an upsert on a real unique
 * constraint, they may overlap freely: two scans of one household running at once produce one row.
 *
 * <h2>Tenancy</h2>
 * <p>{@code household_id} is the scope (§1.5) — the ledger is shared, so a subscription belongs to
 * the household and every member sees it. {@code user_id} is created-by, for audit; on a scheduled
 * run there is no caller, so the household's own administrator is recorded, exactly as
 * {@code NetWorthSnapshotService} does.
 */
package finance.trove.subscriptions;
