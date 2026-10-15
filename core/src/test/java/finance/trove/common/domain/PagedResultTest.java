package finance.trove.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PagedResultTest {

    @Test
    @DisplayName("the content list is copied, so a later mutation of the caller's list is invisible")
    void contentIsDefensivelyCopied() {
        List<String> source = new ArrayList<>(List.of("a", "b"));
        PagedResult<String> page = new PagedResult<>(source, 2, 0, 20);
        source.add("c");

        assertThat(page.content()).containsExactly("a", "b");
        assertThatThrownBy(() -> page.content().add("d"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void validatesItsBounds() {
        assertThatThrownBy(() -> new PagedResult<>(null, 0, 0, 20))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PagedResult<>(List.of(), 0, -1, 20))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> new PagedResult<>(List.of(), 0, 0, 0))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> new PagedResult<>(List.of(), -1, 0, 20))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("totalElements");
    }

    @Test
    void derivesPaginationWithoutASecondCountQuery() {
        PagedResult<String> firstOfThree = new PagedResult<>(List.of("a", "b"), 5, 0, 2);
        assertThat(firstOfThree.totalPages()).isEqualTo(3);
        assertThat(firstOfThree.hasNext()).isTrue();
        assertThat(firstOfThree.isEmpty()).isFalse();

        PagedResult<String> lastOfThree = new PagedResult<>(List.of("e"), 5, 2, 2);
        assertThat(lastOfThree.hasNext()).isFalse();
    }

    @Test
    void emptyPageIsEmpty() {
        PagedResult<String> empty = PagedResult.empty(0, 20);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.hasNext()).isFalse();
    }

    @Test
    @DisplayName("mapping to a DTO keeps the pagination metadata")
    void mapsContentKeepingMetadata() {
        PagedResult<Integer> lengths = new PagedResult<>(List.of("a", "bb"), 7, 1, 2)
            .map(String::length);

        assertThat(lengths.content()).containsExactly(1, 2);
        assertThat(lengths.totalElements()).isEqualTo(7);
        assertThat(lengths.page()).isEqualTo(1);
        assertThat(lengths.limit()).isEqualTo(2);
        assertThatThrownBy(() -> lengths.map(null)).isInstanceOf(NullPointerException.class);
    }
}
