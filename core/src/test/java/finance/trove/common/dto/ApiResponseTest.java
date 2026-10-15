package finance.trove.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.domain.PagedResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void okCarriesDataAndNothingElse() {
        ApiResponse<String> response = ApiResponse.ok("payload");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("payload");
        assertThat(response.error()).isNull();
        assertThat(response.meta()).isNull();
    }

    @Test
    void pagedCarriesMetadata() {
        PaginationMeta meta = PaginationMeta.of(42, 1, 20);
        ApiResponse<List<String>> response = ApiResponse.paged(List.of("a"), meta);

        assertThat(response.success()).isTrue();
        assertThat(response.meta()).isEqualTo(meta);
        assertThat(response.error()).isNull();
    }

    @Test
    @DisplayName("an error response can never also carry data")
    void errorNeverCarriesData() {
        ErrorResponse error = ErrorResponse.builder(404).title("Not Found").build();
        ApiResponse<String> response = ApiResponse.error(error);

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error()).isEqualTo(error);
    }

    @Test
    void factoriesRejectTheirRequiredArgument() {
        assertThatThrownBy(() -> ApiResponse.paged(List.of(), null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("meta");
        assertThatThrownBy(() -> ApiResponse.error(null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("error");
    }

    @Test
    void paginationMetaDerivesFromAPagedResult() {
        PagedResult<String> page = new PagedResult<>(List.of("a", "b"), 7, 1, 2);
        assertThat(PaginationMeta.from(page)).isEqualTo(new PaginationMeta(7, 1, 2));
    }
}
