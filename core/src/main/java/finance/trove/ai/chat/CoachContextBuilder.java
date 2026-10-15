package finance.trove.ai.chat;

import finance.trove.app.AsyncConfig;
import finance.trove.budgets.envelope.EnvelopeService;
import finance.trove.budgets.envelope.dto.EnvelopeResponse;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodService;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.networth.NetWorthRange;
import finance.trove.networth.NetWorthSeriesService;
import finance.trove.networth.dto.NetWorthPoint;
import finance.trove.networth.dto.NetWorthSeries;
import finance.trove.subscriptions.dto.SubscriptionCurrencyTotals;
import finance.trove.subscriptions.dto.SubscriptionSummaryResponse;
import finance.trove.subscriptions.subscription.SubscriptionService;
import finance.trove.transactions.dashboard.DashboardService;
import finance.trove.transactions.dashboard.dto.DashboardCurrencyTotals;
import finance.trove.transactions.dashboard.dto.DashboardTodayResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Builds the ledger context block the coach's prompt carries
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6; inventory model-manager D1, F1, §12).
 *
 * <h2>Four in-process reads, not four HTTP calls</h2>
 * The source fetched these from four peer services over HTTP and got every one of them wrong:
 * it sent {@code X-Trove-User-Id} where ingestion read {@code X-User-Id} (so the dashboard section
 * was silently zeroed), and budget-service and subscription-detector accepted no user-id header at
 * all and required a forwarded JWT that was never sent (so those three calls 401'd
 * unconditionally). The coach's "personalised context" was therefore empty for every user on every
 * install, from the day it was written, and its own test never asserted an outbound header name so
 * nothing caught it. Collapsing the services into one JVM deletes the whole class of bug: these
 * are four method calls on four merged services, scoped by the caller's own household and user id.
 *
 * <h2>Bounded pool, one deadline</h2>
 * The fan-out runs on {@link AsyncConfig#COACH_EXECUTOR}, not on {@code ForkJoinPool.commonPool()}
 * as the source did — a JVM-wide pool shared with every parallel stream in the application is not
 * somewhere to put four database queries a member is waiting on. The deadline is
 * {@link CoachProperties#contextDeadline}, §6's five seconds, and it applies to the whole
 * fan-out rather than per section: a member waiting for an answer does not care which query was
 * slow, and four five-second budgets is a twenty-second wait before the first token.
 *
 * <h2>A section that fails is said to be missing, never reported as zero</h2>
 * This is the one rule of this class. Inventory D1's real damage was not that the reads failed —
 * it is that the failure was rendered as {@code $0.00} and the coach then told members, with
 * complete confidence, that they had spent nothing. Every section here is filled with
 * {@link #UNAVAILABLE} when its read fails, overruns, or is never run at all because the pool
 * rejected it, the system prompt tells the model to say so rather than guess, and no code path
 * substitutes a zero for an unknown.
 *
 * <h2>No transaction of its own</h2>
 * The four section methods carry no {@code @Transactional}: they run on a pool thread, are called
 * from inside this class, and a self-invoked annotation is not proxied — writing one would look
 * like a guarantee and be none. Each merged service opens its own read-only transaction, which is
 * the one that actually runs, and every value that leaves them is a DTO or a simple column.
 *
 * <h2>The minimum, and per currency</h2>
 * Aggregates and category names only: no transaction row, no merchant name, no account number, no
 * access URL, no API key, no receipt and no other member's identity ever reaches the prompt.
 * Amounts are {@code BigDecimal} in the currency they were recorded in, one block per currency the
 * household actually holds — never blended, because no exchange rate exists anywhere in Trove
 * (findings F1/F2). The member's own {@code users.currency} is named as theirs; it is never a
 * literal, which is what {@code SourceConventionTest} enforces and what the source's hard-coded
 * hard-coded Canadian-dollar fallback got wrong.
 */
@Component
public class CoachContextBuilder {

    /**
     * What a section says when its read failed or overran. Deliberately not a number: see the
     * class javadoc.
     */
    public static final String UNAVAILABLE = "(unavailable — this data could not be read)";

    private static final String NO_BUDGET = "(no budget period for this month)";
    private static final String NO_SNAPSHOT = "(no net-worth snapshot has been taken yet)";
    private static final String NO_SUBSCRIPTIONS = "(no active subscriptions)";
    private static final String INDENT = "  ";

    private static final Logger LOG = LoggerFactory.getLogger(CoachContextBuilder.class);

    private final DashboardService dashboard;
    private final BudgetPeriodService periods;
    private final EnvelopeService envelopes;
    private final NetWorthSeriesService netWorth;
    private final SubscriptionService subscriptions;
    private final CoachPromptsProperties prompts;
    private final CoachProperties properties;
    private final Executor executor;

    public CoachContextBuilder(DashboardService dashboard, BudgetPeriodService periods,
                               EnvelopeService envelopes, NetWorthSeriesService netWorth,
                               SubscriptionService subscriptions, CoachPromptsProperties prompts,
                               CoachProperties properties,
                               @Qualifier(AsyncConfig.COACH_EXECUTOR) Executor executor) {
        this.dashboard = dashboard;
        this.periods = periods;
        this.envelopes = envelopes;
        this.netWorth = netWorth;
        this.subscriptions = subscriptions;
        this.prompts = prompts;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * The filled context block for {@code audience}.
     *
     * <p>Never throws and never returns null: a total failure of every section produces a block
     * that says every section is unavailable, which is a truthful prompt. The four reads run
     * concurrently and the whole fan-out is bounded by
     * {@link CoachProperties#contextDeadline}.
     *
     * @param now the instant the block describes, so that a test and the weekly job can both pin
     *            it rather than racing the wall clock
     */
    public String build(CoachAudience audience, Instant now) {
        ZonedDateTime local = now.atZone(audience.zone());
        LocalDate today = local.toLocalDate();
        YearMonth month = YearMonth.from(local);

        CompletableFuture<String> monthSummary = section("month", () -> monthSummary(audience));
        CompletableFuture<String> budgetSummary = section("budget", () -> budgetSummary(audience));
        CompletableFuture<String> subscriptionSummary =
            section("subscriptions", () -> subscriptionSummary(audience));
        CompletableFuture<String> netWorthSummary =
            section("net worth", () -> netWorthSummary(audience));

        awaitAll(monthSummary, budgetSummary, subscriptionSummary, netWorthSummary);

        Map<String, String> values = Map.of(
            "display_name", audience.salutation(),
            "date", today.toString(),
            "timezone", audience.zone().getId(),
            "jurisdiction", audience.country().name(),
            "month", month.toString(),
            "month_summary", resolved(monthSummary),
            "budget_summary", resolved(budgetSummary),
            "subscription_summary", resolved(subscriptionSummary),
            "net_worth_summary", resolved(netWorthSummary));
        return fill(prompts.contextTemplate(), values);
    }

    /**
     * Substitutes {@code {{name}}} slots. A slot the map does not name is replaced with
     * {@link #UNAVAILABLE} rather than left in the prompt: a literal {@code {{budget_summary}}}
     * reaching a model is a template bug that would read to it as data.
     */
    static String fill(String template, Map<String, String> values) {
        StringBuilder filled = new StringBuilder(template.length() + 256);
        int cursor = 0;
        while (true) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                break;
            }
            filled.append(template, cursor, open);
            String key = template.substring(open + 2, close).trim();
            filled.append(values.getOrDefault(key, UNAVAILABLE));
            cursor = close + 2;
        }
        return filled.append(template.substring(cursor)).toString();
    }

    /**
     * Submits one section, and turns every way it can fail into {@link #UNAVAILABLE}.
     *
     * <p>There are two of those ways and they need two mechanisms, which is why the submission is
     * wrapped as well as the future. A read that throws or overruns completes the future
     * exceptionally and is caught by {@code exceptionally}. A pool that is saturated never runs
     * the read at all: {@code CompletableFuture.supplyAsync} calls {@code executor.execute} inline,
     * before it has a future to hand back, so {@code AbortPolicy}'s
     * {@link RejectedExecutionException} (Spring's {@code TaskRejectedException} is one) is thrown
     * on this thread and would sail straight past {@code exceptionally}, out of {@code build()} and
     * out of the member's question as an unhandled 500. That is precisely the case
     * {@link AsyncConfig#coachExecutor} chose {@code AbortPolicy} for, so it has to land where
     * every other section failure lands.
     */
    private CompletableFuture<String> section(String name, Supplier<String> read) {
        try {
            return CompletableFuture.supplyAsync(read, executor).exceptionally(failure -> {
                // swallow-ok: a context section that cannot be read is stated as unavailable
                // rather than failing the member's question. The alternative is a 500 on a coach
                // answer because one aggregate query was slow, and the alternative the source
                // chose - a zero - is worse than both (inventory D1).
                LOG.warn("Coach context section '{}' could not be read; it will be reported as "
                    + "unavailable", name, failure);
                return UNAVAILABLE;
            });
        } catch (RejectedExecutionException e) {
            // swallow-ok: the coach pool is full, which is a load signal and not a fault of this
            // question. One block of one answer degrades to "unavailable" - the whole reason the
            // pool aborts rather than running the work on the caller's streaming thread.
            LOG.warn("Coach context section '{}' was rejected by the coach pool; it will be "
                + "reported as unavailable", name, e);
            return CompletableFuture.completedFuture(UNAVAILABLE);
        }
    }

    private void awaitAll(CompletableFuture<?>... sections) {
        Duration deadline = properties.contextDeadline();
        try {
            CompletableFuture.allOf(sections).get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // swallow-ok: the deadline expiring is the designed outcome, not an error. Whatever
            // finished is used and whatever did not is reported as unavailable by resolved().
            LOG.warn("Coach context fan-out did not finish inside {}; the sections that did not "
                + "complete will be reported as unavailable", deadline);
        } catch (java.util.concurrent.ExecutionException e) {
            // swallow-ok: every section already catches its own failure in section(), so this can
            // only be a failure of allOf itself; the same "report it as unavailable" rule applies.
            LOG.warn("Coach context fan-out failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while building the coach context; every section will be "
                + "reported as unavailable");
        }
    }

    /** What a section produced, or {@link #UNAVAILABLE} if it has not produced anything yet. */
    private static String resolved(CompletableFuture<String> section) {
        return section.getNow(UNAVAILABLE);
    }

    String monthSummary(CoachAudience audience) {
        DashboardTodayResponse today =
            dashboard.today(audience.householdId(), audience.userId());
        if (today.byCurrency().isEmpty()) {
            return INDENT + "(no transactions recorded)";
        }
        StringBuilder text = new StringBuilder();
        for (DashboardCurrencyTotals totals : today.byCurrency()) {
            text.append(INDENT).append(totals.currency().name())
                .append(own(audience, totals.currency().name()))
                .append(": spent ").append(amount(totals.spentThisMonth()))
                .append(", income ").append(amount(totals.incomeThisMonth()))
                .append(", net ").append(amount(totals.netThisMonth()))
                .append(" (").append(totals.transactionsToday())
                .append(" transaction(s) today, ").append(amount(totals.spentToday()))
                .append(" spent today)\n");
        }
        return text.toString().stripTrailing();
    }

    String budgetSummary(CoachAudience audience) {
        BudgetPeriod period;
        try {
            period = periods.current(audience.householdId(), audience.userId());
        } catch (ResourceNotFoundException e) {
            // swallow-ok: "this household has not opened a budget for this month" is a fact about
            // the ledger, not a failure of the read, and the prompt should say the true thing
            // rather than "unavailable".
            return INDENT + NO_BUDGET;
        }
        List<EnvelopeResponse> lines =
            envelopes.list(audience.householdId(), audience.userId(), period.getId());
        StringBuilder text = new StringBuilder();
        text.append(INDENT).append("period ").append(period.month())
            .append(", type ").append(period.getBudgetType())
            .append(", planned income ").append(amount(period.getTotalIncome()))
            .append(' ').append(period.getCurrency().name()).append('\n');
        if (lines.isEmpty()) {
            text.append(INDENT).append("(no envelopes allocated)");
            return text.toString();
        }
        for (EnvelopeResponse line : lines) {
            text.append(INDENT).append(INDENT).append(line.categoryName())
                .append(": allocated ").append(amount(line.allocated()))
                .append(", spent ").append(amount(line.spent()))
                .append(", rollover ").append(amount(line.rolloverAmount()))
                .append(' ').append(line.currency().name())
                .append(" [").append(line.health()).append("]\n");
        }
        return text.toString().stripTrailing();
    }

    String subscriptionSummary(CoachAudience audience) {
        SubscriptionSummaryResponse summary =
            subscriptions.summary(audience.householdId(), audience.userId());
        if (summary.byCurrency().isEmpty()) {
            return INDENT + NO_SUBSCRIPTIONS;
        }
        StringBuilder text = new StringBuilder();
        for (SubscriptionCurrencyTotals totals : summary.byCurrency()) {
            text.append(INDENT).append(totals.currency().name())
                .append(own(audience, totals.currency().name()))
                .append(": monthly run rate ").append(amount(totals.monthlyRunRate()))
                .append(", ").append(totals.activeCount()).append(" active, ")
                .append(totals.flaggedCount()).append(" flagged\n");
        }
        // next14Days is deliberately NOT included: its entries name individual merchants, and the
        // rule for this block is aggregates and category names. The run rate and the flag count
        // answer the questions the suggestions actually offer.
        return text.toString().stripTrailing();
    }

    String netWorthSummary(CoachAudience audience) {
        List<NetWorthSeries> series = netWorth
            .getSeries(audience.householdId(), audience.userId(), NetWorthRange.THREE_MONTHS)
            .series();
        List<String> rows = new ArrayList<>();
        for (NetWorthSeries oneCurrency : series) {
            List<NetWorthPoint> points = oneCurrency.points();
            if (points.isEmpty()) {
                continue;
            }
            NetWorthPoint latest = points.get(points.size() - 1);
            rows.add(INDENT + oneCurrency.currency().name()
                + own(audience, oneCurrency.currency().name())
                + ": assets " + amount(latest.totalAssets())
                + ", liabilities " + amount(latest.totalLiabilities())
                + ", net worth " + amount(latest.netWorth())
                + " (as of " + latest.snapshotDate() + ")");
        }
        return rows.isEmpty() ? INDENT + NO_SNAPSHOT : String.join("\n", rows);
    }

    /** Marks the member's own currency without ever naming a currency literal. */
    private static String own(CoachAudience audience, String currencyName) {
        return audience.currency().name().equals(currencyName) ? " (yours)" : "";
    }

    /**
     * A decimal string, never a float and never a locale-formatted one: the model is being shown
     * a figure to reason about, and {@code 1,234.50} in one locale is {@code 1.234,50} in another.
     */
    private static String amount(BigDecimal value) {
        return value == null ? UNAVAILABLE : value.toPlainString();
    }
}
