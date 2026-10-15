package finance.trove.taxexport;

import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.taxexport.dto.TaxLineMappingRequest;
import finance.trove.taxexport.dto.TaxLineMappingResponse;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The category-to-tax-line map, resolved for one member
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §6 "Tax export").
 *
 * <p><strong>This is the part of step 15 other steps consume.</strong> Collapse step 18's full
 * data export reuses the same builder and therefore the same resolution; nothing else in the tree
 * decides what line a category belongs on.
 *
 * <h2>The precedence rule, stated once</h2>
 * For a category, in a jurisdiction: the caller's OWN row if they have written one, else the
 * install-wide seed row, else <strong>no line at all</strong> — {@link TaxLine#none()}, whose two
 * strings are empty. The third branch is the important one. A category with no line is exported
 * with empty {@code taxLineCode} and {@code taxLineLabel} and <em>is still a row in the file</em>.
 * A transaction that vanished from a member's tax export because nobody had written a label for
 * its category would be the worst failure this step can have, and it would be silent.
 *
 * <p>The precedence is resolved in the database, in one statement
 * ({@link TaxLineMappingRepository#findEffectiveMap}), not by reading two tiers and merging them
 * in Java.
 *
 * <h2>Household mates do not share these</h2>
 * A filing is individual (§1.5, §6), so an override belongs to the member who wrote it and is
 * invisible to everybody else in their household — the opposite of the shared ledger the rows
 * themselves come from.
 */
@Service
public class TaxLineMappingService {

    private final TaxLineMappingRepository mappings;
    private final CategoryRepository categories;
    private final UserRepository users;

    public TaxLineMappingService(TaxLineMappingRepository mappings, CategoryRepository categories,
                                 UserRepository users) {
        this.mappings = mappings;
        this.categories = categories;
        this.users = users;
    }

    /**
     * The effective map for one member and one jurisdiction, keyed by category id.
     *
     * <p>The returned map contains only the categories that some row maps. Callers ask it for a
     * category and take {@link TaxLine#none()} when it answers nothing — see
     * {@link #lineFor(Map, UUID)} — rather than treating a missing key as a reason to drop a row.
     */
    @Transactional(readOnly = true)
    public Map<UUID, TaxLine> resolve(UUID userId, Jurisdiction jurisdiction) {
        Map<UUID, TaxLine> resolved = new HashMap<>();
        for (TaxLineMapping mapping : mappings.findEffectiveMap(jurisdiction, userId)) {
            resolved.put(mapping.getCategoryId(), mapping.toTaxLine());
        }
        return resolved;
    }

    /**
     * The line for one category out of a resolved map — {@link TaxLine#none()} when the map has
     * nothing for it, and for an uncategorised transaction, whose {@code categoryId} is null.
     *
     * <p>It exists so that every caller answers "no line" the same way and no caller is left to
     * decide what a missing key means.
     */
    public static TaxLine lineFor(Map<UUID, TaxLine> resolved, UUID categoryId) {
        if (categoryId == null) {
            return TaxLine.none();
        }
        return resolved.getOrDefault(categoryId, TaxLine.none());
    }

    /**
     * Every category this household can see, each with the caller's effective line, ordered by
     * category name — the body of {@code GET /v1/tax-export/lines}.
     *
     * <p>Driven by the CATEGORY list rather than by the mapping rows, which is what makes a
     * category with no mapping appear with empty cells instead of being absent.
     */
    @Transactional(readOnly = true)
    public List<TaxLineMappingResponse> listLines(UUID userId, UUID householdId,
                                                  Jurisdiction jurisdiction) {
        Map<UUID, TaxLine> resolved = resolve(userId, jurisdiction);
        return categories.findAllVisible(householdId).stream()
            .map(category -> TaxLineMappingResponse.of(category, jurisdiction,
                lineFor(resolved, category.getId())))
            .toList();
    }

    /**
     * Creates or replaces the caller's own row for one category — {@code PUT
     * /v1/tax-export/lines/{categoryId}}.
     *
     * <p>The category is resolved through {@link CategoryRepository#findVisibleById} first, so a
     * category belonging to another household is a 404 before anything is written; the mapping
     * table's own FK would otherwise accept it.
     *
     * <p>The write itself is ONE statement — {@code INSERT … ON CONFLICT ON CONSTRAINT
     * tax_line_mapping_scope_unique DO UPDATE}
     * ({@link TaxLineMappingRepository#upsertOverride}) — and deliberately not a JPA
     * find-then-save. Two concurrent PUTs from the same member for the same category would both
     * read "no row yet" and both insert, and the loser would die on the unique index; the
     * constraint is {@code NULLS NOT DISTINCT} precisely so that {@code ON CONFLICT} can target
     * it and make the second writer an update instead (§2.9, §3, §4.12 item 2). The seed row
     * (user id null) is a different key value, so an override never overwrites it.
     *
     * <p>The response is built from the values just written rather than from a read-back: the
     * statement above stores exactly these three, and {@code overridden} is true by construction
     * because the row the member just wrote is theirs. That the write is durable and wins over
     * the seed is proved end to end by {@code TaxLineMappingIT}, against a real Postgres.
     */
    @Transactional
    public TaxLineMappingResponse upsert(UUID userId, UUID householdId, UUID categoryId,
                                         TaxLineMappingRequest request) {
        Category category = categories.findVisibleById(categoryId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Category", categoryId));

        String lineCode = request.lineCode().strip();
        String lineLabel = request.lineLabel().strip();
        mappings.upsertOverride(request.jurisdiction(), categoryId, userId, lineCode, lineLabel,
            request.deductible());

        return TaxLineMappingResponse.of(category, request.jurisdiction(),
            new TaxLine(lineCode, lineLabel, request.deductible(), true));
    }

    /**
     * The jurisdiction to resolve against when the caller did not name one: their own
     * {@code users.country}.
     *
     * <p>The same value is stamped onto every run they start, so the lines a member sees on the
     * settings screen are the lines their next export will carry.
     */
    @Transactional(readOnly = true)
    public Jurisdiction defaultJurisdiction(UUID userId, UUID householdId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .map(User::getCountry)
            .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }
}
