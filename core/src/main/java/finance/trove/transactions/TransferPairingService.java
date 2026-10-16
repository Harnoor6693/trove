package finance.trove.transactions;

import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.plugin.TransactionType;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links the two legs of a movement between accounts the household already owns
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-14).
 *
 * <p>Paying a credit card from a chequing account produces a DEBIT on one and a CREDIT on the
 * other. Without a link, every aggregate in the product counts the first as spending and the
 * second as income, so a household that pays off $2,000 a month appears to spend $2,000 more and
 * earn $2,000 more than it does — inflating budgets, the dashboard, net-worth change and the tax
 * export at once. {@code transaction.transfer_group_id} is what lets each of those exclude both
 * legs; this class is what sets it.
 *
 * <h2>The rules, and why each one is there</h2>
 * <ul>
 *   <li><strong>Different accounts.</strong> A transfer moves money between two of the
 *       household's accounts. Two rows on the SAME account with opposite signs are far more
 *       likely to be a purchase and its refund, and hiding a refund from the budget would be
 *       worse than showing it.</li>
 *   <li><strong>Opposite direction.</strong> One DEBIT and one CREDIT. A row already typed
 *       {@code TRANSFER} by its source carries no direction, so it is left for a member to link
 *       by hand rather than guessed at.</li>
 *   <li><strong>Equal amount, same currency.</strong> Exactly equal: amounts are
 *       {@code NUMERIC(19,4)} and both legs describe one movement, so a tolerance would only ever
 *       let two unrelated transactions be hidden from the budget. Cross-currency movements are
 *       not paired at all, because netting them would need an exchange rate and this product has
 *       none.</li>
 *   <li><strong>Within three days.</strong> Institutions post the two sides on their own
 *       schedules, and a weekend can separate them; three days spans that without reaching into
 *       the next fortnight's transactions.</li>
 *   <li><strong>Never across households.</strong> Every query is household-scoped, so this is
 *       true by construction rather than by a check that could be forgotten.</li>
 *   <li><strong>Looks like a transfer.</strong> Both legs must say so in their description or
 *       merchant: TRANSFER, XFER, PAYMENT, PAIEMENT or VIREMENT.</li>
 *   <li><strong>One group per leg.</strong> A leg that has been paired is not a candidate for
 *       another pair, so a group is always exactly two legs.</li>
 *   <li><strong>Unambiguous.</strong> A leg is paired only when it has exactly one closest
 *       candidate and that candidate's closest match is the same leg. Ties are left unpaired for
 *       a member to link by hand, so a household that made several identical transfers on the
 *       same day sees them left unpaired because the match is ambiguous.</li>
 * </ul>
 *
 * <p>Because only unpaired legs are ever loaded, running the job twice pairs nothing new: the
 * second run sees an empty candidate list for everything the first one linked. Unlinking by hand
 * ({@code PATCH} with {@code {"transferGroupId": null}}) deliberately makes a leg a candidate
 * again — a member who unlinks a pair the job got wrong will see it re-paired unless they also
 * correct what made it look like a transfer, which is the trade §9 accepts for an automatic
 * pairing rule that needs no configuration.
 */
@Service
public class TransferPairingService {

    /** How far apart the two legs of one movement may be posted. */
    public static final int WINDOW_DAYS = 3;

    /**
     * How far back a run looks. Wider than {@link #WINDOW_DAYS} so that a leg posted late still
     * finds its partner, and bounded so the job's cost does not grow with the ledger.
     */
    public static final int LOOKBACK_DAYS = 30;

    private final TransactionRepository transactions;
    private final ApplicationEventPublisher events;

    public TransferPairingService(TransactionRepository transactions,
                                  ApplicationEventPublisher events) {
        this.transactions = transactions;
        this.events = events;
    }

    /** Households with at least one unpaired leg in the window — the job's work list. */
    @Transactional(readOnly = true)
    public List<UUID> householdsWithUnpairedLegs(LocalDate from, LocalDate to) {
        return transactions.findHouseholdIdsWithUnpairedLegs(from, to);
    }

    /**
     * Pairs what can be paired in one household's window.
     *
     * @return how many groups were created; zero on a second run over the same window
     */
    @Transactional
    public int pairHousehold(UUID householdId, LocalDate from, LocalDate to) {
        List<Transaction> legs = transactions.findUnpairedLegs(householdId, from, to);
        List<Transaction> changed = pair(legs);
        if (!changed.isEmpty()) {
            transactions.saveAll(changed);
            // A paired leg leaves every spend aggregate; tell the envelopes (B2-04).
            for (Transaction leg : changed) {
                if (leg.getCategoryId() != null) {
                    events.publishEvent(new TransactionCategorisedEvent(householdId,
                        leg.getUserId(), leg.getId(), leg.getCategoryId(), leg.getCategoryId(),
                        YearMonth.from(leg.getDate()), leg.getCurrency()));
                }
            }
        }
        return changed.size() / 2;
    }

    /**
     * The rule itself, over a list of unpaired legs — no database, no Spring, so the window, the
     * currency rule, the sign rule and the one-group-per-leg rule are each testable on their own.
     *
     * <p>A leg is paired only when it has exactly one closest candidate and that candidate's
     * closest match is the same leg; identical same-day legs are left unpaired because the match
     * is ambiguous (B1-09).
     *
     * @param legs unpaired transactions of ONE household
     * @return the legs this call modified, both members of every new pair, in pairing order
     */
    static List<Transaction> pair(List<Transaction> legs) {
        List<Transaction> changed = new ArrayList<>();
        Set<UUID> consumed = new HashSet<>();

        for (Transaction left : legs) {
            if (consumed.contains(left.getId()) || !isDirectional(left)) {
                continue;
            }
            List<Transaction> forward = closestMatches(left, legs, consumed);
            if (forward.size() != 1) {
                continue;
            }
            Transaction right = forward.get(0);
            List<Transaction> back = closestMatches(right, legs, consumed);
            if (back.size() != 1 || back.get(0) != left) {
                // Ambiguous: another leg is just as plausible a partner. Pairing would hide one
                // real transaction from every total, so it is left for a member (B1-09).
                continue;
            }
            UUID group = UUID.randomUUID();
            left.setTransferGroupId(group);
            right.setTransferGroupId(group);
            consumed.add(left.getId());
            consumed.add(right.getId());
            changed.add(left);
            changed.add(right);
        }
        return changed;
    }

    /** The legs that pair with {@code leg} and are the nearest in date; more than one is a tie. */
    private static List<Transaction> closestMatches(Transaction leg, List<Transaction> legs,
                                                    Set<UUID> consumed) {
        List<Transaction> best = new ArrayList<>();
        long bestDistance = Long.MAX_VALUE;
        for (Transaction other : legs) {
            if (other == leg || consumed.contains(other.getId()) || !pairs(leg, other)) {
                continue;
            }
            long distance = Math.abs(ChronoUnit.DAYS.between(leg.getDate(), other.getDate()));
            if (distance < bestDistance) {
                best.clear();
                bestDistance = distance;
            }
            if (distance == bestDistance) {
                best.add(other);
            }
        }
        return best;
    }

    /**
     * ponytail: a plain-text heuristic. Both legs must say they are a transfer or a payment; its
     * ceiling is an institution that words one side differently (that pair is left for a member)
     * or a payee whose name contains one of these words.
     */
    private static boolean looksLikeTransfer(Transaction transaction) {
        String text = ((transaction.getDescription() == null ? "" : transaction.getDescription())
            + " " + (transaction.getMerchantName() == null ? "" : transaction.getMerchantName()))
            .toUpperCase(java.util.Locale.ROOT);
        return text.contains("TRANSFER") || text.contains("XFER") || text.contains("PAYMENT")
            || text.contains("PAIEMENT") || text.contains("VIREMENT");
    }

    /**
     * Whether two legs describe one movement. Every clause is one of the rules above, including
     * the transfer-text test.
     */
    private static boolean pairs(Transaction left, Transaction right) {
        return isDirectional(right)
            && looksLikeTransfer(left) && looksLikeTransfer(right)
            && left.getType() != right.getType()
            && !left.getAccountId().equals(right.getAccountId())
            && left.getHouseholdId().equals(right.getHouseholdId())
            && left.getCurrency() == right.getCurrency()
            && left.getAmount().compareTo(right.getAmount()) == 0
            && Math.abs(ChronoUnit.DAYS.between(left.getDate(), right.getDate())) <= WINDOW_DAYS;
    }

    /** Only a DEBIT or a CREDIT states a direction; everything else is left to a member. */
    private static boolean isDirectional(Transaction transaction) {
        return transaction.getType() == TransactionType.DEBIT
            || transaction.getType() == TransactionType.CREDIT;
    }
}
