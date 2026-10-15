package finance.trove.categorisation;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds {@link Category} instances with an id, for the unit tests of this package.
 *
 * <p>{@link Category#getId()} is JPA-generated, so a plain unit test that never touches a
 * database has to set it by hand. Kept in one place because three test classes need it and three
 * copies of a reflective field write is three places to get it wrong.
 */
final class CategoryFixture {

    private CategoryFixture() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** A system category — {@code householdId == null} is what "system" means. */
    static Category system(UUID id, String slug, String name) {
        return category(id, null, slug, name);
    }

    /** One household's own category. */
    static Category owned(UUID id, UUID householdId, String slug, String name) {
        return category(id, householdId, slug, name);
    }

    static Category category(UUID id, UUID householdId, String slug, String name) {
        Category category =
            new Category(householdId, slug, name, null, null, null, Instant.now());
        try {
            var field = Category.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return category;
    }
}
