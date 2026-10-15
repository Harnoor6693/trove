package finance.trove.ai.dto;

import finance.trove.ai.PrivacyTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /v1/ai/settings} — a <strong>replace</strong>, not a patch.
 *
 * <p>{@code /v1/me/preferences} is a patch because its fields are written by different onboarding
 * screens at different times. This resource is one form saved as a unit, and a replace is both
 * what PUT means and what makes clearing an endpoint or a vision model possible without the
 * absent-versus-null machinery that {@code PreferencesUpdateRequest} has to carry.
 *
 * <p>{@code apiKey} is the one field that cannot follow that rule, and it has to be exempt rather
 * than merely convenient: it is write-only, so it is not in the GET this form was populated from,
 * so the client has nothing to send back. Absent or null therefore means "keep what is stored";
 * a non-blank value replaces it. Blank is refused rather than treated as a clear, because clearing
 * has its own route ({@code DELETE /v1/ai/settings/api-key}) and a whitespace key that silently
 * wiped a working one would be a nasty surprise on a form that trims nothing.
 *
 * <p>Cross-field rules — {@code enabled} requiring an endpoint and a model, and the endpoint
 * passing the egress policy — are enforced in {@link finance.trove.ai.AiSettingsService} rather
 * than here, because both need to produce a 400 whose {@code fieldErrors} name the field the user
 * has to fix.
 *
 * @param enabled              required; turning it on requires an endpoint and a model
 * @param endpointUrl          absolute http/https URL, or null to clear
 * @param apiKey               write-only; null keeps the stored key
 * @param model                chat model id, or null to clear; blank is refused
 * @param visionModel          receipt-image model id, or null to clear; blank is refused
 * @param allowPrivateEndpoint required; this member's half of the private-target opt-in
 * @param privacyTier          required
 * @param connectTimeoutMs     null keeps the current value
 * @param readTimeoutMs        null keeps the current value
 */
public record AiSettingsUpdateRequest(

    @NotNull
    Boolean enabled,

    @Size(max = 2000)
    String endpointUrl,

    @Size(max = 500)
    String apiKey,

    @Size(max = 200)
    String model,

    @Size(max = 200)
    String visionModel,

    @NotNull
    Boolean allowPrivateEndpoint,

    @NotNull
    PrivacyTier privacyTier,

    @Min(100)
    @Max(60_000)
    Integer connectTimeoutMs,

    @Min(1_000)
    @Max(600_000)
    Integer readTimeoutMs) {
}
