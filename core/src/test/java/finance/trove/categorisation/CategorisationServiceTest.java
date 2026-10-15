package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rule matching precedence, unit-tested against mocked repositories (collapse step 5's scope).
 *
 * <p>This class proves the SERVICE picks the right tier in the right order; it deliberately does
 * NOT prove {@link MerchantCategoryMapRepository#findPrefixMatches}'s own SQL is correct — a
 * mocked repository returning a canned list is exactly the shape the old service's
 * {@code prefixDbMatchReturnsCategoryWithoutCallingAi} test had, and
 * {@code docs/recovery/inventory/categorisation-service.md} §9/§10 names precisely why that shape
 * cannot catch a query-direction bug: it stubs the outcome, never the query. The real query is
 * proved by {@code CategorisationRuleIT}, which runs Flyway-migrated Postgres and real household
 * rows through {@link CategorisationService#categorise} end to end.
 */
class CategorisationServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    private CategoryRepository categories;
    private MerchantCategoryMapRepository mappings;
    private CommunityMerchantRegistry communityRegistry;
    private AiCategorisationTier aiTier;
    private CategorisationService service;

    @BeforeEach
    void setUp() {
        categories = mock(CategoryRepository.class);
        mappings = mock(MerchantCategoryMapRepository.class);
        communityRegistry = mock(CommunityMerchantRegistry.class);
        // Collapse step 17's tier. Every case in this class calls the five-argument overload,
        // which names no transaction owner and therefore must never reach the tier at all — the
        // mock is left unstubbed on purpose, so a call would fail rather than quietly answer.
        aiTier = mock(AiCategorisationTier.class);
        service = new CategorisationService(categories, mappings, communityRegistry, aiTier);

        when(mappings.findByHouseholdIdAndNormalisedMerchantAndMatchType(any(), anyString(),
            eq(MatchType.EXACT))).thenReturn(Optional.empty());
        when(mappings.findPrefixMatches(any(), anyString())).thenReturn(List.of());
        when(communityRegistry.lookup(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("an EXACT household rule wins over everything else")
    void exactRuleWinsFirst() {
        UUID categoryId = UUID.randomUUID();
        MerchantCategoryMap exact = mappingRow(categoryId, MatchType.EXACT, MappingSource.RULE,
            new BigDecimal("0.950"));
        when(mappings.findByHouseholdIdAndNormalisedMerchantAndMatchType(HOUSEHOLD, "STARBUCKS",
            MatchType.EXACT)).thenReturn(Optional.of(exact));
        when(categories.findVisibleById(categoryId, HOUSEHOLD))
            .thenReturn(Optional.of(category(categoryId, HOUSEHOLD, "Coffee Shops")));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, "Starbucks #99", null, null, null);

        assertThat(result.categoryId()).isEqualTo(categoryId);
        assertThat(result.source()).isEqualTo(MappingSource.RULE);
        assertThat(result.confidence()).isEqualByComparingTo("0.950");
        // The EXACT tier answered; nothing downstream was ever consulted.
        verify(mappings, never()).findPrefixMatches(any(), anyString());
        verify(communityRegistry, never()).lookup(anyString());
    }

    @Test
    @DisplayName("a PREFIX rule wins when no EXACT rule matches")
    void prefixWinsOverCommunityAndFallback() {
        UUID categoryId = UUID.randomUUID();
        MerchantCategoryMap prefix = mappingRow(categoryId, MatchType.PREFIX, MappingSource.USER,
            new BigDecimal("0.800"));
        when(mappings.findPrefixMatches(HOUSEHOLD, "STARBUCKS DOWNTOWN"))
            .thenReturn(List.of(prefix));
        when(categories.findVisibleById(categoryId, HOUSEHOLD))
            .thenReturn(Optional.of(category(categoryId, HOUSEHOLD, "Coffee Shops")));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, "Starbucks Downtown", null, null, null);

        assertThat(result.categoryId()).isEqualTo(categoryId);
        assertThat(result.source()).isEqualTo(MappingSource.USER);
        verify(communityRegistry, never()).lookup(anyString());
    }

    @Test
    @DisplayName("the community registry wins when the household has no rule at all")
    void communityRegistryWinsWhenNoRuleMatches() {
        UUID categoryId = UUID.randomUUID();
        when(communityRegistry.lookup("STARBUCKS"))
            .thenReturn(Optional.of(new CommunityMerchantRegistry.Entry("Coffee Shops",
                new BigDecimal("0.99"))));
        when(categories.findByHouseholdIdAndName(null, "Coffee Shops"))
            .thenReturn(Optional.of(category(categoryId, null, "Coffee Shops")));

        CategorisationResult result = service.categorise(HOUSEHOLD, "Starbucks", null, null, null);

        assertThat(result.categoryId()).isEqualTo(categoryId);
        assertThat(result.categoryName()).isEqualTo("Coffee Shops");
        assertThat(result.confidence()).isEqualByComparingTo("0.99");
        assertThat(result.source())
            .as("a community-registry match is not a persisted household row and cannot claim "
                + "RULE, USER or AI")
            .isNull();
    }

    @Test
    @DisplayName("Uncategorised is the guaranteed fallback when nothing else matches")
    void fallsBackToUncategorised() {
        UUID uncategorisedId = UUID.randomUUID();
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.of(category(uncategorisedId, null, "Uncategorised")));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, "Some Merchant Nobody Has A Rule For", null, null, null);

        assertThat(result.categoryId()).isEqualTo(uncategorisedId);
        assertThat(result.confidence()).isEqualByComparingTo("0.000");
        assertThat(result.source()).isNull();
        verifyNoInteractions(aiTier);
    }

    @Test
    @DisplayName("a missing Uncategorised seed row is a startup-configuration error, not a silent 200")
    void missingUncategorisedIsFatal() {
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.empty());

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.categorise(HOUSEHOLD, "Anything", null, null, null))
            .isInstanceOf(IllegalStateException.class);
    }

    private static MerchantCategoryMap mappingRow(UUID categoryId, MatchType matchType,
                                                   MappingSource source, BigDecimal confidence) {
        return new MerchantCategoryMap(HOUSEHOLD, "IGNORED", matchType, categoryId, confidence,
            source, 0, UUID.randomUUID(), java.time.Instant.now());
    }

    private static Category category(UUID id, UUID householdId, String name) {
        Category category = new Category(householdId, "slug-" + id, name, null, null, null,
            java.time.Instant.now());
        setId(category, id);
        return category;
    }

    /** Test-only: the entities generate their id via JPA, so a plain unit test sets it by hand. */
    private static void setId(Category category, UUID id) {
        try {
            var field = Category.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
