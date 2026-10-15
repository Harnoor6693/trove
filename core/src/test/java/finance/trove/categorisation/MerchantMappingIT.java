package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link MerchantMappingWriter} — the D4 self-invocation fix, proved directly rather than
 * through an endpoint, since "no listener yet" means nothing in this step calls it over HTTP
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6).
 *
 * <p>The old service's {@code persistMerchantMapping} was called by self-invocation from within
 * {@code categorise}'s own {@code readOnly = true} transaction, so its {@code @Transactional}
 * annotation was never applied through the Spring AOP proxy and the write either never ran or ran
 * inside — and died with — the caller's own transaction. {@link #writeSurvivesOuterRollback()} is
 * the regression test that shape of bug needs: it opens an outer transaction, calls the writer
 * (a genuinely separate bean), rolls the outer transaction back, and proves the row still exists.
 */
class MerchantMappingIT extends AuthFlowSupport {

    @Autowired
    private MerchantMappingWriter writer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("The REQUIRES_NEW write survives the caller's own transaction rolling back")
    void writeSurvivesOuterRollback() {
        Session admin = loginBootstrapAdmin();
        UUID householdId = admin.householdId();
        UUID categoryId = systemCategoryId("food.coffee");
        String merchant = "OUTER ROLLBACK TEST " + UUID.randomUUID();

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        RuntimeException forcedRollback =
            new RuntimeException("force the outer transaction to roll back");
        RuntimeException caught = null;
        try {
            outer.executeWithoutResult(status -> {
                writer.upsert(householdId, admin.userId(), merchant, MatchType.EXACT, categoryId,
                    new BigDecimal("0.900"), MappingSource.USER);
                throw forcedRollback;
            });
        } catch (RuntimeException e) {
            caught = e;
        }
        assertThat(caught).as("the outer transaction really did throw and roll back")
            .isSameAs(forcedRollback);

        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ?",
            Integer.class, householdId, MerchantNormaliser.normalise(merchant));
        assertThat(rows)
            .as("the writer's own REQUIRES_NEW transaction must commit independently of the "
                + "outer transaction that called it and then rolled back")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("The upsert is idempotent: a second call for the same merchant replaces, not duplicates")
    void upsertIsIdempotent() {
        Session admin = loginBootstrapAdmin();
        UUID householdId = admin.householdId();
        UUID coffee = systemCategoryId("food.coffee");
        UUID fastFood = systemCategoryId("food.fastfood");
        String merchant = "IDEMPOTENT TEST " + UUID.randomUUID();

        writer.upsert(householdId, admin.userId(), merchant, MatchType.EXACT, coffee,
            new BigDecimal("0.700"), MappingSource.AI);
        writer.upsert(householdId, admin.userId(), merchant, MatchType.EXACT, fastFood,
            new BigDecimal("0.950"), MappingSource.USER);

        String normalised = MerchantNormaliser.normalise(merchant);
        Integer rows = jdbcTemplate.queryForObject(
            "select count(*) from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ?",
            Integer.class, householdId, normalised);
        assertThat(rows).as("one row per (household, merchant), not two").isEqualTo(1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "select category_id, source from merchant_category_map "
                + "where household_id = ? and normalised_merchant = ?",
            householdId, normalised);
        assertThat(row.get("category_id").toString())
            .as("the second call's category replaced the first's")
            .isEqualTo(fastFood.toString());
        assertThat(row.get("source")).isEqualTo("USER");
    }

    @Test
    @DisplayName("A categoryId that does not resolve to this household is refused")
    void unresolvableCategoryIsRefused() {
        Session admin = loginBootstrapAdmin();

        assertThatThrownBy(() -> writer.upsert(admin.householdId(), admin.userId(),
            "Some Merchant", MatchType.EXACT, UUID.randomUUID(), new BigDecimal("0.900"),
            MappingSource.AI))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private UUID systemCategoryId(String slug) {
        return jdbcTemplate.queryForObject(
            "select id from category where household_id is null and slug = ?", UUID.class, slug);
    }
}
