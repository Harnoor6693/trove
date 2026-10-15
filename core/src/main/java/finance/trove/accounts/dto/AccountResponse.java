package finance.trove.accounts.dto;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.SyncStatus;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as every route in this package returns it.
 *
 * <p><strong>There is no {@code configEncrypted} component.</strong> Not one that is nulled out
 * on the way through, not one a mapper remembers to skip — the type has no such field, so no
 * future edit to this class can leak a household's bank credentials into a response by accident.
 * {@code externalAccountId} is likewise absent: it is the provider's own identifier, it is only
 * ever used server-side as part of the upsert key, and no client has a use for it.
 *
 * @param createdBy the member who added the account, for audit; {@code null} once that member's
 *                  account is deleted. It is never the scope — {@code householdId} is
 */
public record AccountResponse(UUID id, UUID householdId, UUID createdBy, SourceKind sourceKind,
                              String displayName, String institution, AccountType accountType,
                              CurrencyCode currency, SyncStatus syncStatus, Instant lastSyncedAt,
                              String errorMessage, Instant createdAt, Instant updatedAt) {

    public static AccountResponse of(ConnectedAccount account) {
        return new AccountResponse(account.getId(), account.getHouseholdId(), account.getUserId(),
            account.getSourceKind(), account.getDisplayName(), account.getInstitution(),
            account.getAccountType(), account.getCurrency(), account.getSyncStatus(),
            account.getLastSyncedAt(), account.getErrorMessage(), account.getCreatedAt(),
            account.getUpdatedAt());
    }
}
