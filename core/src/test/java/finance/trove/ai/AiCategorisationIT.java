package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.categorisation.AiCategorisationProperties;
import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.CategorisationService;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.categorisation.CommunityMerchantRegistry;
import finance.trove.categorisation.DefaultAiCategorisationTier;
import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MerchantCategoryMapRepository;
import finance.trove.categorisation.MerchantMappingWriter;
import finance.trove.categorisation.MerchantNormaliser;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The AI tier against a real OpenAI-compatible server, a real Postgres and the real write-back —
 * collapse step 17.
 *
 * <p>Three things can only be proved here and not in a unit test, and they are this class's three
 * cases: the learned mapping <strong>really lands</strong> in {@code merchant_category_map} with
 * {@code source = 'AI'}, it <strong>survives the caller's transaction rolling back</strong>
 * (which is what {@code REQUIRES_NEW} is for, and what the D4 self-invocation bug destroyed), and
 * a second categorisation of the same merchant <strong>makes no HTTP request at all</strong> —
 * finding D4's "every categorisation re-runs the LLM forever", closed, measured at the socket.
 *
 * <p><strong>Why the gateway is assembled here rather than autowired.</strong> {@link AiStubServer}
 * binds to {@code 127.0.0.1}, and {@code OutboundUrlPolicy} refuses loopback unless
 * <em>both</em> keys of the two-key opt-in are set — the install-wide
 * {@code TROVE_EGRESS_ALLOW_PRIVATE} and the member's own. The install-wide half is false for the
 * whole integration suite ({@code BaseIT} sets no such property, one Spring context is shared by
 * every IT, and a class cannot flip it without forking a second context, which is banned). So this
 * class does exactly what {@code OpenAiCompatibleClientTest} and {@code AiSecrecyTest} do: it
 * builds the {@code ResolvedTarget} directly, through {@link AiStubServer#endpoint}, and drives the
 * production {@link OpenAiCompatibleClient} built by the production {@code HttpClientConfig}. What
 * is under test here is the tier, the parse, the resolution and the write-back — the egress
 * policy's own decision table is {@code OutboundUrlPolicyTest}'s subject and the route that runs it
 * is {@link AiPolicyIT}'s, and the per-call audit row is {@code DefaultAiGatewayTest}'s.
 *
 * <p>Everything below the gateway is the real thing: the real {@link CategorisationService} tiers,
 * the real {@link CategoryRepository} against Flyway-migrated Postgres, and the real Spring
 * {@link MerchantMappingWriter} bean, so its {@code REQUIRES_NEW} proxy is genuinely in play.
 */
class AiCategorisationIT extends AuthFlowSupport {

    private static final String CHAT_PATH = "/chat/completions";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private MerchantCategoryMapRepository mappings;

    @Autowired
    private CommunityMerchantRegistry communityRegistry;

    @Autowired
    private MerchantMappingWriter mappingWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private AiStubServer stub;
    private CategorisationService service;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        service = new CategorisationService(categories, mappings, communityRegistry,
            new DefaultAiCategorisationTier(new StubBackedGateway(stub), categories, mappingWriter,
                new AiCategorisationProperties(null, null, null)));
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("a confident answer lands a real AI mapping row that the next lookup reads back")
    void theWriteBackReallyLands() {
        Session admin = loginBootstrapAdmin();
        String merchant = "AI WRITEBACK " + UUID.randomUUID();
        answers("food.coffee", "0.93");

        // Inside a readOnly transaction, which is how production enters the pipeline: categorise
        // is @Transactional(readOnly = true) and this class hand-builds the service, so without
        // this the write-back would never be exercised under the attributes it really runs with.
        CategorisationResult result = inAReadOnlyTransaction(() ->
            service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null));

        assertThat(result.source()).isEqualTo(MappingSource.AI);
        assertThat(result.categoryName()).isEqualTo("Coffee Shops");
        assertThat(result.confidence()).isEqualByComparingTo("0.930");

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select category_id, match_type, source, confidence, created_by "
                + "from merchant_category_map where household_id = ? and normalised_merchant = ?",
            admin.householdId(), normalise(merchant));
        assertThat(row.get("source")).isEqualTo("AI");
        assertThat(row.get("match_type"))
            .as("the model answered about the merchant it was shown and nothing more")
            .isEqualTo("EXACT");
        assertThat(row.get("category_id")).isEqualTo(systemCategoryId("food.coffee"));
        assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo("0.930");
        assertThat(row.get("created_by"))
            .as("the transaction owner is recorded, because it was their endpoint and their money")
            .isEqualTo(admin.userId());
    }

    @Test
    @DisplayName("a second categorisation of the same merchant makes no HTTP request at all")
    void theSecondSightingIsFree() {
        Session admin = loginBootstrapAdmin();
        String merchant = "AI SECOND SIGHTING " + UUID.randomUUID();
        answers("food.grocery", "0.88");

        CategorisationResult first =
            service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null);
        assertThat(first.source()).isEqualTo(MappingSource.AI);
        int callsAfterFirst = stub.received().size();
        assertThat(callsAfterFirst).as("the first sighting costs exactly one call").isEqualTo(1);

        CategorisationResult second =
            service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null);

        assertThat(second.categoryId()).isEqualTo(first.categoryId());
        assertThat(second.source())
            .as("the EXACT tier answered from the row the first call learned")
            .isEqualTo(MappingSource.AI);
        assertThat(stub.received())
            .as("finding D4's 'every categorisation re-runs the LLM forever' — measured at the "
                + "socket, not inferred from a mock")
            .hasSize(callsAfterFirst);
    }

    @Test
    @DisplayName("a differently-spelled sighting of the same merchant is free too")
    void normalisationMakesTheSecondSpellingFree() {
        Session admin = loginBootstrapAdmin();
        String tag = letters(10);
        answers("food.coffee", "0.91");

        service.categorise(admin.householdId(), admin.userId(),
            "Bean There " + tag + " #402", null, null, null);
        int afterFirst = stub.received().size();

        CategorisationResult second = service.categorise(admin.householdId(), admin.userId(),
            "bean there " + tag, null, null, null);

        assertThat(second.source()).isEqualTo(MappingSource.AI);
        assertThat(stub.received())
            .as("the write-back is keyed on the normalised merchant, which is what the EXACT tier "
                + "looks up")
            .hasSize(afterFirst);
    }

    @Test
    @DisplayName("the write-back survives the caller's own transaction rolling back")
    void theWriteBackSurvivesAnOuterRollback() {
        Session admin = loginBootstrapAdmin();
        String merchant = "AI OUTER ROLLBACK " + UUID.randomUUID();
        answers("food.fastfood", "0.95");

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        // readOnly, because that is exactly what CategorisationService.categorise runs in and it
        // is the condition D4 was about: a write attempted from inside the pipeline's own
        // read-only session. REQUIRES_NEW suspends this transaction and opens its own writable
        // one, which is the property under test.
        outer.setReadOnly(true);
        RuntimeException forced = new RuntimeException("force the outer transaction to roll back");
        RuntimeException caught = null;
        try {
            outer.executeWithoutResult(status -> {
                service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null);
                throw forced;
            });
        } catch (RuntimeException e) {
            caught = e;
        }
        assertThat(caught).as("the outer transaction really did throw").isSameAs(forced);

        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ? and source = 'AI'",
            Integer.class, admin.householdId(), normalise(merchant));
        assertThat(rows)
            .as("MerchantMappingWriter is REQUIRES_NEW and is reached through the injected bean, "
                + "never through this — that is the D4 self-invocation fix")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a slug the household cannot see is a miss that writes nothing")
    void anUnknownSlugWritesNothing() {
        Session admin = loginBootstrapAdmin();
        String merchant = "AI UNKNOWN SLUG " + UUID.randomUUID();
        answers("food.someone.elses.category", "0.99");

        CategorisationResult result =
            service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null);

        assertThat(result.categoryName()).isEqualTo("Uncategorised");
        assertThat(result.source()).isNull();
        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ?",
            Integer.class, admin.householdId(), normalise(merchant));
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("an endpoint that fails is a miss, never an exception the caller sees")
    void aFailingEndpointIsAMiss() {
        Session admin = loginBootstrapAdmin();
        String merchant = "AI ENDPOINT DOWN " + UUID.randomUUID();
        stub.status(CHAT_PATH, 500);

        CategorisationResult result =
            service.categorise(admin.householdId(), admin.userId(), merchant, null, null, null);

        assertThat(result.categoryName()).isEqualTo("Uncategorised");
        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map where household_id = ? "
                + "and normalised_merchant = ?",
            Integer.class, admin.householdId(), normalise(merchant));
        assertThat(rows).isZero();
    }

    /**
     * Runs {@code call} inside a {@code readOnly = true} transaction — the attributes
     * {@link CategorisationService#categorise} carries in production.
     *
     * <p>This class builds its {@link CategorisationService} by hand (for the loopback reason in
     * the class javadoc), so no Spring transactional proxy applies to it and the pipeline would
     * otherwise run with no transaction at all. Reproducing the read-only session here costs
     * nothing and is the condition the write-back has to survive: the D4 bug was precisely a write
     * attempted from inside it.
     */
    private <T> T inAReadOnlyTransaction(Supplier<T> call) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template.execute(status -> call.get());
    }

    /** The stub answers with the JSON object the tier asks for, inside an OpenAI-shaped body. */
    private void answers(String slug, String confidence) {
        stub.json(CHAT_PATH, "{\"choices\":[{\"message\":{\"content\":"
            + "\"{\\\"slug\\\":\\\"" + slug + "\\\",\\\"confidence\\\":" + confidence + "}\"}}]}");
    }

    private static String normalise(String merchant) {
        return MerchantNormaliser.normalise(merchant);
    }

    /**
     * A letters-only tag, so the merchant is unique across the shared suite <em>and</em> normalises
     * to itself. A random hex tag would not: {@link MerchantNormaliser} strips a trailing
     * alphanumeric run that contains a digit, which is a card reference suffix, so two spellings of
     * a hex-tagged merchant would not normalise to the same key and the test would be measuring
     * the normaliser rather than the write-back.
     */
    private static String letters(int length) {
        StringBuilder tag = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            tag.append((char) ('A' + RANDOM.nextInt(26)));
        }
        return tag.toString();
    }

    private UUID systemCategoryId(String slug) {
        return jdbcTemplate.queryForObject(
            "select id from category where household_id is null and slug = ?", UUID.class, slug);
    }

    /**
     * An {@link AiGateway} whose handle is enabled, {@link PrivacyTier#LOCAL_ONLY} and pointed at
     * the stub — the one seam this class substitutes, for the loopback reason in the class javadoc.
     * Everything it does on {@link AiHandle#chat} is the production client's.
     */
    private static final class StubBackedGateway implements AiGateway {

        private final AiStubServer stub;
        private final OpenAiCompatibleClient client = AiClientSupport.client();

        private StubBackedGateway(AiStubServer stub) {
            this.stub = stub;
        }

        @Override
        public AiHandle forUser(UUID userId) {
            AiEndpoint endpoint = stub.endpoint(null, "qwen2.5:14b");
            return new AiHandle() {

                @Override
                public boolean isEnabled() {
                    return true;
                }

                @Override
                public PrivacyTier privacyTier() {
                    return PrivacyTier.LOCAL_ONLY;
                }

                @Override
                public String chatModel() {
                    return endpoint.model();
                }

                @Override
                public String visionModel() {
                    return null;
                }

                @Override
                public AiCompletion chat(List<AiMessage> messages, AiSensitivity sensitivity) {
                    if (!privacyTier().permits(sensitivity)) {
                        throw new AiPolicyBlockedException("refused by the privacy tier");
                    }
                    return client.chat(endpoint, messages);
                }

                @Override
                public AiCompletion chatStream(List<AiMessage> messages,
                                               AiSensitivity sensitivity,
                                               AiStreamListener listener) {
                    throw new UnsupportedOperationException("step 13's, not this test's");
                }

                @Override
                public AiCompletion vision(String prompt, byte[] image, String contentType,
                                           AiSensitivity sensitivity) {
                    throw new UnsupportedOperationException("step 14's, not this test's");
                }

                @Override
                public List<AiModel> models() {
                    return client.models(endpoint);
                }
            };
        }
    }
}
