package finance.trove.accounts.dto;

import finance.trove.accounts.AccountType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/accounts} body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3).
 *
 * <p>It carries no {@code id}, no {@code householdId} and no credentials. The household comes
 * from the signed token; the id is the server's; and a source's credentials are exchanged by
 * their own flow (step 7) rather than posted to a general-purpose create endpoint. That is the
 * same G3 lesson as {@code CategoryRequest}: the old service bound the JPA entity itself as the
 * request body.
 *
 * @param currency {@code null} means "the creating member's own {@code users.currency}". There is
 *                 no literal default anywhere — §9 risk 5
 */
public record AccountRequest(@NotNull SourceKind sourceKind,
                             @NotBlank @Size(max = 200) String displayName,
                             @Size(max = 200) String institution,
                             @NotNull AccountType accountType,
                             CurrencyCode currency) {
}
