package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.dto.AiCompletion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the AI tier sits in the pipeline, proved against the <strong>gateway</strong> rather than
 * against a mocked tier — collapse step 17 scope item 1.
 *
 * <p>The assertion that matters in every case below is {@code verifyNoInteractions(gateway)}, and
 * it is the difference between a free feature and a bill. A rule tier that answered correctly but
 * asked the model first would pass every test written about its <em>result</em>: the category
 * would be right, the confidence would be right, and every ingested row would still cost an AI
 * call. So this class wires the real {@link CategorisationService} to the real
 * {@link DefaultAiCategorisationTier} and mocks only the seam that costs money.
 *
 * <p>The order it proves — household EXACT, household PREFIX, community registry, AI,
 * {@code Uncategorised} — is plan §6 D4's, and the reason the model is fourth rather than first is
 * that the three tiers above it are a member's own explicit statement or a free, instant, offline
 * lookup, while the model is the one that costs money, time and privacy.
 *
 * <p>It also proves the property an install with no AI depends on: the five-argument
 * {@code categorise} never touches the gateway <em>at all</em> — not even to ask whether AI is on
 * — so the rules dry-run and every pre-step-17 caller behave exactly as they did before.
 */
class CategorisationTierOrderTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();
    private static final UUID UNCATEGORISED = UUID.randomUUID();

    private AiGateway gateway;
    private AiHandle handle;
    private CategoryRepository categories;
    private MerchantCategoryMapRepository mappings;
    private CommunityMerchantRegistry communityRegistry;
    private MerchantMappingWriter mappingWriter;
    private CategorisationService service;

    @BeforeEach
    void setUp() {
        gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        categories = mock(CategoryRepository.class);
        mappings = mock(MerchantCategoryMapRepository.class);
        communityRegistry = mock(CommunityMerchantRegistry.class);
        mappingWriter = mock(MerchantMappingWriter.class);
        service = new CategorisationService(categories, mappings, communityRegistry,
            new DefaultAiCategorisationTier(gateway, categories, mappingWriter,
                new AiCategorisationProperties(null, null, null)));

        when(mappings.findByHouseholdIdAndNormalisedMerchantAndMatchType(any(), anyString(), any()))
            .thenReturn(Optional.empty());
        when(mappings.findPrefixMatches(any(), anyString())).thenReturn(List.of());
        when(communityRegistry.lookup(anyString())).thenReturn(Optional.empty());
        when(categories.findVisibleById(eq(COFFEE), any()))
            .thenReturn(Optional.of(CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops")));
        when(categories.findByHouseholdIdAndSlug(any(), anyString())).thenReturn(Optional.empty());
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.of(CategoryFixture.system(UNCATEGORISED, "system.uncategorised",
                "Uncategorised")));
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("food.coffee")))
            .thenReturn(Optional.of(CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops")));
        when(categories.findAllVisible(HOUSEHOLD)).thenReturn(List.of(
            CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops")));

        when(gateway.forUser(OWNER)).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(handle.chat(any(), any())).thenReturn(new AiCompletion(
            "{\"slug\":\"food.coffee\",\"confidence\":0.93}", "qwen2.5:14b", null, null));
    }

    @Test
    @DisplayName("a household EXACT row wins and the model is never contacted")
    void anExactRuleCostsNothing() {
        when(mappings.findByHouseholdIdAndNormalisedMerchantAndMatchType(HOUSEHOLD, "STARBUCKS",
            MatchType.EXACT)).thenReturn(Optional.of(row(MatchType.EXACT, MappingSource.RULE)));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, OWNER, "Starbucks #99", null, null, null);

        assertThat(result.source()).isEqualTo(MappingSource.RULE);
        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a household PREFIX row wins and the model is never contacted")
    void aPrefixRuleCostsNothing() {
        when(mappings.findPrefixMatches(HOUSEHOLD, "STARBUCKS DOWNTOWN"))
            .thenReturn(List.of(row(MatchType.PREFIX, MappingSource.USER)));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, OWNER, "Starbucks Downtown", null, null, null);

        assertThat(result.source()).isEqualTo(MappingSource.USER);
        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a community-registry hit wins and the model is never contacted")
    void aCommunityHitCostsNothing() {
        when(communityRegistry.lookup("STARBUCKS")).thenReturn(
            Optional.of(new CommunityMerchantRegistry.Entry("Coffee Shops",
                new BigDecimal("0.99"))));
        when(categories.findByHouseholdIdAndName(null, "Coffee Shops"))
            .thenReturn(Optional.of(CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops")));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, OWNER, "Starbucks", null, null, null);

        assertThat(result.categoryId()).isEqualTo(COFFEE);
        assertThat(result.source())
            .as("the registry is not a persisted household row and cannot claim a source")
            .isNull();
        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("the AI tier runs only after all three free tiers miss")
    void theModelIsTheFourthTier() {
        CategorisationResult result =
            service.categorise(HOUSEHOLD, OWNER, "Some Unknown Cafe", null, null, null);

        assertThat(result.categoryId()).isEqualTo(COFFEE);
        assertThat(result.source()).isEqualTo(MappingSource.AI);
        verify(gateway).forUser(OWNER);
        verify(mappingWriter).upsert(HOUSEHOLD, OWNER, "Some Unknown Cafe", MatchType.EXACT,
            COFFEE, new BigDecimal("0.930"), MappingSource.AI);
    }

    @Test
    @DisplayName("the AI tier sits BEFORE Uncategorised, which stays the guaranteed last word")
    void uncategorisedIsStillTheLastTier() {
        when(handle.chat(any(), any()))
            .thenReturn(new AiCompletion("no idea, sorry", "qwen2.5:14b", null, null));

        CategorisationResult result =
            service.categorise(HOUSEHOLD, OWNER, "Some Unknown Cafe", null, null, null);

        assertThat(result.categoryId()).isEqualTo(UNCATEGORISED);
        assertThat(result.confidence()).isEqualByComparingTo("0.000");
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("the five-argument overload never touches the gateway, even to ask if AI is on")
    void theOwnerlessOverloadNeverAsksTheGatewayAnything() {
        CategorisationResult result =
            service.categorise(HOUSEHOLD, "Some Unknown Cafe", null, null, null);

        assertThat(result.categoryId()).isEqualTo(UNCATEGORISED);
        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    private static MerchantCategoryMap row(MatchType matchType, MappingSource source) {
        return new MerchantCategoryMap(HOUSEHOLD, "IGNORED", matchType, COFFEE,
            new BigDecimal("0.950"), source, 0, UUID.randomUUID(), Instant.now());
    }
}
