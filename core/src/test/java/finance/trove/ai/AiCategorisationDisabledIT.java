package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.categorisation.BatchCategoriser;
import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.CategorisationService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An install with no AI at all, through the <strong>real</strong> {@link DefaultAiGateway} —
 * collapse step 17.
 *
 * <p>This is the case that must not regress, and it is the common one: AI is off by default on
 * every install, no feature may require it, and the overwhelming majority of Trove installs will
 * never have an {@code ai_settings} row. The property being proved is that the pipeline behaves
 * <em>exactly</em> as the merged tree does today — the same category, from the same tiers, with
 * nothing contacted and nothing written.
 *
 * <p>Unlike {@link AiCategorisationIT}, nothing here is substituted: the autowired
 * {@link CategorisationService} carries the Spring-wired {@code DefaultAiCategorisationTier} over
 * the Spring-wired {@link DefaultAiGateway}, reading real {@code ai_settings} rows out of real
 * Postgres. A {@link AiStubServer} is started anyway and asserted to have received nothing, so the
 * test would fail if some future wiring gave a member an endpoint they never configured.
 *
 * <p>{@code AiSettingsIT} owns the settings route; what this class owns is the categorisation
 * pipeline's behaviour when that route was never used.
 */
class AiCategorisationDisabledIT extends AuthFlowSupport {

    @Autowired
    private CategorisationService categorisation;

    @Autowired
    private BatchCategoriser batch;

    private AiStubServer stub;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("with no ai_settings row, naming the owner changes nothing at all")
    void theOwnerAwareOverloadMatchesTheOwnerlessOne() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-off-parity");
        String merchant = "NO AI HERE " + UUID.randomUUID();

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_settings where user_id = ?", Long.class, member.userId()))
            .as("this member configured no AI, which is every member on a default install")
            .isZero();

        CategorisationResult withoutOwner =
            categorisation.categorise(member.householdId(), merchant, null, null, null);
        CategorisationResult withOwner = categorisation.categorise(member.householdId(),
            member.userId(), merchant, null, null, null);

        assertThat(withOwner.categoryId()).isEqualTo(withoutOwner.categoryId());
        assertThat(withOwner.categoryName()).isEqualTo("Uncategorised");
        assertThat(withOwner.source()).isNull();
        assertThat(withOwner.confidence()).isEqualByComparingTo(withoutOwner.confidence());
    }

    @Test
    @DisplayName("the rule tiers still answer, and the model is still never contacted")
    void theRuleTiersAreUntouched() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-off-rules");

        CategorisationResult result = categorisation.categorise(member.householdId(),
            member.userId(), "Starbucks #99", null, null, null);

        assertThat(result.categoryName())
            .as("the community-registry tier is unchanged by step 17")
            .isEqualTo("Coffee Shops");
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @DisplayName("no ai_settings row means no learned mapping is ever written")
    void nothingIsWrittenBack() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-off-nowrite");
        String merchant = "NO AI WRITE " + UUID.randomUUID();

        categorisation.categorise(member.householdId(), member.userId(), merchant, null, null,
            null);

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map where household_id = ? and source = 'AI' "
                + "and normalised_merchant like ?",
            Long.class, member.householdId(), "NO AI WRITE%"))
            .isZero();
        assertThat(stub.received()).isEmpty();
    }

    @Test
    @DisplayName("a batch on an AI-less install costs nothing and still categorises every row")
    void theBatchRunsTheFreeTiersOnly() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-off-batch");
        List<BatchCategoriser.Item> items = List.of(
            item("Starbucks #99"), item("Tim Hortons #4"), item("MYSTERY " + UUID.randomUUID()));

        BatchCategoriser.Outcome outcome =
            batch.categorise(member.householdId(), member.userId(), items);

        assertThat(outcome.results()).hasSize(3);
        assertThat(outcome.aiCallsAttempted())
            .as("no ai_settings row, so no call was attempted and none could have been — the "
                + "batch asks the gateway once for the whole batch and skips the AI pass, rather "
                + "than re-running the pipeline per unresolved merchant and reporting attempts "
                + "that cost nothing and contacted nobody")
            .isZero();
        assertThat(outcome.leftUncategorisedAtCap())
            .as("nothing was truncated; the default cap was never reached")
            .isZero();
        assertThat(stub.received()).isEmpty();
    }

    private static BatchCategoriser.Item item(String merchant) {
        return new BatchCategoriser.Item(UUID.randomUUID(), merchant, null,
            new BigDecimal("4.50"), "CAD");
    }
}
