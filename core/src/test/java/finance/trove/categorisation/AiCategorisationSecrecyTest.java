package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiUnreachableException;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.app.support.LogCapture;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves the merchant string, the prompt and the model's answer never reach a log line.
 *
 * <p>Its shape is {@code finance.trove.ai.AiSecrecyTest}'s, which step 12 wrote for the API key,
 * and it exists for the same reason: the failure it guards against is silent and permanent. A
 * merchant string is not an incidental detail of a transaction — it <em>is</em> the household's
 * ledger, and a log line naming where a member shops shows up months later in a log aggregator, on
 * a shared screen, or in a support bundle somebody emailed somewhere.
 *
 * <p>This is a regression test against a defect that shipped. The old
 * {@code AiCategorisationService} logged the merchant name at WARN on three separate paths
 * ({@code AiCategorisationService.java:88}, {@code :97} and {@code :106}) — precisely the
 * well-meaning "log what went wrong" that puts the ledger in the log, and precisely the paths a
 * misbehaving endpoint takes most often.
 *
 * <p>The level is forced to TRACE for the duration, which is the whole point: at the shipped
 * {@code finance.trove: INFO} some of these paths log nothing at all and the assertion would pass
 * without proving anything.
 */
class AiCategorisationSecrecyTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();
    private static final String MERCHANT = "Dr Suzuki Fertility Clinic #7712";
    private static final String NORMALISED = "DR SUZUKI FERTILITY CLINIC";
    private static final String ANSWER_TEXT = "probably the clinic on Bloor, they go monthly";

    private AiGateway gateway;
    private AiHandle handle;
    private CategoryRepository categories;
    private MerchantMappingWriter mappingWriter;
    private DefaultAiCategorisationTier tier;
    private Logger troveLogger;
    private Level previousLevel;

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
            CategoryFixture.system(COFFEE, "health.doctor", "Doctor")));
        when(categories.findByHouseholdIdAndSlug(any(), anyString())).thenReturn(Optional.empty());
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("health.doctor")))
            .thenReturn(Optional.of(CategoryFixture.system(COFFEE, "health.doctor", "Doctor")));

        troveLogger = (Logger) LoggerFactory.getLogger("finance.trove");
        previousLevel = troveLogger.getLevel();
        troveLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void restoreLevel() {
        troveLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("a successful categorisation logs neither the merchant nor the answer")
    void nothingSecretIsLoggedOnSuccess() {
        when(handle.chat(any(), any())).thenReturn(new AiCompletion(
            "{\"slug\":\"health.doctor\",\"confidence\":0.97}", "qwen2.5:14b", null, null));

        try (LogCapture logs = LogCapture.start()) {
            assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isPresent();

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(MERCHANT)
                .doesNotContain(NORMALISED)
                .doesNotContain("SUZUKI")
                .doesNotContain("health.doctor");
        }
    }

    @Test
    @DisplayName("an endpoint failure logs the slug and the ids, never the merchant")
    void nothingSecretIsLoggedOnAnEndpointFailure() {
        when(handle.chat(any(), any())).thenThrow(new AiUnreachableException("connection refused"));

        try (LogCapture logs = LogCapture.start()) {
            assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

            String captured = String.join("\n", logs.lines());
            assertThat(captured).doesNotContain(MERCHANT).doesNotContain("SUZUKI");
            assertThat(captured)
                .as("something has to be logged, or the operator has nothing to go on")
                .contains("ai-unreachable");
            assertThat(captured)
                .as("two opaque ids are what makes the line actionable without disclosing anything")
                .contains(HOUSEHOLD.toString())
                .contains(OWNER.toString());
        }
    }

    @Test
    @DisplayName("a rejected answer logs why it was rejected, never what it said")
    void nothingSecretIsLoggedWhenTheAnswerIsRejected() {
        when(handle.chat(any(), any()))
            .thenReturn(new AiCompletion(ANSWER_TEXT, "qwen2.5:14b", null, null));

        try (LogCapture logs = LogCapture.start()) {
            assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

            String captured = String.join("\n", logs.lines());
            assertThat(captured)
                .doesNotContain(MERCHANT)
                .doesNotContain("SUZUKI")
                .doesNotContain(ANSWER_TEXT)
                .doesNotContain("Bloor");
            assertThat(captured)
                .as("the operator learns that an answer was rejected and why, and nothing else")
                .contains("unparseable");
        }
    }

    @Test
    @DisplayName("a low-confidence answer logs no merchant either")
    void nothingSecretIsLoggedBelowTheThreshold() {
        when(handle.chat(any(), any())).thenReturn(new AiCompletion(
            "{\"slug\":\"health.doctor\",\"confidence\":0.11}", "qwen2.5:14b", null, null));

        try (LogCapture logs = LogCapture.start()) {
            assertThat(tier.categorise(HOUSEHOLD, OWNER, MERCHANT)).isEmpty();

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(MERCHANT)
                .doesNotContain("SUZUKI")
                .contains("below-threshold");
        }
    }
}
