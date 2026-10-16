package finance.trove.categorisation.rules;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MatchType;
import finance.trove.categorisation.MerchantMappingWriter;
import finance.trove.categorisation.MerchantNormaliser;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** B1-06 (a learned mapping can be corrected and no longer blocks deletes) and B1-18 (a pin does). */
class LearnedMappingAndPinIT extends LedgerSupport {

    @Autowired
    private MerchantMappingWriter mappingWriter;

    private String customCategory(Session member, String name) {
        HttpResponse<String> created = postJson(appUrl("/v1/categories"), Json.object("name", name),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return Json.data(created.body()).path("id").asString();
    }

    @Test
    @DisplayName("B1-06: a rule overwrites an AI-learned mapping, and an AI mapping does not block "
        + "deleting its category")
    void aRuleCorrectsAnAiMappingAndAiMappingsDoNotBlockDeletes() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "learned-map");
        String bearer = "Bearer " + member.accessToken();
        String dining = customCategory(member, "Dining " + UUID.randomUUID());
        String coffee = systemCategoryId(member, "food.coffee");
        String merchant = "STARBUCKS " + UUID.randomUUID().toString().substring(0, 8);

        mappingWriter.upsert(member.householdId(), member.userId(), merchant, MatchType.EXACT,
            UUID.fromString(dining), new BigDecimal("0.800"), MappingSource.AI);
        HttpResponse<String> rule = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", merchant, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer);
        assertThat(rule.statusCode()).as(rule.body()).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject("select category_id::text from "
                + "merchant_category_map where household_id = ? and normalised_merchant = ?",
            String.class, member.householdId(), MerchantNormaliser.normalise(merchant)))
            .isEqualTo(coffee);
        assertThat(jdbcTemplate.queryForObject("select source from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ?", String.class,
            member.householdId(), MerchantNormaliser.normalise(merchant))).isEqualTo("RULE");

        String other = "OTHER " + UUID.randomUUID().toString().substring(0, 8);
        mappingWriter.upsert(member.householdId(), member.userId(), other, MatchType.EXACT,
            UUID.fromString(dining), new BigDecimal("0.800"), MappingSource.AI);
        HttpResponse<String> deleted = delete(appUrl("/v1/categories/" + dining), null,
            "Authorization", bearer);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("B1-18: a category with a pinned transaction can be deleted; the pin goes")
    void aPinnedCategoryCanBeDeleted() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "pin-delete");
        String bearer = "Bearer " + member.accessToken();
        String account = createManualAccount(member, "Pin " + UUID.randomUUID());
        String category = customCategory(member, "Pinned " + UUID.randomUUID());
        String transaction = createManualTransaction(member, account, "9.00", "2026-09-01",
            "DEBIT", "PIN " + UUID.randomUUID()).path("id").asString();
        assertThat(patchJson(appUrl("/v1/transactions/" + transaction),
            Json.object("categoryId", category), "Authorization", bearer).statusCode())
            .isEqualTo(200);

        HttpResponse<String> deleted = delete(appUrl("/v1/categories/" + category), null,
            "Authorization", bearer);

        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("select count(*) from category_override "
            + "where transaction_id = ?::uuid", Integer.class, transaction)).isZero();
    }
}
