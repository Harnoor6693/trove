package finance.trove.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The hierarchy itself: each subclass pins a status and a slug, and each keeps its cause so the
 * log has the real failure while the wire body does not
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.3).
 */
class TroveExceptionTest {

    @Test
    @DisplayName("every subclass carries its own status and slug, with or without a cause")
    void everySubclassPinsAStatusAndASlug() {
        record Case(String slug, HttpStatus status,
                    BiFunction<String, Throwable, TroveException> withCause) {
        }

        List<Case> cases = List.of(
            new Case("not-found", HttpStatus.NOT_FOUND, ResourceNotFoundException::new),
            new Case("validation-error", HttpStatus.BAD_REQUEST, ValidationException::new),
            new Case("unauthorized", HttpStatus.UNAUTHORIZED, UnauthorizedException::new),
            new Case("forbidden", HttpStatus.FORBIDDEN, ForbiddenException::new),
            new Case("conflict", HttpStatus.CONFLICT, ConflictException::new),
            new Case("rate-limited", HttpStatus.TOO_MANY_REQUESTS, RateLimitException::new),
            new Case("service-unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                ServiceUnavailableException::new));

        Throwable cause = new IllegalStateException("underlying");
        for (Case testCase : cases) {
            TroveException exception = testCase.withCause().apply("boom", cause);

            assertThat(exception.getStatus()).isEqualTo(testCase.status());
            assertThat(exception.getSlug()).isEqualTo(testCase.slug());
            assertThat(exception.getMessage()).isEqualTo("boom");
            assertThat(exception.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("a bare TroveException derives its slug from the status reason phrase")
    void theDefaultSlugComesFromTheReasonPhrase() {
        assertThat(new TroveException("x", HttpStatus.NOT_FOUND).getSlug()).isEqualTo("not-found");
        assertThat(new TroveException("x", HttpStatus.CONFLICT).getSlug()).isEqualTo("conflict");
        assertThat(new TroveException("x", HttpStatus.INTERNAL_SERVER_ERROR).getSlug())
            .isEqualTo("internal-server-error");
        assertThat(new TroveException("x", HttpStatus.SERVICE_UNAVAILABLE).getSlug())
            .isEqualTo("service-unavailable");
    }

    @Test
    void theResourceFactoryComposesTheStandardMessage() {
        assertThat(ResourceNotFoundException.of("Budget", 42).getMessage())
            .isEqualTo("Budget not found: 42");
    }
}
