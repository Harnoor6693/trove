package finance.trove.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step-up body against the schema that describes it.
 *
 * <p>The contract declares {@code minLength: 1} on both fields and {@code maxLength} 1024 and 32.
 * A record that carried only the maxima would describe a stricter document than the server it
 * ships with — an explicitly empty string would sail past Bean Validation and come back as a 403
 * about credentials rather than the 400 about the request the schema promises. These tests are
 * the only thing that keeps the two halves in step, because the conformance validator checks what
 * a test actually sends and no client sends an empty password on purpose.
 */
class StepUpRequestTest {

    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("An explicitly empty password is a violation, not a wrong password")
    void anEmptyPasswordIsRejected() {
        assertThat(validator.validate(new StepUpRequest("", null)))
            .as("minLength: 1 in the contract has to be minLength: 1 in the server")
            .isNotEmpty();
    }

    @Test
    @DisplayName("An explicitly empty code is a violation, not a wrong code")
    void anEmptyCodeIsRejected() {
        assertThat(validator.validate(new StepUpRequest("a-password", "")))
            .isNotEmpty();
    }

    @Test
    @DisplayName("An ABSENT field is legitimate: it is how an account says it has no such factor")
    void absentFieldsAreAccepted() {
        assertThat(validator.validate(new StepUpRequest("a-password", null)))
            .as("a member with a password and no second factor")
            .isEmpty();
        assertThat(validator.validate(new StepUpRequest(null, "123456")))
            .as("a member who signs in only through an identity provider has no password to send")
            .isEmpty();
    }

    @Test
    @DisplayName("The maxima match the contract's maxLengths")
    void theMaximaAreEnforced() {
        assertThat(validator.validate(new StepUpRequest("x".repeat(1025), null))).isNotEmpty();
        assertThat(validator.validate(new StepUpRequest("x".repeat(1024), null))).isEmpty();
        assertThat(validator.validate(new StepUpRequest(null, "0".repeat(33)))).isNotEmpty();
        assertThat(validator.validate(new StepUpRequest(null, "0".repeat(32)))).isEmpty();
    }
}
