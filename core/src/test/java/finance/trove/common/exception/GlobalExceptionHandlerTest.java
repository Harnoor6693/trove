package finance.trove.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.observability.RequestIdMdcFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every handler of {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.3, exercised directly.
 *
 * <p>§5.3 asks for a {@code @SpringBootTest} rather than a slice. The end-to-end half of that is
 * {@code CommonFoundationIT}, which proves the real chain emits this exact envelope for the one
 * failure an unauthenticated caller can reach at collapse step 2 (401). The rest is asserted here
 * because every other handler needs an authenticated principal or a controller that does not
 * exist yet — collapse step 3 brings both, and this class is what stops the handlers rotting in
 * the meantime.
 */
class GlobalExceptionHandlerTest {

    private static final String REQUEST_ID = "11111111-2222-3333-4444-555555555555";

    private final ErrorResponseFactory factory = new ErrorResponseFactory();
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(factory);
    private final MockHttpServletRequest request = requestWithContextPath();

    private static MockHttpServletRequest requestWithContextPath() {
        MockHttpServletRequest request =
            new MockHttpServletRequest("POST", "/api/v1/transactions");
        request.setContextPath("/api");
        return request;
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("domain exceptions keep their own status and slug")
    class DomainExceptions {

        @Test
        void notFoundIs404() {
            // A controller advice runs after a handler was matched, so the template is on the
            // request — which is what `instance` reports. It is the route, never the URI: two
            // paths on this application carry a live credential in a path segment, and an error
            // body is the part of a response that ends up pasted into consoles and bug reports.
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/v1/transactions");
            ErrorResponse error = errorOf(handler.handleTrove(
                ResourceNotFoundException.of("Budget", "abc"), request), HttpStatus.NOT_FOUND);

            assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "not-found");
            assertThat(error.title()).isEqualTo("Not Found");
            assertThat(error.detail()).isEqualTo("Budget not found: abc");
            assertThat(error.instance()).isEqualTo("/api/v1/transactions");
        }

        @Test
        @DisplayName("with no matched route the last segment is redacted, never echoed")
        void anUnmatchedPathIsRedacted() {
            MockHttpServletRequest preview = new MockHttpServletRequest(
                "GET", "/api/v1/auth/invites/a-live-invite-token");
            preview.setContextPath("/api");

            ErrorResponse error = errorOf(handler.handleTrove(
                ResourceNotFoundException.of("Invite", "abc"), preview), HttpStatus.NOT_FOUND);

            assertThat(error.instance())
                .isEqualTo("/api/v1/auth/invites/*")
                .doesNotContain("a-live-invite-token");
        }

        @Test
        void everySubclassMapsToItsDeclaredStatus() {
            assertThat(statusOf(new ValidationException("x"))).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(statusOf(new UnauthorizedException("x"))).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(statusOf(new ForbiddenException("x"))).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusOf(new ConflictException("x"))).isEqualTo(HttpStatus.CONFLICT);
            assertThat(statusOf(new RateLimitException("x")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(statusOf(new ServiceUnavailableException("x")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("a retry-aware exception answers with the Retry-After the spec promises")
        void aRetryAwareExceptionCarriesTheHeader() {
            ResponseEntity<ApiResponse<Void>> response =
                handler.handleTrove(new RetryableFailure(), request);

            assertThat(response.getHeaders().getFirst("Retry-After"))
                .as("openapi/trove-core.yaml promises Retry-After on every 429 this API answers "
                    + "— LoginThrottled says so in as many words — and until this branch existed "
                    + "only RateLimitFilter, which writes its own response, ever set one. The "
                    + "brute-force gate's 429 came out of here bare, so a client backing off by "
                    + "the documented header had nothing to read")
                .isEqualTo("42");
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(errorOf(response, HttpStatus.TOO_MANY_REQUESTS).type())
                .isEqualTo(ErrorResponse.TYPE_BASE_URI + "account-locked");
        }

        @Test
        @DisplayName("an ordinary domain exception sets no Retry-After at all")
        void anOrdinaryExceptionSetsNoHeader() {
            assertThat(handler.handleTrove(new ConflictException("nope"), request)
                .getHeaders().getFirst("Retry-After"))
                .as("a Retry-After on a response that cannot usefully be retried is worse than "
                    + "no header")
                .isNull();
        }

        @Test
        @DisplayName("a 5xx domain exception is logged, and its message still reaches the caller")
        void serverSideDomainExceptionsAreLogged() {
            ErrorResponse error = errorOf(
                handler.handleTrove(new ServiceUnavailableException("bridge unreachable"), request),
                HttpStatus.SERVICE_UNAVAILABLE);

            assertThat(error.detail()).isEqualTo("bridge unreachable");
        }

        @Test
        void aCauseIsCarriedWithoutReachingTheWire() {
            ConflictException withCause =
                new ConflictException("duplicate", new IllegalStateException("unique_violation"));

            ErrorResponse error =
                errorOf(handler.handleTrove(withCause, request), HttpStatus.CONFLICT);

            assertThat(error.detail()).isEqualTo("duplicate").doesNotContain("unique_violation");
            assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
        }

        private HttpStatus statusOf(TroveException exception) {
            return HttpStatus.valueOf(
                handler.handleTrove(exception, request).getStatusCode().value());
        }
    }

    @Nested
    @DisplayName("validation failures become 400 with fieldErrors")
    class BeanValidationFailures {

        @Test
        void bodyValidationListsEveryRejectedField() throws Exception {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "body");
            binding.rejectValue("name", "NotBlank", "must not be blank");
            binding.rejectValue("amount", "Min", "must be at least 1");
            MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("handle", Payload.class), 0),
                binding);

            ErrorResponse error = errorOf(handler.handleBodyValidation(exception, request),
                HttpStatus.BAD_REQUEST);

            assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "validation-error");
            assertThat(error.fieldErrors())
                .containsExactlyInAnyOrder(
                    new ErrorResponse.FieldError("name", "must not be blank"),
                    new ErrorResponse.FieldError("amount", "must be at least 1"));
        }

        @Test
        @DisplayName("a constraint violation reports the leaf property, not the whole method path")
        void constraintViolationsReportTheLeafProperty() {
            Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
            Set<jakarta.validation.ConstraintViolation<Payload>> violations =
                validator.validate(new Payload());

            ErrorResponse error = errorOf(handler.handleConstraintViolation(
                new ConstraintViolationException(violations), request), HttpStatus.BAD_REQUEST);

            assertThat(error.fieldErrors()).isNotEmpty()
                .allSatisfy(fieldError -> assertThat(fieldError.field()).doesNotContain("."));
        }

        @Test
        void aMissingQueryParameterIsAFieldError() {
            ErrorResponse error = errorOf(handler.handleMissingParameter(
                new MissingServletRequestParameterException("month", "String"), request),
                HttpStatus.BAD_REQUEST);

            assertThat(error.fieldErrors())
                .containsExactly(new ErrorResponse.FieldError("month", "is required"));
        }

        @Test
        void aTypeMismatchIsAFieldError() {
            MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "abc", UUID.class, "id", null, new IllegalArgumentException("bad uuid"));

            ErrorResponse error =
                errorOf(handler.handleTypeMismatch(exception, request), HttpStatus.BAD_REQUEST);

            assertThat(error.fieldErrors())
                .containsExactly(new ErrorResponse.FieldError("id", "is not a valid value"));
        }

        @Test
        @DisplayName("a blank Bean Validation message still names the field as invalid")
        void aBlankMessageFallsBackToIsInvalid() throws Exception {
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Payload(), "body");
            binding.rejectValue("name", "Custom", "");
            MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new org.springframework.core.MethodParameter(
                    GlobalExceptionHandlerTest.class.getDeclaredMethod("handle", Payload.class), 0),
                binding);

            ErrorResponse error = errorOf(handler.handleBodyValidation(exception, request),
                HttpStatus.BAD_REQUEST);

            assertThat(error.fieldErrors())
                .containsExactly(new ErrorResponse.FieldError("name", "is invalid"));
        }
    }

    @Nested
    @DisplayName("framework failures")
    class Framework {

        @Test
        @DisplayName("a JSON parse error never echoes the body — it would contain the password")
        void malformedJsonDoesNotEchoTheBody() {
            HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Unexpected character in {\"password\":\"hunter2\"", (org.springframework.http.HttpInputMessage) null);

            ErrorResponse error =
                errorOf(handler.handleNotReadable(exception, request), HttpStatus.BAD_REQUEST);

            assertThat(error.detail()).doesNotContain("hunter2")
                .isEqualTo("The request body could not be parsed as JSON.");
            assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "malformed-request");
        }

        @Test
        void anOversizedUploadIs413() {
            ErrorResponse error = errorOf(
                handler.handleUploadTooLarge(new MaxUploadSizeExceededException(20_971_520L),
                    request), HttpStatus.CONTENT_TOO_LARGE);

            assertThat(error.status()).isEqualTo(413);
            assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "upload-too-large");
        }

        @Test
        @DisplayName("a unique-constraint violation is the caller's 409, not a 500")
        void dataIntegrityIs409() {
            ErrorResponse error = errorOf(handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique_email"),
                request), HttpStatus.CONFLICT);

            assertThat(error.detail()).doesNotContain("unique_email");
            assertThat(error.type()).isEqualTo(ErrorResponse.TYPE_BASE_URI + "conflict");
        }

        @Test
        void optimisticLockingIs409WithARetryHint() {
            ErrorResponse error = errorOf(handler.handleOptimisticLock(
                new OptimisticLockingFailureException("version mismatch"), request),
                HttpStatus.CONFLICT);

            assertThat(error.detail()).contains("Reload and try again");
            assertThat(error.type())
                .isEqualTo(ErrorResponse.TYPE_BASE_URI + "concurrent-modification");
        }

        @Test
        void authenticationFailureIs401AndAccessDenialIs403() {
            ErrorResponse unauthorised = errorOf(
                handler.handleAuthentication(new BadCredentialsException("bad"), request),
                HttpStatus.UNAUTHORIZED);
            ErrorResponse forbidden = errorOf(
                handler.handleAccessDenied(new AccessDeniedException("nope"), request),
                HttpStatus.FORBIDDEN);

            assertThat(unauthorised.detail()).isEqualTo(TroveAuthenticationEntryPoint.DETAIL);
            assertThat(forbidden.detail()).isEqualTo(TroveAccessDeniedHandler.DETAIL);
            assertThat(forbidden.detail()).doesNotContain("nope");
        }

        @Test
        void responseStatusExceptionKeepsItsOwnStatusAndReason() {
            ErrorResponse error = errorOf(handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "send JSON"),
                request), HttpStatus.UNSUPPORTED_MEDIA_TYPE);

            assertThat(error.detail()).isEqualTo("send JSON");
            assertThat(error.type())
                .isEqualTo(ErrorResponse.TYPE_BASE_URI + "unsupported-media-type");
        }

        @Test
        @DisplayName("a 5xx ResponseStatusException loses its reason — it may quote internals")
        void serverSideResponseStatusExceptionIsGenericised() {
            ErrorResponse error = errorOf(handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream 10.0.0.5 refused"),
                request), HttpStatus.BAD_GATEWAY);

            assertThat(error.detail()).isEqualTo(ErrorResponseFactory.INTERNAL_DETAIL)
                .doesNotContain("10.0.0.5");
        }

        @Test
        void anUnresolvableStatusCodeBecomes500() {
            ErrorResponse error = errorOf(handler.handleResponseStatus(
                new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(599)),
                request), HttpStatus.INTERNAL_SERVER_ERROR);

            assertThat(error.detail()).isEqualTo(ErrorResponseFactory.INTERNAL_DETAIL);
        }

        @Test
        void unknownPathsAndWrongVerbs() {
            ErrorResponse notFound = errorOf(handler.handleNoResource(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api",
                    "/v1/nope"),
                request), HttpStatus.NOT_FOUND);
            ErrorResponse wrongVerb = errorOf(handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"), request),
                HttpStatus.METHOD_NOT_ALLOWED);

            assertThat(notFound.detail())
                .as("nothing matched, so there is no template and the caller's own last segment "
                    + "is not echoed back at them — the path here is attacker-controlled by "
                    + "definition")
                .contains("/api/v1/*")
                .doesNotContain("transactions");
            assertThat(wrongVerb.detail()).contains("DELETE");
        }
    }

    @Nested
    @DisplayName("the catch-all")
    class CatchAll {

        @Test
        @DisplayName("a 500 body carries no class name, no message and no stack trace")
        void unexpectedFailuresLeakNothing() {
            RuntimeException cause =
                new IllegalStateException("jdbc:postgresql://db:5432/trove password=hunter2");

            ErrorResponse error =
                errorOf(handler.handleUnexpected(cause, request), HttpStatus.INTERNAL_SERVER_ERROR);

            assertThat(error.detail()).isEqualTo(ErrorResponseFactory.INTERNAL_DETAIL);
            assertThat(error.toString())
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at finance.trove");
        }
    }

    @Nested
    @DisplayName("request correlation")
    class Correlation {

        @Test
        @DisplayName("the requestId in the body is the one in the MDC and the X-Request-Id header")
        void requestIdComesFromTheMdc() {
            MDC.put(RequestIdMdcFilter.MDC_REQUEST_ID, REQUEST_ID);

            ErrorResponse error = errorOf(
                handler.handleTrove(new ConflictException("x"), request), HttpStatus.CONFLICT);

            assertThat(error.requestId()).isEqualTo(REQUEST_ID);
        }

        @Test
        @DisplayName("after the MDC is cleared — an ERROR dispatch — the request attribute is used")
        void fallsBackToTheRequestAttribute() {
            request.setAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);

            assertThat(factory.requestId(request)).isEqualTo(REQUEST_ID);
        }

        @Test
        void isNullWhenNeitherIsPresent() {
            assertThat(factory.requestId(request)).isNull();
            assertThat(factory.requestId(null)).isNull();
            assertThat(factory.build(HttpStatus.CONFLICT, "conflict", "x", null).instance())
                .isNull();
        }

        @Test
        void theEnvelopeHelperProducesTheSameBody() {
            ApiResponse<Void> envelope =
                factory.envelope(HttpStatus.FORBIDDEN, "forbidden", "no", request);

            assertThat(envelope.success()).isFalse();
            assertThat(envelope.error().status()).isEqualTo(403);
            assertThat(envelope.error().detail()).isEqualTo("no");
        }

        @Test
        @DisplayName("a blank MDC value is treated as absent, not echoed as an empty requestId")
        void blankMdcValuesAreIgnored() {
            MDC.put(RequestIdMdcFilter.MDC_REQUEST_ID, "  ");
            request.setAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID);

            assertThat(factory.requestId(request)).isEqualTo(REQUEST_ID);
        }
    }

    private static ErrorResponse errorOf(ResponseEntity<ApiResponse<Void>> response,
                                         HttpStatus expectedStatus) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.data()).isNull();
        assertThat(body.error()).isNotNull();
        assertThat(body.error().status()).isEqualTo(expectedStatus.value());
        return body.error();
    }

    /** A domain exception that knows how long the caller should wait; see RetryAfterAware. */
    private static final class RetryableFailure extends TroveException implements RetryAfterAware {
        private static final long serialVersionUID = 1L;

        private RetryableFailure() {
            super("Too many sign-in attempts from this network. Try again later.",
                HttpStatus.TOO_MANY_REQUESTS, "account-locked");
        }

        @Override
        public long getRetryAfterSeconds() {
            return 42L;
        }
    }

    @SuppressWarnings("unused")
    private void handle(Payload payload) {
        // Exists only so MethodParameter has a real method to point at.
        new DataBinder(payload).bind(new MutablePropertyValues(List.of()));
    }

    /** A request DTO with two constraints, standing in for one that does not exist yet. */
    static final class Payload {
        @NotBlank
        private final String name = "";
        @Min(1)
        private final int amount = 0;

        public String getName() {
            return name;
        }

        public int getAmount() {
            return amount;
        }
    }
}
