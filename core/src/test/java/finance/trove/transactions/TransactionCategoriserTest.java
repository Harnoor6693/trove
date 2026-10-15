package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.CategorisationService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The owner-aware categorise on the insert path — collapse step 16 wire 2, closing step 17's
 * recorded follow-up ("whoever threads the principal through it should call the six-argument
 * categorise").
 *
 * <p>Both halves are asserted here rather than in an IT, and that is a deliberate choice with a
 * reason: the AI tier can only be reached through {@code CategorisationService}'s six-argument
 * overload, and whether an AI call is then MADE is decided inside the tier by the owner's
 * {@code ai_settings} — which the integration suite cannot switch on, because
 * {@code OutboundUrlPolicy} refuses the loopback address a stub server binds to and the
 * install-wide opt-in is off for the whole suite (the reason {@code AiCategorisationIT} hand-builds
 * its own service). What CAN be proved anywhere is the thing that was actually broken: which
 * overload is called, and with whose id. A row with an owner reaches the tier-capable overload
 * with that owner; an ownerless row reaches it with {@code null} and therefore takes the non-AI
 * path by construction.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionCategoriserTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID TRANSACTION = UUID.randomUUID();

    @Mock
    private CategorisationService categorisation;

    @Mock
    private CategoryOverrideRepository overrides;

    private TransactionCategoriser categoriser() {
        when(categorisation.categorise(any(), any(), any(), any(), any(), any()))
            .thenReturn(new CategorisationResult(CATEGORY, "Groceries", new BigDecimal("0.90"),
                null));
        return new TransactionCategoriser(categorisation, overrides);
    }

    @Test
    @DisplayName("a row with an owner reaches the six-argument categorise carrying that owner")
    void anOwnedRowCanReachTheTier() {
        UUID resolved = categoriser().categoriseOnInsert(HOUSEHOLD, OWNER, "LOBLAWS", "Groceries",
            new BigDecimal("42.00"), "CAD");

        assertThat(resolved).isEqualTo(CATEGORY);
        verify(categorisation).categorise(eq(HOUSEHOLD), eq(OWNER), eq("LOBLAWS"), eq("Groceries"),
            eq(new BigDecimal("42.00")), eq("CAD"));
    }

    @Test
    @DisplayName("an ownerless row takes the non-AI path: the owner argument is null")
    void anOwnerlessRowTakesTheFreeTiersAlone() {
        UUID resolved = categoriser().categoriseOnInsert(HOUSEHOLD, null, "LOBLAWS", null,
            new BigDecimal("42.00"), "CAD");

        assertThat(resolved).isEqualTo(CATEGORY);
        verify(categorisation).categorise(eq(HOUSEHOLD), isNull(), eq("LOBLAWS"), isNull(),
            eq(new BigDecimal("42.00")), eq("CAD"));
    }

    @Test
    @DisplayName("the five-argument overload still means 'no owner', so no caller changed "
        + "behaviour")
    void theLegacyOverloadDelegatesWithNoOwner() {
        categoriser().categoriseOnInsert(HOUSEHOLD, "LOBLAWS", null, new BigDecimal("42.00"),
            "CAD");

        ArgumentCaptor<UUID> owner = ArgumentCaptor.forClass(UUID.class);
        verify(categorisation).categorise(eq(HOUSEHOLD), owner.capture(), eq("LOBLAWS"), isNull(),
            eq(new BigDecimal("42.00")), eq("CAD"));
        assertThat(owner.getValue())
            .as("IngestionBatchWriter still calls this form from inside its write transaction, "
                + "where an AI round-trip would pin a pooled connection")
            .isNull();
    }

    @Test
    @DisplayName("a source that reports only a description is matched on the description")
    void aBlankMerchantFallsBackToTheDescription() {
        categoriser().categoriseOnInsert(HOUSEHOLD, OWNER, "   ", "MONTHLY TRANSIT PASS",
            new BigDecimal("10.00"), "CAD");

        verify(categorisation).categorise(eq(HOUSEHOLD), eq(OWNER), eq("MONTHLY TRANSIT PASS"),
            eq("MONTHLY TRANSIT PASS"), eq(new BigDecimal("10.00")), eq("CAD"));
    }

    @Test
    @DisplayName("a pin is read through the override repository, not re-implemented")
    void aPinIsRead() {
        when(overrides.existsByHouseholdIdAndTransactionId(HOUSEHOLD, TRANSACTION))
            .thenReturn(true);

        assertThat(new TransactionCategoriser(categorisation, overrides)
            .isPinned(HOUSEHOLD, TRANSACTION)).isTrue();
    }
}
