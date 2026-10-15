package finance.trove.receipts.receipt.dto;

import finance.trove.receipts.receipt.LineItem;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a receipt, inside {@link ReceiptResponse} (§4.10, §5 item 4 — camelCase).
 *
 * <p>Every {@link BigDecimal} leaves as a decimal string, through the application's global
 * serialiser: a client's float type cannot round a figure it never sees as a number.
 */
public record ReceiptLineItemResponse(UUID id, String description, BigDecimal quantity,
                                      BigDecimal unitPrice, BigDecimal totalPrice,
                                      boolean taxable) {

    public static ReceiptLineItemResponse of(LineItem item) {
        return new ReceiptLineItemResponse(item.getId(), item.getDescription(), item.getQuantity(),
            item.getUnitPrice(), item.getTotalPrice(), item.isTaxable());
    }
}
