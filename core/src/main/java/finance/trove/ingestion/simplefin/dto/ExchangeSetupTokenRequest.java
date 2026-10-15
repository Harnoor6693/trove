package finance.trove.ingestion.simplefin.dto;

import finance.trove.common.plugin.SourceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /v1/accounts/exchange-setup-token}.
 *
 * <p>{@code sourceKind} replaces the pre-collapse {@code pluginId} free-text field: a typo in that
 * string produced "PLUGIN_NOT_SUPPORTED" and nothing a member could act on. Only
 * {@link SourceKind#SIMPLEFIN} issues setup tokens; the field exists so a second bridge can be
 * added without a second route.
 *
 * <p>{@link #toString()} is overridden. This record is a request body, and a request body is
 * exactly the thing a framework prints when it cannot bind one — {@code setupToken} is a one-time
 * credential and must not travel into a log that way.
 *
 * @param sourceKind must be {@code SIMPLEFIN}
 * @param setupToken the base64 token from the member's bridge, decoding to a one-time claim URL
 */
public record ExchangeSetupTokenRequest(@NotNull SourceKind sourceKind,
                                        @NotBlank @Size(max = 4096) String setupToken) {

    @Override
    public String toString() {
        return "ExchangeSetupTokenRequest[sourceKind=" + sourceKind + ", setupToken=<redacted>]";
    }
}
