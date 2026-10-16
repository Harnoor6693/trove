package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.dto.CategoryRequest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slug generation against the {@code category.slug VARCHAR(60)} column (CI-39). The slug column
 * is narrower than the 100-character name limit, so the slug has to be cut, and the cut must
 * leave room for a {@code -2} style suffix and must not end on a dash.
 */
class CategoryServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final CategoryService service =
        new CategoryService(categories, mock(MerchantCategoryMapRepository.class));

    private String createSlug(String name) {
        when(categories.save(any(Category.class))).thenAnswer(call -> call.getArgument(0));
        return service.create(HOUSEHOLD, USER, new CategoryRequest(name, null, null)).getSlug();
    }

    @Test
    @DisplayName("A 100-character name gets a slug of at most 60 characters")
    void longNameIsTruncated() {
        assertThat(createSlug("a".repeat(100))).isEqualTo("a".repeat(60));
    }

    @Test
    @DisplayName("Truncation never leaves a trailing dash")
    void truncationReStripsTrailingDashes() {
        // 59 letters, a separator, then more text: a plain cut at 60 would end in "-".
        String slug = createSlug("a".repeat(59) + " bcd");

        assertThat(slug).isEqualTo("a".repeat(59));
    }

    @Test
    @DisplayName("Collisions at the 60-character boundary shrink the base so the suffix fits")
    void suffixedSlugsStayWithinTheColumn() {
        String full = "a".repeat(60);
        when(categories.existsByHouseholdIdAndSlug(eq(HOUSEHOLD), anyString())).thenReturn(false);
        when(categories.existsByHouseholdIdAndSlug(HOUSEHOLD, full)).thenReturn(true);
        when(categories.existsByHouseholdIdAndSlug(HOUSEHOLD, "a".repeat(58) + "-2"))
            .thenReturn(true);

        assertThat(createSlug("a".repeat(100))).isEqualTo("a".repeat(58) + "-3");
    }

    @Test
    @DisplayName("Short names keep the existing slug shape")
    void shortNamesAreUnchanged() {
        assertThat(createSlug("Side Projects")).isEqualTo("side-projects");
    }
}
