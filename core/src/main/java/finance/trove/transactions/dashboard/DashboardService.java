package finance.trove.transactions.dashboard;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.InstantRange;
import finance.trove.common.time.UserClock;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.transactions.dashboard.dto.DashboardCurrencyTotals;
import finance.trove.transactions.dashboard.dto.DashboardTodayResponse;
import finance.trove.users.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Today and this month for the caller's household, with every boundary computed in the caller's
 * own timezone ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4; gap P2-15).
 *
 * <h2>What was wrong before</h2>
 * The pre-collapse controller called {@code LocalDate.now()} with no zone, twice, and read the
 * identity from a client-supplied {@code X-User-Id} header, answering zeros when the header was
 * absent instead of refusing. The container is UTC by policy (§1.6), so a Vancouver member's
 * evening purchase landed on tomorrow's dashboard and their month rolled over at 16:00 on the
 * 31st. Every boundary here comes from {@link UserClock}, which reads {@code users.timezone}
 * through the {@code @Primary} table-backed provider.
 *
 * <h2>What counts as spending</h2>
 * {@code DEBIT} less {@code REFUND}, and income is {@code CREDIT} — deliberately the same rule
 * §6 D1 specifies for a budget envelope's {@code spent}. Two screens that disagree about whether
 * a refund reduces spending are worse than either answer on its own, so the rule is written once
 * and stated here. {@code FEE}, {@code INTEREST} and {@code OTHER} are neither: they are real
 * movements but they are not a household's discretionary spending, and folding them into it would
 * make a budget the member cannot reconcile against their own statement.
 *
 * <h2>Transfer legs are excluded</h2>
 * By the query, not by a filter here — {@link TransactionQueryService#findSpendableInRange} is
 * the seam every aggregate in the product reads through precisely so that the exclusion cannot be
 * forgotten in one of them (gap P2-14).
 */
@Service
public class DashboardService {

    private final TransactionQueryService queries;
    private final UserRepository users;
    private final UserClock clock;

    public DashboardService(TransactionQueryService queries, UserRepository users,
                            UserClock clock) {
        this.queries = queries;
        this.users = users;
        this.clock = clock;
    }

    /** The caller's dashboard. */
    @Transactional(readOnly = true)
    public DashboardTodayResponse today(UUID householdId, UUID userId) {
        CurrencyCode headlineCurrency = users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId))
            .getCurrency();

        // todayRange() is the boundary pair every per-user date decision in the application goes
        // through; the calendar date is read back out of it rather than from a second clock call,
        // so the day named in the response is provably the same day the figures were bounded by.
        InstantRange todayRange = clock.todayRange(userId);
        LocalDate today = clock.dateOf(userId, todayRange.start());
        YearMonth month = clock.monthOf(userId, todayRange.start());
        LocalDate monthStart = month.atDay(1);

        List<Transaction> rows = queries.findSpendableInRange(householdId, monthStart, today);

        Map<CurrencyCode, Totals> totals = new EnumMap<>(CurrencyCode.class);
        for (Transaction row : rows) {
            totals.computeIfAbsent(row.getCurrency(), key -> new Totals()).add(row, today);
        }

        List<DashboardCurrencyTotals> byCurrency = new ArrayList<>();
        totals.forEach((currency, sums) -> byCurrency.add(sums.toDto(currency)));
        byCurrency.sort(Comparator.comparing(entry -> entry.currency().name()));

        DashboardCurrencyTotals headline = byCurrency.stream()
            .filter(entry -> entry.currency() == headlineCurrency)
            .findFirst()
            .orElseGet(() -> DashboardCurrencyTotals.zero(headlineCurrency));

        return DashboardTodayResponse.of(today, clock.zoneOf(userId).getId(), headline,
            byCurrency);
    }

    /** One currency's running sums. Mutable and package-private — it never leaves this class. */
    private static final class Totals {

        private BigDecimal spentToday = BigDecimal.ZERO;
        private BigDecimal spentThisMonth = BigDecimal.ZERO;
        private BigDecimal incomeThisMonth = BigDecimal.ZERO;
        private int transactionsToday;

        void add(Transaction row, LocalDate today) {
            boolean isToday = today.equals(row.getDate());
            if (isToday) {
                transactionsToday++;
            }
            BigDecimal signedSpend = spendContribution(row);
            if (signedSpend != null) {
                spentThisMonth = spentThisMonth.add(signedSpend);
                if (isToday) {
                    spentToday = spentToday.add(signedSpend);
                }
            } else if (row.getType() == TransactionType.CREDIT) {
                incomeThisMonth = incomeThisMonth.add(row.getAmount());
            }
        }

        /** {@code DEBIT} adds, {@code REFUND} subtracts, everything else is not spending. */
        private static BigDecimal spendContribution(Transaction row) {
            return switch (row.getType()) {
                case DEBIT -> row.getAmount();
                case REFUND -> row.getAmount().negate();
                case CREDIT, TRANSFER, FEE, INTEREST, OTHER -> null;
            };
        }

        DashboardCurrencyTotals toDto(CurrencyCode currency) {
            return new DashboardCurrencyTotals(currency, currency.normalise(spentToday),
                transactionsToday, currency.normalise(spentThisMonth),
                currency.normalise(incomeThisMonth),
                currency.normalise(incomeThisMonth.subtract(spentThisMonth)));
        }
    }
}
