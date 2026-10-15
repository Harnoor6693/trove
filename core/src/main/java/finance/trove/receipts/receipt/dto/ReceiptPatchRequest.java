package finance.trove.receipts.receipt.dto;

import finance.trove.common.domain.CurrencyCode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Manual fields for a receipt — {@code PATCH /v1/receipts/{id}} (§4.10).
 *
 * <p>On an install with no vision model, which is every install by default, this is the <em>only</em>
 * way a receipt's fields are ever filled in, and that is the decided degradation rather than a
 * fallback nobody meant (owner decision 2026-09-03).
 *
 * <p>Every property is optional and an absent one is left alone. There is deliberately no way to
 * clear a field back to null: a PATCH in which "absent" and "null" mean different things is a
 * contract clients get wrong, and a member who wants a field gone has the delete route.
 * {@link #isEmpty()} is what makes a body with nothing in it a 400 rather than a silent no-op.
 *
 * <p>No field named {@code userId} or {@code householdId} — {@code ArchitectureTest} bans an
 * identity on a request DTO, and the caller's identity comes from signed claims.
 */
public record ReceiptPatchRequest(
    @Size(min = 1, max = 200) String extractedMerchant,
    @DecimalMin("0.0") @DecimalMax("1000000000") @Digits(integer = 15, fraction = 4)
    BigDecimal extractedAmount,
    CurrencyCode extractedCurrency,
    LocalDate extractedDate,
    UUID matchedTransactionId) {

    /** Whether the body asked for nothing at all, which the controller answers 400 for. */
    public boolean isEmpty() {
        return extractedMerchant == null && extractedAmount == null && extractedCurrency == null
            && extractedDate == null && matchedTransactionId == null;
    }

    /** Whether the merchant is present but all whitespace, which {@code @Size} cannot express. */
    public boolean hasBlankMerchant() {
        return extractedMerchant != null && extractedMerchant.isBlank();
    }
}
