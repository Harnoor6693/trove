package finance.trove.auth.dto;

import java.util.List;

/**
 * The ten single-use recovery codes, shown once and never again
 * ({@code apps/mobile/src/api/types.ts:198}: {@code {recoveryCodes: string[]}}).
 *
 * <p>There is deliberately no "show my codes again" endpoint. Only SHA-256 hashes are stored, so
 * the server could not produce them if it wanted to — and a code the server can re-display is a
 * password it is keeping in readable form.
 */
public record TotpConfirmResponse(List<String> recoveryCodes) {

    public TotpConfirmResponse {
        recoveryCodes = List.copyOf(recoveryCodes);
    }
}
