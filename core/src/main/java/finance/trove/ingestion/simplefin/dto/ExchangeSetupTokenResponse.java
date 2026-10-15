package finance.trove.ingestion.simplefin.dto;

import finance.trove.ingestion.dto.SyncLogResponse;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /v1/accounts/exchange-setup-token}.
 *
 * <p><strong>The field is called {@code accountIds} and stays called that</strong>: the web
 * client's Onboarding screen reads {@code mutation.data.accountIds.length} to tell the member how
 * many accounts were connected ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3/§4.13).
 *
 * <p>Neither the access URL nor the setup token appears here, and there is no field they could
 * appear in. That is the same rule {@code AccountResponse} follows for {@code config_encrypted}:
 * not a field that gets stripped, no field at all.
 *
 * @param accountIds one id per bridge account, created or reused. A re-exchange after a token
 *                   rotation returns the SAME ids, which is the whole point of the upsert
 * @param syncLogs   the initial sync run for each of those accounts, in the same order. A run that
 *                   could not reach the bridge is a {@code FAILED} log here rather than an HTTP
 *                   error: the accounts were still connected
 */
public record ExchangeSetupTokenResponse(List<UUID> accountIds, List<SyncLogResponse> syncLogs) {

    public ExchangeSetupTokenResponse {
        accountIds = List.copyOf(accountIds);
        syncLogs = List.copyOf(syncLogs);
    }
}
