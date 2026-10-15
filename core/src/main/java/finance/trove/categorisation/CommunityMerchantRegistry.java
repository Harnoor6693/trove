package finance.trove.categorisation;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The built-in, household-agnostic merchant-to-category defaults, ported from the old
 * categorisation-service's in-memory registry
 * ({@code services/categorisation-service/src/main/resources/community-merchant-registry.json},
 * {@code CategorisationService.loadCommunityRegistry}).
 *
 * <p><strong>Household-agnostic by construction.</strong> Every entry's {@code categoryName}
 * names a SYSTEM category (V14) — this tier never resolves to a household's own category, so it
 * needs no {@code householdId} and is safe to share one instance across every request. It sits
 * between a household's own {@link MerchantCategoryMap} rows and the {@code Uncategorised}
 * fallback in {@link CategorisationService#categorise}.
 *
 * <p>This tier fixes the "ships with zero rows and nothing reads it" half of D4 by construction
 * rather than by seeding: the old design tried to keep two stores in sync (a seeded-but-dead SQL
 * table and a separately-loaded in-memory map) and never actually wired the second one into a
 * lookup. Here there is exactly one store for the built-in defaults, and it is read on every
 * miss against the household's own rows — nothing else needs to keep it in sync because nothing
 * else owns a copy of it.
 *
 * <p>Keys are pre-normalised at load time with {@link MerchantNormaliser#normalise(String)} —
 * the same function every lookup call runs its input through — so a future change to the
 * normalisation pipeline (a new punctuation rule, say) cannot silently desynchronise this map
 * from what {@link #lookup} is called with.
 */
@Component
public class CommunityMerchantRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CommunityMerchantRegistry.class);
    private static final String RESOURCE_PATH = "/categorisation/community-merchant-registry.json";

    private final Map<String, Entry> registry;

    public CommunityMerchantRegistry() {
        this.registry = load(RESOURCE_PATH);
    }

    /** One community default: the system category it resolves to and its AI-equivalent confidence. */
    public record Entry(String categoryName, BigDecimal confidence) {
    }

    /** The default for this already-normalised merchant string, if the registry has one. */
    public Optional<Entry> lookup(String normalisedMerchant) {
        return Optional.ofNullable(registry.get(normalisedMerchant));
    }

    /** How many entries loaded — used by the "the seed file parses" regression test. */
    public int size() {
        return registry.size();
    }

    /**
     * Package-private so a unit test can exercise the real classpath resource without a Spring
     * context — the "the seed file parses" test of collapse step 5's scope.
     */
    static Map<String, Entry> load(String resourcePath) {
        try (InputStream is = CommunityMerchantRegistry.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("{} not found on the classpath; the community registry tier is empty",
                    resourcePath);
                return Map.of();
            }
            JsonNode root = new ObjectMapper().readTree(is);
            JsonNode merchants = root.get("merchants");
            if (merchants == null || !merchants.isArray()) {
                return Map.of();
            }
            Map<String, Entry> parsed = new HashMap<>();
            for (JsonNode entry : merchants) {
                String key = MerchantNormaliser.normalise(entry.get("name").asText());
                if (key.isEmpty()) {
                    continue;
                }
                parsed.put(key, new Entry(entry.get("categoryName").asText(),
                    BigDecimal.valueOf(entry.get("confidence").asDouble())));
            }
            return Map.copyOf(parsed);
        } catch (Exception e) {
            // swallow-ok: a malformed or missing seed file degrades this fallback tier to empty
            // rather than preventing the application from starting; the household's own rules and
            // the Uncategorised fallback still work.
            LOG.error("Failed to load {}", resourcePath, e);
            return Map.of();
        }
    }
}
