package finance.trove.common.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ErrorResponseTest {

    @Test
    @DisplayName("the type is namespaced under the error catalogue URI")
    void builderNamespacesTheTypeSlug() {
        ErrorResponse error = ErrorResponse.builder(404)
            .type("not-found")
            .title("Not Found")
            .detail("Budget not found: 1")
            .instance("/api/v1/budgets/1")
            .requestId("11111111-1111-1111-1111-111111111111")
            .build();

        assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "not-found");
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.title()).isEqualTo("Not Found");
        assertThat(error.detail()).isEqualTo("Budget not found: 1");
        assertThat(error.instance()).isEqualTo("/api/v1/budgets/1");
        assertThat(error.requestId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(error.fieldErrors()).isNull();
    }

    @Test
    @DisplayName("an empty fieldErrors list is normalised to null so it is omitted from the JSON")
    void emptyFieldErrorsBecomeNull() {
        assertThat(ErrorResponse.builder(400).fieldErrors(List.of()).build().fieldErrors()).isNull();
        assertThat(ErrorResponse.builder(400).fieldErrors(null).build().fieldErrors()).isNull();
    }

    @Test
    void fieldErrorsAreCopiedAndImmutable() {
        List<ErrorResponse.FieldError> source =
            new ArrayList<>(List.of(new ErrorResponse.FieldError("amount", "must be positive")));
        ErrorResponse error = ErrorResponse.builder(400).fieldErrors(source).build();
        source.clear();

        assertThat(error.fieldErrors()).hasSize(1);
        assertThatThrownBy(() -> error.fieldErrors().add(null))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aFieldErrorMustNameAField() {
        assertThatThrownBy(() -> new ErrorResponse.FieldError(null, "is invalid"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the default type is the 500 slug, so an unset builder cannot emit a bare null")
    void defaultTypeIsInternalServerError() {
        assertThat(ErrorResponse.builder(500).build().type())
            .isEqualTo(ErrorResponse.TYPE_BASE_URI + "internal-server-error");
    }
}
