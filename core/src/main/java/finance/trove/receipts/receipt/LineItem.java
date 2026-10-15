package finance.trove.receipts.receipt;

import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a receipt ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V37-row, from the source's
 * {@code line_item} table).
 *
 * <p>Written once by the scoring stage, when the composite confidence cleared its threshold, and
 * never updated in place — a correction replaces the set rather than editing a row, so a line item
 * always reflects one extraction rather than a mixture of two.
 *
 * <p>Like {@link ReceiptImage} it has no repository: it is reached through its {@link Receipt},
 * which owns the collection — and, for the same reason, {@code receipt_id} is not a field here
 * either. The parent's {@code @JoinColumn} owns that column; mapping it twice is a Hibernate
 * "column is duplicated in mapping" failure at boot.
 *
 * <p>Every amount is {@code NUMERIC(19,4)}, unlike the source's {@code (8,2)}/{@code (10,2)}
 * mixture, because {@code SchemaDefaultsIT} scans the whole database for numerics that are not.
 */
@Entity
@Table(name = "line_item")
public class LineItem {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "total_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalPrice = BigDecimal.ZERO;

    @Column(name = "is_taxable", nullable = false, updatable = false)
    private boolean taxable = true;

    /** Set here, not left to the column default: see {@link Receipt#getCreatedAt()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LineItem() {
        // JPA
    }

    public LineItem(String description, BigDecimal quantity, BigDecimal unitPrice,
                    BigDecimal totalPrice, boolean taxable) {
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalPrice = totalPrice;
        this.taxable = taxable;
        this.createdAt = Instants.now();
    }

    public UUID getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public boolean isTaxable() {
        return taxable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
