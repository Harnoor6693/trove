package finance.trove.budgets.envelope;

import finance.trove.budgets.envelope.dto.EnvelopeRequest;
import finance.trove.budgets.envelope.dto.EnvelopeResponse;
import finance.trove.budgets.envelope.dto.EnvelopeUpdateRequest;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodService;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserClock;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over the envelopes of one budget period
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6, §6 D1).
 *
 * <p>Every method resolves the period through {@link BudgetPeriodService#get} first, so a period
 * id from another household is a 404 before an envelope id is ever looked at, and an envelope id
 * paired with the wrong period is a 404 too — the period is part of the lookup rather than a check
 * afterwards (§9 risk 2).
 *
 * <p>The category name is <strong>joined on read</strong> rather than stored: the pre-collapse
 * table carried a copy taken at creation time, so renaming a category left every envelope showing
 * the old label for ever. The join is only possible because the collapse put both tables in one
 * database, and V24 backs it with a real foreign key.
 */
@Service
public class EnvelopeService {

    private final EnvelopeRepository envelopes;
    private final BudgetPeriodService periods;
    private final CategoryRepository categories;
    private final EnvelopeSpendService spend;
    private final UserClock clock;

    public EnvelopeService(EnvelopeRepository envelopes, BudgetPeriodService periods,
                           CategoryRepository categories, EnvelopeSpendService spend,
                           UserClock clock) {
        this.envelopes = envelopes;
        this.periods = periods;
        this.categories = categories;
        this.spend = spend;
        this.clock = clock;
    }

    /** Every envelope of one period, each with its live health. */
    @Transactional(readOnly = true)
    public List<EnvelopeResponse> list(UUID householdId, UUID userId, UUID periodId) {
        BudgetPeriod period = periods.get(householdId, periodId);
        List<Envelope> rows =
            envelopes.findAllByHouseholdIdAndPeriodIdOrderByCreatedAtAsc(householdId, periodId);
        if (rows.isEmpty()) {
            return List.of();
        }
        // One lookup of the household's visible categories for the whole list rather than one per
        // envelope: the set is bounded (51 system rows plus the household's own) and a per-row
        // join would be the classic N+1 on the busiest read of this surface.
        Map<UUID, String> names = visibleCategoryNames(householdId);
        LocalDate today = clock.today(userId);
        return rows.stream().map(row -> toResponse(row, period, names, today)).toList();
    }

    /** One envelope of one period, with its live health. */
    @Transactional(readOnly = true)
    public EnvelopeResponse get(UUID householdId, UUID userId, UUID periodId, UUID envelopeId) {
        BudgetPeriod period = periods.get(householdId, periodId);
        Envelope envelope = require(householdId, periodId, envelopeId);
        return toResponse(envelope, period, visibleCategoryNames(householdId),
            clock.today(userId));
    }

    /**
     * Budgets a category inside a period.
     *
     * <p>Three refusals, each with the status the caller can act on: a category this household
     * cannot use is 400 (the id arrived as a field value, so {@code fieldErrors} can name it and
     * a 404 would be ambiguous with the period's own id being unknown); a category the period
     * already budgets is 409, because {@code UNIQUE (period_id, category_id)} means the second
     * envelope would be rewritten to the same ledger total as the first and the period would
     * double-count itself; and a {@code ZERO_BASED} period whose allocations would stop summing to
     * its income is 400.
     *
     * <p>The new envelope's {@code spent} is computed immediately from the ledger. Budgeting a
     * category halfway through a month and being shown zero — when the household has already spent
     * three hundred dollars in it — is exactly the reading finding D1 produced, and it would be
     * just as wrong arriving from a cold start as from a missing writer.
     */
    @Transactional
    public EnvelopeResponse create(UUID householdId, UUID userId, UUID periodId,
                                   EnvelopeRequest request) {
        BudgetPeriod period = periods.get(householdId, periodId);
        Category category = requireVisibleCategory(householdId, request.categoryId());

        if (envelopes.existsByPeriodIdAndCategoryId(periodId, category.getId())) {
            throw new ConflictException(
                "This budget period already has an envelope for that category");
        }

        // The period's currency, not a literal and not the member's: an envelope lives inside a
        // period, so the period is "the row" its currency defaults from (finding F1).
        CurrencyCode currency = request.currency() != null ? request.currency()
            : period.getCurrency();
        BigDecimal allocated = currency.normalise(request.allocated());

        BudgetPeriodService.requireZeroBasedInvariant(period,
            envelopes.sumAllocatedByPeriodId(periodId).add(allocated));

        Envelope saved = envelopes.save(new Envelope(householdId, periodId, category.getId(),
            allocated, currency, clock.now()));
        spend.recalculate(householdId, userId, category.getId(), period.month());

        return toResponse(saved, period, Map.of(category.getId(), category.getName()),
            clock.today(userId));
    }

    /**
     * Replaces an envelope's allocation.
     *
     * <p>{@code spent} is not touched: it is the ledger's answer, and nothing a member types into
     * a budget form can change what they actually spent.
     */
    @Transactional
    public EnvelopeResponse update(UUID householdId, UUID userId, UUID periodId, UUID envelopeId,
                                   EnvelopeUpdateRequest request) {
        BudgetPeriod period = periods.get(householdId, periodId);
        Envelope envelope = require(householdId, periodId, envelopeId);

        BigDecimal allocated = envelope.getCurrency().normalise(request.allocated());
        BudgetPeriodService.requireZeroBasedInvariant(period,
            envelopes.sumAllocatedByPeriodIdExcluding(periodId, envelopeId).add(allocated));

        envelope.setAllocated(allocated);
        Envelope saved = envelopes.save(envelope);
        return toResponse(saved, period, visibleCategoryNames(householdId), clock.today(userId));
    }

    /**
     * Removes an envelope.
     *
     * <p>The transactions it aggregated are untouched — it was only ever a view of them — and so
     * is the category, whose own row this envelope's foreign key was merely protecting.
     */
    @Transactional
    public void delete(UUID householdId, UUID periodId, UUID envelopeId) {
        periods.get(householdId, periodId);
        Envelope envelope = require(householdId, periodId, envelopeId);
        envelopes.deleteByIdAndHouseholdId(envelope.getId(), householdId);
    }

    /** The envelope, resolved by household AND period, or 404. */
    private Envelope require(UUID householdId, UUID periodId, UUID envelopeId) {
        return envelopes.findByIdAndHouseholdIdAndPeriodId(envelopeId, householdId, periodId)
            .orElseThrow(() -> ResourceNotFoundException.of("Envelope", envelopeId));
    }

    /**
     * A category this household may actually budget — a system row or one of its own.
     *
     * <p>400 rather than 404, for the reason {@code TransactionService.requireVisibleCategory}
     * gives: the id arrived in a body as a field value.
     */
    private Category requireVisibleCategory(UUID householdId, UUID categoryId) {
        return categories.findVisibleById(categoryId, householdId)
            .orElseThrow(() -> new ValidationException(
                "categoryId does not name a category this household can use"));
    }

    private Map<UUID, String> visibleCategoryNames(UUID householdId) {
        return categories.findAllVisible(householdId).stream()
            .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a,
                HashMap::new));
    }

    private static EnvelopeResponse toResponse(Envelope envelope, BudgetPeriod period,
                                               Map<UUID, String> categoryNames, LocalDate today) {
        EnvelopeHealth health =
            EnvelopeHealthCalculator.calculate(envelope, period.month(), today);
        // The FK is ON DELETE RESTRICT, so the name is always resolvable; the fallback exists so
        // that a category made invisible some future way degrades to a readable list rather than
        // a NullPointerException on a screen the member is looking at.
        String name = categoryNames.getOrDefault(envelope.getCategoryId(), "Unknown category");
        return EnvelopeResponse.of(envelope, name, health);
    }
}
