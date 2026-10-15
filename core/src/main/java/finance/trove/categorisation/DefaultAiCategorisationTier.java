package finance.trove.categorisation;

import finance.trove.ai.AiException;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.net.OutboundUrlPolicyException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one implementation of {@link AiCategorisationTier} — collapse step 17.
 *
 * <p>It replaces the old {@code AiCategorisationService}
 * ({@code services/categorisation-service/.../categorise/AiCategorisationService.java}), of which
 * essentially nothing survives but the idea. Each defect it had is closed here by construction:
 * <ul>
 *   <li>It returned the model's own {@code category} <em>string</em> to its caller, never looked
 *       up (gap row 44, §2.3). Here the answer is a <strong>slug</strong>, resolved through
 *       {@link CategoryRepository} against the categories that household can actually see, and a
 *       slug that does not resolve is a miss — never a name to be trusted, never a category to
 *       create, never an id to write.</li>
 *   <li>Its prompt listed twenty-five invented category names ("Food &amp; Dining", "Ride Share",
 *       …) that match none of V14's seeded rows, so even a perfect answer named a category the
 *       database does not have. The candidate list is read from the database, per household, per
 *       call.</li>
 *   <li>{@code double confidence} everywhere, which {@code ArchitectureTest} has banned on domain
 *       API return types since step 2. {@link BigDecimal} in {@code [0.000, 1.000]}, matching the
 *       {@code NUMERIC(4,3)} column.</li>
 *   <li>It logged the merchant name at WARN on three separate failure paths, and a merchant string
 *       is the household's ledger. Nothing here logs the merchant, the prompt, the answer or the
 *       endpoint's body — only the error slug and two opaque ids.</li>
 *   <li>It minted a fresh correlation id per call and its client had no timeouts at all. Both
 *       evaporate: {@link AiGateway} owns the timeouts, the audit row, the egress policy and the
 *       correlation.</li>
 *   <li>Finding D4, "every categorisation re-runs the LLM forever": a confident answer is learned
 *       back through {@link MerchantMappingWriter}, so the second sighting of a merchant is
 *       resolved by tier 1 and costs nothing.</li>
 * </ul>
 *
 * <p><strong>Whose AI.</strong> {@link AiGateway#forUser} is called with the <em>transaction
 * owner's</em> id, not the caller's and not an installation-wide one — {@code ai_settings} is
 * keyed by user id (V16, owner decision 9), so this member's endpoint, key, model and privacy
 * tier are the ones in play. {@link AiHandle#isEnabled()} is asked first because it opens no
 * connection and reads only a snapshot.
 *
 * <p><strong>The payload is always {@link AiSensitivity#SENSITIVE}.</strong> That enum's own
 * javadoc names "a merchant description being categorised" as its example, and a merchant string
 * is the household's ledger. The consequence is deliberate: a member on
 * {@code PrivacyTier.CLOUD_OK} is refused before any connection is opened, and the refusal becomes
 * a miss here rather than an error anyone sees.
 */
@Service
public class DefaultAiCategorisationTier implements AiCategorisationTier {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAiCategorisationTier.class);

    /**
     * The parser for the model's answer, built here rather than injected on purpose: the
     * application's own {@code ObjectMapper} is configured for Trove's wire contract, and this is
     * untrusted text from somebody else's server. It shares no configuration with the API.
     */
    private static final JsonMapper PARSER = JsonMapper.builder().build();

    /**
     * Control characters and format characters, which a conforming answer never contains. Removed
     * before the answer is looked at (gap row 44's "strip control characters"), and their presence
     * additionally makes the whole answer a miss: the only use for this text is an exact slug
     * lookup, so refusing a tampered answer outright is strictly stronger than acting on the
     * stripped remains of one. {@code \t}, {@code \n} and {@code \r} are excluded because they are
     * legal JSON whitespace and a pretty-printing endpoint is not an attacker.
     */
    private static final Pattern CONTROL_CHARACTERS =
        Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]|\\p{Cf}");

    /** The shape of every slug V14 seeds and {@code CategoryService} allows; anything else misses. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9._-]{0,59}");

    /** The scale of {@code merchant_category_map.confidence}, {@code NUMERIC(4,3)}. */
    private static final int CONFIDENCE_SCALE = 3;

    /**
     * The width of {@code merchant_category_map.normalised_merchant}, {@code VARCHAR(255)} (V15).
     *
     * <p>Checked here rather than discovered by Postgres, because the subject of a categorisation
     * is frequently not a merchant name at all: several sources report only a description
     * ({@code CsvSource} and {@code SimplefinNormalizer} both build their {@code RawTransaction}
     * with a null {@code merchantName}), {@code description} is {@code TEXT} in V19, and
     * {@link MerchantNormaliser} never truncates — NFKD can even lengthen its input. A
     * three-hundred-character description that the free tiers miss and the model answers
     * confidently about would otherwise raise SQL state 22001 inside the writer's
     * {@code REQUIRES_NEW} transaction, and a {@code DataIntegrityViolationException} escaping
     * this tier would fail the whole import — the exact opposite of scope item 6.
     */
    private static final int MAX_STORED_MERCHANT_CHARACTERS = 255;

    private final AiGateway gateway;
    private final CategoryRepository categories;
    private final MerchantMappingWriter mappingWriter;
    private final AiCategorisationProperties properties;

    public DefaultAiCategorisationTier(AiGateway gateway, CategoryRepository categories,
                                       MerchantMappingWriter mappingWriter,
                                       AiCategorisationProperties properties) {
        this.gateway = gateway;
        this.categories = categories;
        this.mappingWriter = mappingWriter;
        this.properties = properties;
    }

    @Override
    public Optional<CategorisationResult> categorise(UUID householdId, UUID userId,
                                                     String merchant) {
        if (householdId == null || userId == null) {
            return Optional.empty();
        }
        String normalised = MerchantNormaliser.normalise(merchant);
        if (normalised.isEmpty()) {
            return Optional.empty();
        }

        AiHandle handle = gateway.forUser(userId);
        if (!handle.isEnabled()) {
            return Optional.empty();
        }

        List<Category> candidates = categories.findAllVisible(householdId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        AiCompletion completion = ask(handle, normalised, candidates, householdId, userId);
        if (completion == null) {
            return Optional.empty();
        }
        return believe(completion.content(), householdId, userId, merchant, normalised);
    }

    /**
     * The one network call, with every failure it can produce turned into {@code null}.
     *
     * <p>{@link AiException} is the common base of the six typed failures {@link AiHandle}
     * guarantees, and it is caught rather than each subclass in turn because they all mean the
     * same thing here: this row does not get an AI answer. {@link OutboundUrlPolicyException} is
     * caught alongside it — narrower than a bare {@code catch (Exception)}, which is how the old
     * service hid D4 for a year — because {@link AiHandle}'s own javadoc says a URL that was legal
     * when it was saved can stop being legal by the time it is called, and an import of five
     * hundred transactions must not fail on it.
     *
     * <p>What is logged is the slug and two opaque ids. Never the merchant, never the prompt,
     * never the answer, never the endpoint's response body — an OpenAI-compatible server routinely
     * echoes the request back inside an error ({@code AiException}'s javadoc). There is no
     * transaction id to log at this seam: {@link CategorisationService#categorise} resolves a
     * merchant, not a row, and the request id in the MDC already correlates the line.
     */
    private AiCompletion ask(AiHandle handle, String normalisedMerchant, List<Category> candidates,
                             UUID householdId, UUID userId) {
        try {
            return handle.chat(prompt(normalisedMerchant, candidates), AiSensitivity.SENSITIVE);
        } catch (AiException e) {
            LOG.info("ai-categorisation: {} for household {} (owner {}); falling through to the "
                + "Uncategorised tier", e.getSlug(), householdId, userId);
            return null;
        } catch (OutboundUrlPolicyException e) {
            LOG.info("ai-categorisation: outbound-url-rejected for household {} (owner {}); the "
                + "saved endpoint no longer passes the egress policy", householdId, userId);
            return null;
        }
    }

    /**
     * The prompt, built from the database.
     *
     * <p>The candidates are named by <strong>slug</strong> and not by display name, because a slug
     * is the stable identity: a member can rename "Groceries" mid-flight and every name-keyed
     * answer would then resolve to nothing, while the slug is {@code updatable = false} on
     * {@link Category}. The name travels too, because it is what makes a slug meaningful to a
     * model, but only the slug is read back.
     *
     * <p>The merchant sent is the <em>normalised</em> one — store numbers, card reference suffixes
     * and punctuation already removed by {@link MerchantNormaliser}. That is what every other tier
     * matches on, and it is also strictly less of the household's data leaving the box than the
     * raw bank string would be.
     */
    private static List<AiMessage> prompt(String normalisedMerchant, List<Category> candidates) {
        StringBuilder system = new StringBuilder(candidates.size() * 40 + 400);
        system.append("You classify a bank transaction's merchant into exactly one category.\n")
            .append("Answer with one JSON object and nothing else, in exactly this shape:\n")
            .append("{\"slug\":\"<one slug from the list below>\",\"confidence\":<0.0 to 1.0>}\n")
            .append("The slug must be copied verbatim from the list. Do not invent a slug, do not ")
            .append("answer with a category name, and do not add any explanation.\n")
            .append("Answer \"").append(CategorisationService.UNCATEGORISED_SLUG)
            .append("\" if none of them fits.\n")
            .append("Categories (slug = name):\n");
        for (Category candidate : candidates) {
            system.append(candidate.getSlug()).append(" = ").append(candidate.getName())
                .append('\n');
        }
        return List.of(AiMessage.system(system.toString()),
            AiMessage.user("Merchant: " + normalisedMerchant));
    }

    /**
     * The answer is untrusted input (gap row 44). Length-capped, control-character-stripped,
     * parsed strictly, range-checked, and resolved through the database before any of it is
     * believed.
     *
     * <p>A model that answers with another household's category, with prose, with JSON that does
     * not parse, with a missing field, with a confidence of 2.0, or with ten kilobytes of text
     * produces exactly the same outcome as a model that is switched off.
     */
    private Optional<CategorisationResult> believe(String content, UUID householdId, UUID userId,
                                                   String merchant, String normalisedMerchant) {
        String json = extractObject(content);
        if (json == null) {
            return miss("unparseable", householdId, userId);
        }

        JsonNode root;
        try {
            root = PARSER.readTree(json);
        } catch (JacksonException e) {
            return miss("unparseable", householdId, userId);
        }
        if (!root.isObject()) {
            return miss("unparseable", householdId, userId);
        }

        JsonNode slugNode = root.path("slug");
        JsonNode confidenceNode = root.path("confidence");
        if (!slugNode.isTextual() || !confidenceNode.isNumber()) {
            return miss("incomplete", householdId, userId);
        }

        BigDecimal confidence = confidenceNode.decimalValue();
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            return miss("confidence-out-of-range", householdId, userId);
        }
        if (confidence.compareTo(properties.minConfidence()) < 0) {
            return miss("below-threshold", householdId, userId);
        }

        String slug = slugNode.asString().trim().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()
            || CategorisationService.UNCATEGORISED_SLUG.equals(slug)) {
            // An answer of "Uncategorised" is the model saying it does not know, which is a miss
            // and not a mapping: learning it back would create an EXACT rule that wins tier 1
            // forever and permanently stops this merchant ever being categorised by anything.
            return miss("no-usable-slug", householdId, userId);
        }

        Optional<Category> resolved = categories.findByHouseholdIdAndSlug(null, slug);
        if (resolved.isEmpty()) {
            resolved = categories.findByHouseholdIdAndSlug(householdId, slug);
        }
        if (resolved.isEmpty()) {
            return miss("slug-not-visible-to-this-household", householdId, userId);
        }

        Category category = resolved.get();
        BigDecimal stored = confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
        if (!learn(householdId, userId, merchant, normalisedMerchant, category.getId(), stored)) {
            return miss("category-vanished-before-write-back", householdId, userId);
        }
        return Optional.of(new CategorisationResult(category.getId(), category.getName(), stored,
            MappingSource.AI));
    }

    /**
     * The learned write-back, and the guarantee that failing to learn can never fail the caller.
     *
     * <p>The write is what makes the second sighting of this merchant free (finding D4), but it is
     * an <em>optimisation</em>: the answer above it has already been resolved against this
     * household's own categories and is good whether or not it can be stored. So every way the
     * write can fail is handled here and none of them is allowed to leave this class — an import of
     * five hundred transactions must not fail because one row's mapping could not be persisted.
     * <ul>
     *   <li>A key wider than {@link #MAX_STORED_MERCHANT_CHARACTERS} is not written at all. It is
     *       not a usable mapping anyway: the EXACT tier looks up the same over-long key and would
     *       never find a truncated row, so storing a clipped one would be a rule for a merchant
     *       that does not exist.</li>
     *   <li>{@link ResourceNotFoundException} — the category was deleted between the lookup above
     *       and the write — makes the whole answer a miss, because the category it named is
     *       genuinely gone.</li>
     *   <li>Any other {@code DataAccessException} (a constraint, a dead connection, a full disk)
     *       leaves the answer standing and simply does not learn: the next sighting asks again,
     *       which is exactly the behaviour of an install whose write-back never ran.</li>
     * </ul>
     *
     * @return {@code false} only when the answer itself must be abandoned
     */
    private boolean learn(UUID householdId, UUID userId, String merchant, String normalisedMerchant,
                          UUID categoryId, BigDecimal confidence) {
        if (normalisedMerchant.length() > MAX_STORED_MERCHANT_CHARACTERS) {
            LOG.info("ai-categorisation: write-back skipped (merchant-key-too-long) for household "
                + "{} (owner {}); the resolved category still stands", householdId, userId);
            return true;
        }
        try {
            // Through the INJECTED bean, never through `this`: MerchantMappingWriter is
            // REQUIRES_NEW and its javadoc says outright that a self-invoked call would run inside
            // this pipeline's readOnly transaction and be lost. That was D4.
            mappingWriter.upsert(householdId, userId, merchant, MatchType.EXACT, categoryId,
                confidence, MappingSource.AI);
        } catch (ResourceNotFoundException e) {
            // The category was deleted between the lookup above and the write. Nothing about this
            // row is worth failing an import for.
            return false;
        } catch (org.springframework.dao.DataAccessException e) {
            // swallow-ok: the mapping is a learning optimisation and the answer above it is
            // already resolved. Logged without the merchant, the prompt or the answer, as
            // everything in this class is.
            LOG.warn("ai-categorisation: write-back failed (storage) for household {} (owner {}); "
                + "the resolved category still stands and the next sighting will ask again",
                householdId, userId);
        }
        return true;
    }

    /**
     * The JSON object inside the answer, or {@code null} if there is not one to be had.
     *
     * <p>The length cap is applied first, before anything is parsed. Then control characters are
     * removed and their presence refuses the answer outright. Then the span from the first opening
     * brace to the last closing brace is taken, which tolerates the code fence and the "Sure, here you go:" that
     * instruction-tuned models add without tolerating anything about the content: whatever is
     * extracted still has to parse, still has to carry both fields, and its slug still has to
     * resolve against this household's own categories.
     *
     * <p>A consequence worth naming: a lone conforming object wrapped in an array is accepted the
     * same way a fenced one is. That is deliberate and costs nothing — the envelope is not what
     * makes an answer trustworthy, the database lookup is, and an answer whose slug resolves to a
     * category this household can see is equally usable whichever brackets arrived around it.
     */
    private String extractObject(String content) {
        if (content == null || content.length() > properties.maxAnswerCharacters()) {
            return null;
        }
        String stripped = CONTROL_CHARACTERS.matcher(content).replaceAll("");
        if (stripped.length() != content.length()) {
            return null;
        }
        int open = stripped.indexOf('{');
        int close = stripped.lastIndexOf('}');
        return open < 0 || close <= open ? null : stripped.substring(open, close + 1);
    }

    /** One line, one reason, and nothing of the household's in it. */
    private Optional<CategorisationResult> miss(String reason, UUID householdId, UUID userId) {
        LOG.info("ai-categorisation: answer rejected ({}) for household {} (owner {}); falling "
            + "through to the Uncategorised tier", reason, householdId, userId);
        return Optional.empty();
    }
}
