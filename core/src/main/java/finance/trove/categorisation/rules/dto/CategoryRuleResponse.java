package finance.trove.categorisation.rules.dto;

import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MatchType;
import finance.trove.categorisation.MerchantCategoryMap;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One household merchant rule as the rules controller returns it. {@code merchant} is the
 * normalised, stored pattern — not necessarily byte-for-byte what the caller originally typed,
 * since {@link finance.trove.categorisation.MerchantNormaliser} runs on every write.
 */
public record CategoryRuleResponse(UUID id, UUID householdId, String merchant,
                                   MatchType matchType, UUID categoryId, String categoryName,
                                   BigDecimal confidence, MappingSource source, int priority,
                                   Instant createdAt, Instant updatedAt) {

    public static CategoryRuleResponse of(MerchantCategoryMap rule, String categoryName) {
        return new CategoryRuleResponse(rule.getId(), rule.getHouseholdId(),
            rule.getNormalisedMerchant(), rule.getMatchType(), rule.getCategoryId(), categoryName,
            rule.getConfidence(), rule.getSource(), rule.getPriority(), rule.getCreatedAt(),
            rule.getUpdatedAt());
    }
}
