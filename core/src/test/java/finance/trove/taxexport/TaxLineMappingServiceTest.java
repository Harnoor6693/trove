package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.taxexport.dto.TaxLineMappingRequest;
import finance.trove.taxexport.dto.TaxLineMappingResponse;
import finance.trove.users.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The resolution rule other steps consume, tested by behaviour.
 *
 * <p>The precedence itself — the caller's override beating the seed, and CA and US not shadowing
 * each other — is resolved in ONE SQL statement ({@code DISTINCT ON … ORDER BY user_id NULLS
 * LAST}), so asserting it against a mocked repository here would only assert the mock. It is
 * proved end to end against a real Postgres in {@code TaxLineMappingIT}. What is tested here is
 * everything the Java layer decides: how a resolved row becomes a {@link TaxLine}, and — the one
 * that matters most — that a category with NO row is still present with empty fields rather than
 * silently absent.
 */
class TaxLineMappingServiceTest {

    private final TaxLineMappingRepository mappings = mock(TaxLineMappingRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TaxLineMappingService service =
        new TaxLineMappingService(mappings, categories, users);

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private static Category category(UUID id, String slug, String name) {
        Category category = new Category(null, slug, name, null, null, null, Instant.now());
        ReflectionTestUtils.setField(category, "id", id);
        return category;
    }

    private static TaxLineMapping mapping(Jurisdiction jurisdiction, UUID categoryId, UUID userId,
                                          String code, String label, boolean deductible) {
        return new TaxLineMapping(jurisdiction, categoryId, userId, code, label, deductible);
    }

    @Test
    @DisplayName("A resolved row keeps its code, label and deductible flag")
    void aResolvedRowKeepsItsFields() {
        UUID software = UUID.randomUUID();
        when(mappings.findEffectiveMap(Jurisdiction.CA, userId)).thenReturn(List.of(
            mapping(Jurisdiction.CA, software, null, "8810", "T2125 line 8810 - Office expenses",
                true)));

        Map<UUID, TaxLine> resolved = service.resolve(userId, Jurisdiction.CA);

        assertThat(resolved.get(software))
            .isEqualTo(new TaxLine("8810", "T2125 line 8810 - Office expenses", true, false));
    }

    @Test
    @DisplayName("A row with a user id is reported as the caller's own override")
    void anOverrideIsMarkedOverridden() {
        UUID software = UUID.randomUUID();
        when(mappings.findEffectiveMap(Jurisdiction.CA, userId)).thenReturn(List.of(
            mapping(Jurisdiction.CA, software, userId, "9270", "T2125 line 9270 - Other expenses",
                true)));

        assertThat(service.resolve(userId, Jurisdiction.CA).get(software).overridden())
            .as("the client shows a member which lines they have changed; a seed row must not "
                + "look like one of theirs")
            .isTrue();
    }

    @Test
    @DisplayName("A category with no row resolves to no line at all, not to null")
    void anUnmappedCategoryResolvesToNone() {
        assertThat(TaxLineMappingService.lineFor(Map.of(), UUID.randomUUID()))
            .isEqualTo(TaxLine.none());
        assertThat(TaxLine.none().code()).isEmpty();
        assertThat(TaxLine.none().label()).isEmpty();
        assertThat(TaxLine.none().isMapped()).isFalse();
    }

    @Test
    @DisplayName("An uncategorised transaction resolves to no line rather than throwing")
    void aNullCategoryResolvesToNone() {
        assertThat(TaxLineMappingService.lineFor(Map.of(), null)).isEqualTo(TaxLine.none());
    }

    @Test
    @DisplayName("Every visible category is listed, mapped or not")
    void everyVisibleCategoryIsListed() {
        UUID mapped = UUID.randomUUID();
        UUID unmapped = UUID.randomUUID();
        when(categories.findAllVisible(householdId)).thenReturn(List.of(
            category(mapped, "business.software", "Software"),
            category(unmapped, "household.own", "A household's own category")));
        when(mappings.findEffectiveMap(Jurisdiction.CA, userId)).thenReturn(List.of(
            mapping(Jurisdiction.CA, mapped, null, "8810", "T2125 line 8810 - Office expenses",
                true)));

        List<TaxLineMappingResponse> lines =
            service.listLines(userId, householdId, Jurisdiction.CA);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).lineCode()).isEqualTo("8810");
        assertThat(lines.get(1))
            .as("a transaction that vanished from a member's tax export because nobody wrote a "
                + "label for its category would be a silent loss — the worst failure this step "
                + "can have. The category is listed with empty cells instead")
            .extracting(TaxLineMappingResponse::categorySlug, TaxLineMappingResponse::lineCode,
                TaxLineMappingResponse::lineLabel, TaxLineMappingResponse::deductible)
            .containsExactly("household.own", "", "", false);
    }

    @Test
    @DisplayName("An upsert on a category the caller cannot see is 404, never 403")
    void anInvisibleCategoryIs404() {
        UUID foreign = UUID.randomUUID();
        when(categories.findVisibleById(foreign, householdId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(userId, householdId, foreign,
            new TaxLineMappingRequest(Jurisdiction.CA, "9270", "Other expenses", true)))
            .as("§9 risk 2: a 403 would confirm that another household's category id is real")
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("An upsert writes the caller's own row and trims the labels")
    void anUpsertWritesTheCallersOwnRow() {
        UUID categoryId = UUID.randomUUID();
        Category visible = category(categoryId, "business.software", "Software");
        when(categories.findVisibleById(categoryId, householdId)).thenReturn(Optional.of(visible));

        TaxLineMappingResponse response = service.upsert(userId, householdId, categoryId,
            new TaxLineMappingRequest(Jurisdiction.CA, "  9270 ", "  Other expenses  ", true));

        verify(mappings).upsertOverride(Jurisdiction.CA, categoryId, userId, "9270",
            "Other expenses", true);
        assertThat(response.lineCode()).isEqualTo("9270");
        assertThat(response.lineLabel()).isEqualTo("Other expenses");
        assertThat(response.overridden())
            .as("the row the member just wrote is theirs by construction")
            .isTrue();
    }

    @Test
    @DisplayName("An upsert is ONE atomic statement, never a read-then-save")
    void anUpsertIsASingleAtomicStatement() {
        UUID categoryId = UUID.randomUUID();
        Category visible = category(categoryId, "business.software", "Software");
        when(categories.findVisibleById(categoryId, householdId)).thenReturn(Optional.of(visible));

        service.upsert(userId, householdId, categoryId,
            new TaxLineMappingRequest(Jurisdiction.CA, "9270", "Other expenses", false));

        verify(mappings).upsertOverride(Jurisdiction.CA, categoryId, userId, "9270",
            "Other expenses", false);
        verify(mappings, never()).save(any());
        verifyNoMoreInteractions(mappings);
    }
}
