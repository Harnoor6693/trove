package finance.trove.notifications.digest;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.UserClock;
import finance.trove.notifications.send.NotificationContent;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns one member's week into the digest they receive
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8; gap P2-15; findings D-N1 and F1).
 *
 * <h2>What the source did, and why none of it survives</h2>
 * {@code WeeklyDigestScheduler} fetched transactions from the ingestion service over HTTP with
 * three independent mistakes in one call (lines 55-60): the header {@code X-Trove-User-Id} where
 * the target required {@code X-User-Id}, the query parameters {@code user_id}/{@code date_from}/
 * {@code date_to} where the target read {@code from}/{@code to}, and an expected object
 * {@code {"transactions":[…]}} where the target returned a bare array. Any one of the three breaks
 * it; all three were present. The per-user {@code try/catch} then logged and counted a "skip", so
 * the job ran every Monday and reported success with {@code sent=0} for as long as it existed.
 *
 * <p>All of it evaporates because the read is now an in-process call to
 * {@link TransactionQueryService#findSpendableInRange}, which already excludes transfer legs. The
 * source's {@code WeeklyDigestSchedulerTest} is deliberately NOT ported: its WireMock stubs pinned
 * the wrong contract as correct, so it passed against the broken call.
 *
 * <h2>Whose week, and in whose money</h2>
 * The trigger is UTC (§5 item 6); the CONTENT is per recipient. "This week" is the last complete
 * Monday-to-Sunday week in the RECIPIENT'S own timezone, from {@link UserClock} — a Vancouver
 * member and a Toronto member woken by the same UTC instant get different boundaries, which is
 * gap P2-15 stated as a behaviour. The figures are in the currencies the ledger actually holds,
 * with the recipient's own {@code users.currency} first; the source hardcoded a single
 * currency code into the template variables (finding F1).
 */
@Component
public class WeeklyDigestBuilder {

    private final TransactionQueryService transactions;
    private final UserClock clock;

    public WeeklyDigestBuilder(TransactionQueryService transactions, UserClock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The last complete Monday-to-Sunday week, as the recipient's own calendar reads it.
     *
     * <p>Complete, not current: a digest sent on Monday morning about the week that is four hours
     * old would be about nothing. Walking back from the recipient's own "today" rather than from
     * the container's is what makes a member west of UTC get their own Sunday rather than
     * yesterday's.
     */
    public LocalDate weekStartFor(UUID userId) {
        return clock.today(userId)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .minusWeeks(1);
    }

    /**
     * The ISO week that week belongs to, in the recipient's own zone —
     * {@code 2026-W36} — and the dedup key for {@code WEEKLY_DIGEST}.
     *
     * <p>The week-BASED year, not the calendar year: 1 January 2027 falls in ISO week 53 of 2026,
     * and keying that as {@code 2027-W53} would collide with a week that does not exist while
     * leaving the real one unclaimed.
     */
    public static String isoWeekOf(LocalDate weekStart) {
        return "%d-W%02d".formatted(weekStart.get(IsoFields.WEEK_BASED_YEAR),
            weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /**
     * One summary per currency the household's week actually held, the recipient's own currency
     * first and the rest by code so that two runs of the same week read the same way.
     *
     * @param householdId the ledger to read — shared, unlike the notification itself
     * @param preferred   the recipient's own {@code users.currency}
     * @param weekStart   the Monday, in the recipient's own zone
     */
    public List<WeeklyDigestSummary> summarise(UUID householdId, CurrencyCode preferred,
                                               LocalDate weekStart) {
        List<Transaction> rows = transactions.findSpendableInRange(householdId, weekStart,
            weekStart.plusDays(6));

        Map<CurrencyCode, BigDecimal> spent = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, BigDecimal> income = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, Integer> counts = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, Map<UUID, BigDecimal>> byCategory = new EnumMap<>(CurrencyCode.class);

        for (Transaction row : rows) {
            CurrencyCode currency = row.getCurrency();
            counts.merge(currency, 1, Integer::sum);
            switch (row.getType()) {
                case DEBIT -> {
                    spent.merge(currency, row.getAmount(), BigDecimal::add);
                    accumulateCategory(byCategory, currency, row.getCategoryId(), row.getAmount());
                }
                case REFUND -> {
                    spent.merge(currency, row.getAmount().negate(), BigDecimal::add);
                    accumulateCategory(byCategory, currency, row.getCategoryId(),
                        row.getAmount().negate());
                }
                case CREDIT -> income.merge(currency, row.getAmount(), BigDecimal::add);
                default -> {
                    // FEE, INTEREST and OTHER are real movements but are not a household's
                    // discretionary spending or its income, and TRANSFER cannot reach here at all
                    // — the query excludes paired legs. They are counted, not classified, which
                    // is the same rule DashboardService and EnvelopeSpendService apply.
                }
            }
        }

        List<WeeklyDigestSummary> summaries = new ArrayList<>();
        for (CurrencyCode currency : counts.keySet()) {
            Map.Entry<UUID, BigDecimal> top = topCategory(byCategory.get(currency));
            summaries.add(new WeeklyDigestSummary(currency,
                spent.getOrDefault(currency, BigDecimal.ZERO),
                income.getOrDefault(currency, BigDecimal.ZERO),
                counts.get(currency),
                top == null ? null : top.getKey(),
                top == null ? BigDecimal.ZERO : top.getValue()));
        }
        summaries.sort((left, right) -> {
            if (left.currency() == preferred) {
                return right.currency() == preferred ? 0 : -1;
            }
            if (right.currency() == preferred) {
                return 1;
            }
            return left.currency().name().compareTo(right.currency().name());
        });
        return List.copyOf(summaries);
    }

    /**
     * The digest itself.
     *
     * <p>A member whose household saw nothing still gets one, saying so. The alternative — send
     * nothing — is indistinguishable from the job being broken, which is precisely how the
     * source's failure went unnoticed for as long as it did.
     */
    public NotificationContent contentFor(LocalDate weekStart, CurrencyCode preferred,
                                          List<WeeklyDigestSummary> summaries) {
        LocalDate weekEnd = weekStart.plusDays(6);
        String subject = "Your week: " + weekStart + " to " + weekEnd;
        StringBuilder body = new StringBuilder();
        if (summaries.isEmpty()) {
            body.append("No transactions were recorded between ").append(weekStart)
                .append(" and ").append(weekEnd).append('.');
        } else {
            for (WeeklyDigestSummary summary : summaries) {
                if (body.length() > 0) {
                    body.append(' ');
                }
                body.append(summary.currency().name()).append(": spent ")
                    .append(summary.spent().toPlainString()).append(", received ")
                    .append(summary.income().toPlainString()).append(", net ")
                    .append(summary.net().toPlainString()).append(" over ")
                    .append(summary.transactionCount())
                    .append(summary.transactionCount() == 1 ? " transaction." : " transactions.");
            }
        }
        Map<String, Object> model = new HashMap<>();
        model.put("weekStart", weekStart.toString());
        model.put("weekEnd", weekEnd.toString());
        model.put("primaryCurrency", preferred.name());
        model.put("summaries", summaries);
        return new NotificationContent(subject, body.toString(), "/dashboard",
            isoWeekOf(weekStart), "weekly-digest", model);
    }

    private static void accumulateCategory(Map<CurrencyCode, Map<UUID, BigDecimal>> byCategory,
                                           CurrencyCode currency, UUID categoryId,
                                           BigDecimal amount) {
        if (categoryId == null) {
            return;
        }
        byCategory.computeIfAbsent(currency, key -> new HashMap<>())
            .merge(categoryId, amount, BigDecimal::add);
    }

    private static Map.Entry<UUID, BigDecimal> topCategory(Map<UUID, BigDecimal> totals) {
        if (totals == null || totals.isEmpty()) {
            return null;
        }
        Map.Entry<UUID, BigDecimal> best = null;
        for (Map.Entry<UUID, BigDecimal> candidate : totals.entrySet()) {
            if (candidate.getValue().signum() <= 0) {
                continue;
            }
            if (best == null || candidate.getValue().compareTo(best.getValue()) > 0) {
                best = candidate;
            }
        }
        return best;
    }
}
