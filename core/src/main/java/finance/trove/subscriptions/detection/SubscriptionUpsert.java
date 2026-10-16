package finance.trove.subscriptions.detection;

import finance.trove.common.domain.CurrencyCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one write that creates or updates a {@code detected_subscription}, and the join rows that
 * record what it was detected from ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "D2 —
 * subscription scan"; inventory SD-5).
 *
 * <h2>Why this is an upsert and not a find-then-save</h2>
 * <p>The source did {@code findByUserIdAndMerchantName(...).orElseGet(...)} then {@code save(...)}
 * ({@code SubscriptionDetectionService.java:124-138}). That is a check-then-act, and it was safe
 * only because one sequential scheduler was the only caller. This step adds two more callers — a
 * manual trigger and a listener on ingested transactions — so two scans of the SAME household
 * really can run at once, and two of them can both miss each other's insert and both insert. V29
 * adds {@code UNIQUE (household_id, merchant_name)} and this statement targets it, so the second
 * writer updates instead of failing or duplicating.
 *
 * <h2>Why one statement and not two</h2>
 * <p>Deciding whether the price changed needs the row as it was BEFORE the write, and reading it
 * separately would reopen exactly the race the upsert closes. The {@code before} CTE reads the
 * pre-image from the same snapshot the insert runs against, so the caller gets "what it used to
 * be" and "what it is now" from one round trip and one point in time.
 *
 * <h2>What the DO UPDATE deliberately does not touch</h2>
 * <ul>
 *   <li>{@code is_cancelled} — a dismissed subscription is never resurrected by a scan;</li>
 *   <li>{@code user_id} and {@code created_at} — created-by is a fact about how the row came to
 *       exist, not something a later scan re-attributes;</li>
 *   <li>{@code first_seen_date} is {@code LEAST(old, new)}, so a window that has rolled forward
 *       past the first charge cannot make a subscription look younger than it is.</li>
 * </ul>
 *
 * <p>{@code updated_at} moves only when a column actually changed, so a repeat scan is a no-op
 * on the wire as well as in the ledger — a nightly job that stamped it unconditionally would make
 * every subscription look freshly changed every morning.
 *
 * <p>{@code price_changed} and {@code previous_amount} are STICKY: a later scan that sees no
 * change leaves them alone. That is what makes a repeat scan a true no-op in every column, and it
 * is why {@code signal} can be derived from them rather than from "did this particular run notice
 * something". A currency change resets both, because an amount in a currency the subscription is
 * no longer billed in cannot be compared with the current one.
 */
@Component
public class SubscriptionUpsert {

    /**
     * The signal the row ends up with, as one SQL expression.
     *
     * <p>Written once and substituted in two places — the {@code signal} assignment and the
     * {@code updated_at} guard, which has to compare the stored signal against the value this
     * statement is about to write rather than against {@code EXCLUDED.signal}. Comparing against
     * the latter would stamp {@code updated_at} on every scan of any subscription whose price has
     * ever changed: its stored signal is a sticky {@code AMBER} while the incoming one is
     * {@code GREEN}, and they would differ for ever.
     *
     * <p>{@code RED} beats everything — a charge that has not arrived is more urgent than a price
     * that moved. Constant text; nothing caller-supplied reaches it.
     */
    private static final String SIGNAL_EXPRESSION = """
        CASE
            WHEN EXCLUDED.signal = 'RED' THEN 'RED'
            WHEN detected_subscription.currency IS DISTINCT FROM EXCLUDED.currency
                THEN EXCLUDED.signal
            WHEN detected_subscription.price_changed
                 OR detected_subscription.normalized_amount
                    IS DISTINCT FROM EXCLUDED.normalized_amount
                THEN 'AMBER'
            ELSE EXCLUDED.signal END
        """;

    /**
     * The pre-image, the upsert and the returned outcome, in one statement.
     *
     * <p>Every caller-supplied value is a bound parameter; the only thing assembled here is
     * constant text. The {@code CAST(... AS type)} on each parameter is not decoration — Postgres
     * cannot infer the type of a bare parameter inside a {@code VALUES} list that also feeds an
     * {@code ON CONFLICT} comparison, and a null {@code category_id} would fail outright without
     * one.
     */
    private static final String UPSERT_SQL = ("""
        WITH before AS (
            SELECT normalized_amount AS old_amount,
                   currency          AS old_currency,
                   is_cancelled      AS was_cancelled
            FROM detected_subscription
            WHERE household_id = CAST(:householdId AS uuid)
              AND merchant_name = :merchantName
        ),
        upserted AS (
            INSERT INTO detected_subscription (
                household_id, user_id, merchant_name, category_id, normalized_amount,
                previous_amount, currency, billing_cycle, next_charge_date, signal,
                price_changed, first_seen_date, last_seen_date, is_cancelled,
                created_at, updated_at)
            VALUES (
                CAST(:householdId AS uuid), CAST(:userId AS uuid), :merchantName,
                CAST(:categoryId AS uuid), CAST(:amount AS numeric),
                NULL, :currency, :billingCycle, CAST(:nextChargeDate AS date), :baseSignal,
                false, CAST(:firstSeen AS date), CAST(:lastSeen AS date), false,
                now(), now())
            ON CONFLICT (household_id, merchant_name) DO UPDATE SET
                category_id = EXCLUDED.category_id,
                normalized_amount = EXCLUDED.normalized_amount,
                previous_amount = CASE
                    WHEN detected_subscription.currency IS DISTINCT FROM EXCLUDED.currency
                        THEN NULL
                    WHEN detected_subscription.normalized_amount
                         IS DISTINCT FROM EXCLUDED.normalized_amount
                        THEN detected_subscription.normalized_amount
                    ELSE detected_subscription.previous_amount END,
                currency = EXCLUDED.currency,
                billing_cycle = EXCLUDED.billing_cycle,
                next_charge_date = EXCLUDED.next_charge_date,
                price_changed = CASE
                    WHEN detected_subscription.currency IS DISTINCT FROM EXCLUDED.currency
                        THEN false
                    ELSE detected_subscription.price_changed
                         OR detected_subscription.normalized_amount
                            IS DISTINCT FROM EXCLUDED.normalized_amount END,
                signal = SIGNAL_PLACEHOLDER,
                first_seen_date = LEAST(detected_subscription.first_seen_date,
                                        EXCLUDED.first_seen_date),
                last_seen_date = EXCLUDED.last_seen_date,
                -- Only when something actually moved. A scan that re-reads the same 25
                -- months is meant to be a no-op in every column a member can see, and
                -- `updated_at` is one of them: a nightly job that touched it would make every
                -- subscription look freshly changed every morning.
                updated_at = CASE
                    WHEN detected_subscription.normalized_amount
                             IS DISTINCT FROM EXCLUDED.normalized_amount
                      OR detected_subscription.currency IS DISTINCT FROM EXCLUDED.currency
                      OR detected_subscription.billing_cycle
                             IS DISTINCT FROM EXCLUDED.billing_cycle
                      OR detected_subscription.next_charge_date
                             IS DISTINCT FROM EXCLUDED.next_charge_date
                      OR detected_subscription.last_seen_date
                             IS DISTINCT FROM EXCLUDED.last_seen_date
                      OR detected_subscription.first_seen_date
                             IS DISTINCT FROM LEAST(detected_subscription.first_seen_date,
                                                    EXCLUDED.first_seen_date)
                      OR detected_subscription.category_id
                             IS DISTINCT FROM EXCLUDED.category_id
                      OR detected_subscription.signal IS DISTINCT FROM SIGNAL_PLACEHOLDER
                        THEN now()
                    ELSE detected_subscription.updated_at END
            RETURNING id
        )
        SELECT u.id, b.old_amount, b.old_currency, b.was_cancelled
        FROM upserted u LEFT JOIN before b ON true
        """).replace("SIGNAL_PLACEHOLDER", SIGNAL_EXPRESSION);

    private final EntityManager entityManager;

    public SubscriptionUpsert(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * What the upsert found and what it wrote.
     *
     * @param subscriptionId  the row's id, whether it was inserted or updated
     * @param previousAmount  the amount stored BEFORE this write, or {@code null} on an insert
     * @param previousCurrency the currency stored before this write, or {@code null}
     * @param wasCancelled    whether the row was already dismissed by a member
     */
    public record Outcome(UUID subscriptionId, BigDecimal previousAmount,
                          CurrencyCode previousCurrency, boolean wasCancelled) {

        /**
         * Whether this write changed the price in a way that is meaningful to compare.
         *
         * <p>False on an insert (there is nothing to compare with), false across a currency change
         * (the two amounts are not commensurable — {@code SubscriptionPriceChangedEvent}'s
         * constructor refuses such a pair outright), and false for a dismissed subscription, which
         * the member has asked not to hear about.
         */
        public boolean isComparablePriceChange(CurrencyCode currency, BigDecimal amount) {
            return !wasCancelled
                && previousAmount != null
                && previousCurrency == currency
                && previousAmount.compareTo(amount) != 0;
        }
    }

    /**
     * Creates or updates the household's row for this merchant.
     *
     * @param householdId the ledger
     * @param userId      the member the run is attributed to; created-by only
     * @param candidate   what the scan concluded
     * @return the row's id and its pre-image
     */
    public Outcome apply(UUID householdId, UUID userId, SubscriptionCandidate candidate) {
        Object[] row = (Object[]) entityManager.createNativeQuery(UPSERT_SQL)
            .setParameter("householdId", householdId)
            .setParameter("userId", userId)
            .setParameter("merchantName", candidate.merchantName())
            .setParameter("categoryId", candidate.categoryId())
            .setParameter("amount", candidate.amount())
            .setParameter("currency", candidate.currency().name())
            .setParameter("billingCycle", candidate.billingCycle().name())
            .setParameter("nextChargeDate", candidate.nextChargeDate())
            .setParameter("baseSignal", candidate.baseSignal())
            .setParameter("firstSeen", candidate.firstSeenDate())
            .setParameter("lastSeen", candidate.lastSeenDate())
            .getSingleResult();

        return new Outcome(asUuid(row[0]), (BigDecimal) row[1],
            row[2] == null ? null : CurrencyCode.valueOf((String) row[2]),
            Boolean.TRUE.equals(row[3]));
    }

    /**
     * Records which transactions a detection was built from.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than delete-then-insert: the link is a historical
     * fact — this charge was part of this subscription — and it stays true after the charge falls
     * out of the 25-month window. Re-running a scan therefore writes nothing the second
     * time, which is part of what makes the whole scan idempotent. A deleted transaction takes its
     * link with it through V29's {@code ON DELETE CASCADE}.
     *
     * <p>The statement is assembled from constant fragments and an index — every id is a bound
     * parameter, so nothing caller-supplied reaches the SQL as syntax.
     */
    public void linkTransactions(UUID subscriptionId, List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
            "INSERT INTO subscription_transaction (subscription_id, transaction_id) VALUES ");
        for (int i = 0; i < transactionIds.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(CAST(:subscriptionId AS uuid), CAST(:transaction").append(i)
                .append(" AS uuid))");
        }
        sql.append(" ON CONFLICT DO NOTHING");

        Query query = entityManager.createNativeQuery(sql.toString())
            .setParameter("subscriptionId", subscriptionId);
        for (int i = 0; i < transactionIds.size(); i++) {
            query.setParameter("transaction" + i, transactionIds.get(i));
        }
        query.executeUpdate();
    }

    /** Postgres {@code uuid} comes back as a {@link UUID}; a driver that hands back text still works. */
    private static UUID asUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
