package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiDisabledException;
import finance.trove.ai.AiEndpointUnauthorizedException;
import finance.trove.ai.AiException;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiModelMissingException;
import finance.trove.ai.AiPolicyBlockedException;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.AiTimeoutException;
import finance.trove.ai.AiUnreachableException;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.net.OutboundUrlPolicyException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The AI tier, by behaviour — collapse step 17.
 *
 * <p>The organising principle of this class is gap row 44: <strong>the model's answer is untrusted
 * input</strong>. The old {@code AiCategorisationService} returned the model's own category
 * <em>string</em> to its caller with no lookup of any kind, so a model that answered "Groceries",
 * "groceries", "Grocerys" or "DROP TABLE category" were four different outcomes and only one of
 * them was right. Here every one of them is the same outcome: a miss, and a fall-through to
 * {@code Uncategorised}. A model that answers with another household's category, with prose, with
 * JSON that does not parse, with a missing field, with a confidence of 2.0, or with ten kilobytes
 * of text must produce exactly what a model that is switched off produces, and each of those is a
 * case below.
 *
 * <p>The second principle is that <strong>failure is never the caller's problem</strong>. An
 * import of five hundred transactions must not 409 because one member's endpoint is off, and
 * {@code POST /v1/transactions} must not 403 because a member chose {@code CLOUD_OK}. Every one of
 * the six {@link AiException} subclasses is exercised for that below, and none of them may escape.
 */
class DefaultAiCategorisationTierTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();
    private static final String MERCHANT = "Tim Horton's #402";
    private static final String NORMALISED = "TIM HORTONS";

    private AiGateway gateway;
    private AiHandle handle;
    private CategoryRepository categories;
    private MerchantMappingWriter mappingWriter;
    private DefaultAiCategorisationTier tier;

    @BeforeEach
    void setUp() {
        gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        categories = mock(CategoryRepository.class);
        mappingWriter = mock(MerchantMappingWriter.class);
        tier = new DefaultAiCategorisationTier(gateway, categories, mappingWriter,
            new AiCategorisationProperties(null, null, null));

        when(gateway.forUser(OWNER)).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(categories.findAllVisible(HOUSEHOLD)).thenReturn(List.of(
            CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops"),
            CategoryFixture.system(UUID.randomUUID(), "food.grocery", "Groceries"),
            CategoryFixture.system(UUID.randomUUID(), "system.uncategorised", "Uncategorised")));
        when(categories.findByHouseholdIdAndSlug(any(), anyString())).thenReturn(Optional.empty());
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("food.coffee")))
            .thenReturn(Optional.of(
                CategoryFixture.system(COFFEE, "food.coffee", "Coffee Shops")));
    }

    // ---------------------------------------------------------------- the happy path and the write

    @Test
    @DisplayName("a confident answer resolves the slug and is learned back as an AI/EXACT mapping")
    void aConfidentAnswerIsReturnedAndLearned() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");

        Optional<CategorisationResult> result = tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        assertThat(result).isPresent();
        assertThat(result.get().categoryId()).isEqualTo(COFFEE);
        assertThat(result.get().categoryName()).isEqualTo("Coffee Shops");
        assertThat(result.get().source()).isEqualTo(MappingSource.AI);
        assertThat(result.get().confidence()).isEqualByComparingTo("0.930");
        assertThat(result.get().confidence().scale())
            .as("merchant_category_map.confidence is NUMERIC(4,3)")
            .isEqualTo(3);

        // The write-back is what makes the SECOND sighting of this merchant free — finding D4's
        // "every categorisation re-runs the LLM forever", closed. EXACT and not PREFIX: the model
        // answered about the merchant it was shown and nothing more.
        verify(mappingWriter, times(1)).upsert(HOUSEHOLD, OWNER, MERCHANT, MatchType.EXACT, COFFEE,
            new BigDecimal("0.930"), MappingSource.AI);
    }

    @Test
    @DisplayName("the payload is SENSITIVE — captured, not assumed")
    void everyCallIsSensitive() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");

        tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        ArgumentCaptor<AiSensitivity> sensitivity = ArgumentCaptor.forClass(AiSensitivity.class);
        verify(handle).chat(any(), sensitivity.capture());
        assertThat(sensitivity.getValue())
            .as("a merchant string IS the household's ledger; ORDINARY here would ship a "
                + "member's spending to somebody else's server on a CLOUD_OK privacy tier")
            .isEqualTo(AiSensitivity.SENSITIVE);
    }

    @Test
    @DisplayName("the candidates come from the database, by slug, and the merchant is normalised")
    void thePromptIsBuiltFromTheDatabase() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");

        tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMessage>> messages = ArgumentCaptor.forClass(List.class);
        verify(handle).chat(messages.capture(), any());
        String prompt = messages.getValue().stream().map(AiMessage::content)
            .reduce("", (a, b) -> a + "\n" + b);

        // The old service's prompt listed twenty-five invented names ("Food & Dining", "Ride
        // Share", ...) matching NONE of V14's seeded rows, so even a perfect answer named a
        // category the database does not have.
        assertThat(prompt).contains("food.coffee").contains("food.grocery");
        assertThat(prompt)
            .as("a slug is the stable id; a name can be renamed by a member mid-flight")
            .contains("food.coffee = Coffee Shops");
        assertThat(prompt)
            .as("the normalised merchant is what every other tier matches on, and is less of the "
                + "household's data than the raw bank string")
            .contains(NORMALISED)
            .doesNotContain("#402");
    }

    @Test
    @DisplayName("a household's own category resolves after the system rows are tried")
    void aHouseholdsOwnSlugResolves() {
        UUID own = UUID.randomUUID();
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("cat.treats")))
            .thenReturn(Optional.empty());
        when(categories.findByHouseholdIdAndSlug(eq(HOUSEHOLD), eq("cat.treats")))
            .thenReturn(Optional.of(CategoryFixture.owned(own, HOUSEHOLD, "cat.treats", "Treats")));
        answers("{\"slug\":\"cat.treats\",\"confidence\":0.88}");

        Optional<CategorisationResult> result = tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        assertThat(result).isPresent();
        assertThat(result.get().categoryId()).isEqualTo(own);
    }

    @Test
    @DisplayName("a renamed category still resolves, and answers with its new name")
    void aRenamedCategoryStillResolvesBySlug() {
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("food.coffee")))
            .thenReturn(Optional.of(
                CategoryFixture.system(COFFEE, "food.coffee", "Caffeine Habit")));
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");

        Optional<CategorisationResult> result = tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        assertThat(result).isPresent();
        assertThat(result.get().categoryName()).isEqualTo("Caffeine Habit");
    }

    @Test
    @DisplayName("the threshold is inclusive: exactly 0.70 is believed")
    void theThresholdIsInclusive() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.70}");

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isPresent();
    }

    // ---------------------------------------------------------------- AI off, or nobody to ask

    @Test
    @DisplayName("AI switched off is a miss that opens no connection and writes nothing")
    void disabledAiNeverCalls() {
        when(handle.isEnabled()).thenReturn(false);

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

        verify(handle, never()).chat(any(), any());
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("an ownerless row asks nobody — there is no member whose endpoint this would be")
    void anOwnerlessRowNeverReachesTheGateway() {
        assertThat(tier.categorise(HOUSEHOLD, null, MERCHANT)).isEmpty();

        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a merchant that normalises to nothing asks nobody")
    void anEmptyMerchantNeverReachesTheGateway() {
        assertThat(tier.categorise(HOUSEHOLD, OWNER, "   ")).isEmpty();
        assertThat(tier.categorise(HOUSEHOLD, OWNER, null)).isEmpty();

        verifyNoInteractions(gateway);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a household with no visible categories at all is a miss, not an empty prompt")
    void noCandidatesIsAMiss() {
        when(categories.findAllVisible(HOUSEHOLD)).thenReturn(List.of());

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

        verify(handle, never()).chat(any(), any());
    }

    // ---------------------------------------------------------------- the answer is untrusted

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("hostileAnswers")
    @DisplayName("every hostile or malformed answer is exactly a switched-off model")
    void hostileAnswersAreMisses(String label, String answer) {
        answers(answer);

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT))
            .as(label)
            .isEmpty();

        verifyNoInteractions(mappingWriter);
    }

    static Stream<Arguments> hostileAnswers() {
        String tenKilobytes = "{\"slug\":\"food.coffee\",\"confidence\":0.99,\"why\":\""
            + "x".repeat(10_240) + "\"}";
        return Stream.of(
            arguments(
                "prose instead of JSON",
                "I think this one is a coffee shop, probably."),
            arguments(
                "JSON that does not parse",
                "{\"slug\":\"food.coffee\",\"confidence\":}"),
            arguments(
                "a slug that is an array rather than a string",
                "[{\"slug\":[\"food.coffee\"],\"confidence\":0.99}]"),
            arguments(
                "a slug that is a number",
                "{\"slug\":42,\"confidence\":0.99}"),
            arguments(
                "a nested object where the fields should be",
                "{\"answer\":{\"slug\":\"food.coffee\",\"confidence\":0.99}}"),
            arguments(
                "the confidence field missing",
                "{\"slug\":\"food.coffee\"}"),
            arguments(
                "the slug field missing",
                "{\"confidence\":0.99}"),
            arguments(
                "a confidence that is a string, not a number",
                "{\"slug\":\"food.coffee\",\"confidence\":\"very\"}"),
            arguments(
                "a confidence of 2.0",
                "{\"slug\":\"food.coffee\",\"confidence\":2.0}"),
            arguments(
                "a negative confidence",
                "{\"slug\":\"food.coffee\",\"confidence\":-1}"),
            arguments(
                "an embedded control character",
                "{\"slug\":\"food.cof\0fee\",\"confidence\":0.99}"),
            arguments(
                "ten kilobytes of text",
                tenKilobytes),
            arguments(
                "a slug this household cannot see",
                "{\"slug\":\"food.someone.elses\",\"confidence\":0.99}"),
            arguments(
                "the category's display NAME instead of its slug",
                "{\"slug\":\"Coffee Shops\",\"confidence\":0.99}"),
            arguments(
                "a slug with a path traversal in it",
                "{\"slug\":\"../../etc/passwd\",\"confidence\":0.99}"),
            arguments(
                "an empty answer",
                ""),
            arguments(
                "a null answer",
                null));
    }

    @Test
    @DisplayName("below the threshold is a miss that writes nothing")
    void belowTheThresholdWritesNothing() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.69}");

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("an answer of system.uncategorised is a miss, never a learned mapping")
    void answeringUncategorisedIsNeverLearned() {
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.of(CategoryFixture.system(UUID.randomUUID(),
                "system.uncategorised", "Uncategorised")));
        answers("{\"slug\":\"system.uncategorised\",\"confidence\":0.99}");

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

        // Learning it back would create an EXACT rule that wins tier 1 forever and permanently
        // stop this merchant ever being categorised by anything at all.
        verifyNoInteractions(mappingWriter);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "```json\n{\"slug\":\"food.coffee\",\"confidence\":0.93}\n```",
        "Sure! {\"slug\":\"food.coffee\",\"confidence\":0.93}",
        "  {\"slug\":\"food.coffee\",\"confidence\":0.93}  "
    })
    @DisplayName("a code fence or a chatty preamble around a conforming object is tolerated")
    void aWrappedObjectIsStillRead(String answer) {
        answers(answer);

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isPresent();
    }

    // ---------------------------------------------------------------- failure is never the caller's

    @ParameterizedTest
    @MethodSource("everyAiFailure")
    @DisplayName("every AiException subclass becomes a miss — none of them escapes")
    void everyAiFailureIsAMiss(AiException failure) {
        when(handle.chat(any(), any())).thenThrow(failure);

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT))
            .as(failure.getSlug())
            .isEmpty();

        verifyNoInteractions(mappingWriter);
    }

    static Stream<AiException> everyAiFailure() {
        return Stream.of(
            new AiDisabledException("off"),
            new AiPolicyBlockedException("CLOUD_OK refuses a SENSITIVE payload"),
            new AiUnreachableException("connection refused"),
            new AiEndpointUnauthorizedException("401 upstream"),
            new AiModelMissingException("no such model"),
            new AiTimeoutException("no answer inside the deadline"));
    }

    @Test
    @DisplayName("a URL that stopped passing the egress policy is a miss, not a failed import")
    void anEgressRefusalIsAMiss() {
        when(handle.chat(any(), any()))
            .thenThrow(new OutboundUrlPolicyException("resolves to a private address now"));

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a category deleted between the lookup and the write-back is a miss, not a 404")
    void aVanishedCategoryIsAMiss() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");
        doThrow(ResourceNotFoundException.of("Category", COFFEE))
            .when(mappingWriter).upsert(any(), any(), anyString(), any(), any(), any(), any());

        assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();
    }

    // ------------------------------------------------- a failure to LEARN never fails the caller

    @Test
    @DisplayName("a merchant key too wide for the column is answered but never written")
    void anOverlongMerchantKeyIsNotWritten() {
        // Not a hostile input: several sources report no merchant at all, so the subject of a
        // categorisation is the row's description — TEXT in V19 — while
        // merchant_category_map.normalised_merchant is VARCHAR(255) in V15 and MerchantNormaliser
        // never truncates. Writing this would raise SQL state 22001 inside the writer's
        // REQUIRES_NEW transaction and the DataIntegrityViolationException would fail the import.
        String longSubject = "GROCERY DELIVERY ORDER ".repeat(20).trim();
        assertThat(MerchantNormaliser.normalise(longSubject).length()).isGreaterThan(255);
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");

        Optional<CategorisationResult> result = tier.categorise(HOUSEHOLD, OWNER, longSubject);

        assertThat(result).as("the answer was resolved against this household's own categories "
            + "and is good whether or not it can be stored").isPresent();
        assertThat(result.get().categoryId()).isEqualTo(COFFEE);
        verifyNoInteractions(mappingWriter);
    }

    @Test
    @DisplayName("a write-back that fails on the database is not the caller's problem")
    void aFailedWriteBackStillReturnsTheAnswer() {
        answers("{\"slug\":\"food.coffee\",\"confidence\":0.93}");
        doThrow(new DataIntegrityViolationException("value too long for type character varying"))
            .when(mappingWriter).upsert(any(), any(), anyString(), any(), any(), any(), any());

        Optional<CategorisationResult> result = tier.categorise(HOUSEHOLD, OWNER, MERCHANT);

        assertThat(result)
            .as("the mapping is a learning optimisation; losing it must never lose the answer, "
                + "and must never propagate out of an import of five hundred rows")
            .isPresent();
        assertThat(result.get().categoryId()).isEqualTo(COFFEE);
        assertThat(result.get().source()).isEqualTo(MappingSource.AI);
    }

    private void answers(String content) {
        when(handle.chat(any(), any()))
            .thenReturn(new AiCompletion(content, "qwen2.5:14b", null, null));
    }
}
