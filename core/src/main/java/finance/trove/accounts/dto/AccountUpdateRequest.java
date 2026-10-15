package finance.trove.accounts.dto;

import finance.trove.accounts.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /v1/accounts/{id}} body.
 *
 * <p>{@code sourceKind} and {@code currency} are deliberately absent. The first decides how the
 * account is synced and which source may write to it; the second is the denomination every
 * transaction already stored against the account was written in. Letting a rename form change
 * either would silently reinterpret money that is already in the ledger, and there is no
 * conversion anywhere in this product to reinterpret it correctly.
 */
public record AccountUpdateRequest(@NotBlank @Size(max = 200) String displayName,
                                   @Size(max = 200) String institution,
                                   @NotNull AccountType accountType) {
}
