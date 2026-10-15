package finance.trove.auth.dto;

import jakarta.validation.constraints.Size;

/**
 * The re-proof a sensitive action asks for — the body of {@code POST /v1/me/export},
 * {@code DELETE /v1/me} and {@code POST /v1/me/delete} (collapse step 18; §1.5, gap P2-6).
 *
 * <p><strong>Neither field is {@code @NotBlank}, and that is the decision, not an oversight.</strong>
 * Which factor applies depends on what the account actually has, and Bean Validation cannot see
 * that: a member with a local password must send {@code password} (and {@code code} too where a
 * second factor is enrolled), while a member who signs in only through an identity provider has no
 * password to send and proves an enrolled {@code code} alone. Marking {@code password} required
 * would 400 the second member on a route that exists precisely so they can leave.
 * {@link finance.trove.auth.StepUpVerifier} makes the decision, and a missing factor is a 403
 * {@code step-up-required} with an explanation rather than a 400 about a field.
 *
 * <p>The {@code @Size} bounds are cheap refusals of a body nobody legitimately sends, and they
 * match the contract's own {@code minLength}s and {@code maxLength}s exactly. {@code min = 1}
 * rather than {@code @NotBlank}: an ABSENT field is legitimate — it is how the account says it
 * has no such factor — while an explicitly EMPTY one is a body the schema does not describe, and
 * a server that accepted it would be looser than the contract it publishes. An empty string would
 * otherwise reach the verifier and come back as a 403 about credentials, when the honest answer
 * is a 400 about the request.
 */
public record StepUpRequest(
    @Size(min = 1, max = 1024) String password,
    @Size(min = 1, max = 32) String code) {
}
