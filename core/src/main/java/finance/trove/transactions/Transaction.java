package finance.trove.transactions;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One transaction in the household's ledger — fetched from a source, imported from a file, or
 * entered by hand ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2, §3 V19, §4.4).
 *
 * <p><strong>{@link #getProviderTransactionId()} is the renamed {@code source_id}</strong>, and
 * the rename is the visible half of finding O7/M0. Every pre-collapse normaliser wrote the
 * plugin's NAME into that column — a constant, the same on every row — while the bank's real
 * per-transaction id went into an unread field. With the unique index on
 * {@code (account_id, source_id)} that meant at most one transaction per account could ever
 * exist. The column now says what it holds, and {@link finance.trove.common.plugin.RawTransaction}
 * has only the one identifier slot to put it in.
 *
 * <p><strong>{@link #getAmount()} is always positive; {@link #getType()} carries the
 * direction.</strong> Every source normalises onto that convention, so no consumer has to know an
 * institution's own sign rules, and the transfer-pairing job can look for a DEBIT and a CREDIT of
 * equal absolute value rather than for amounts that happen to sum to zero.
 *
 * <p><strong>{@link #isManual()}</strong> marks a hand-entered row (gap P2-13). It is what keeps a
 * sync or an import undo from overwriting or removing it, and it is the only kind of row
 * {@code DELETE /v1/transactions/{id}} will remove.
 *
 * <p><strong>{@link #getTransferGroupId()}</strong> is shared by both legs of an internal transfer
 * (gap P2-14), so every spend and income aggregate can exclude them instead of counting a
 * credit-card payment as spending on one account and income on the other.
 *
 * <p>Tenancy is {@link #getHouseholdId()}; {@link #getUserId()} means created-by, for audit.
 */
@Entity
// Only changed columns are written, so a background write of one column (transfer pairing,
// category write-back) cannot overwrite a concurrent edit of another (B1-21).
@org.hibernate.annotations.DynamicUpdate
@Table(name = "transaction")
public class Transaction {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "provider_transaction_id", length = 255, updatable = false)
    private String providerTransactionId;

    /** Updatable for {@link #markPosted} alone: a pre-authorisation posts at what was charged. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TransactionType type;

    @Column(name = "description")
    private String description;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "pending", nullable = false)
    private boolean pending;

    @Column(name = "manual", nullable = false, updatable = false)
    private boolean manual;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "transfer_group_id")
    private UUID transferGroupId;

    @Column(name = "import_batch_id", updatable = false)
    private UUID importBatchId;

    /**
     * The receipt attached to this transaction, or null (V38, collapse step 14).
     *
     * <p>The other half of {@code receipt.matched_transaction_id}. Exactly one class writes either
     * column — {@code finance.trove.receipts.receipt.ReceiptLinkService} — and it writes both in
     * one transaction, which is what keeps them from disagreeing. {@link #setReceiptId} exists for
     * that class alone; nothing else in the tree calls it.
     */
    @Column(name = "receipt_id")
    private UUID receiptId;

    /**
     * Free-form member tags, stored as {@code jsonb}.
     *
     * <p>The source column was {@code tags_json TEXT} — a string that only looked like data, never
     * validated on write and impossible to index. {@code jsonb} is checked by Postgres on every
     * write, so a malformed value fails at the insert instead of at the read.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags")
    private List<String> tags;

    @Column(name = "notes")
    private String notes;

    /** Set by the application, not by the column default — see {@code ConnectedAccount}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected Transaction() {
        // JPA
    }

    public Transaction(UUID householdId, UUID accountId, UUID userId,
                       String providerTransactionId, BigDecimal amount, CurrencyCode currency,
                       TransactionType type, String description, String merchantName,
                       LocalDate date, Instant postedAt, boolean pending, boolean manual,
                       Instant createdAt) {
        this(householdId, accountId, userId, providerTransactionId, amount, currency, type,
            description, merchantName, date, postedAt, pending, manual, null, createdAt);
    }

    /**
     * The same row, attributed to the import batch that inserted it (gap P2-12).
     *
     * <p>{@code import_batch_id} is a constructor parameter and not a setter because the column is
     * {@code updatable = false}: a row's provenance is a fact about how it got into the ledger, not
     * a field to be re-attributed afterwards. Re-attribution is what would let an undo of one batch
     * remove a row an earlier batch created.
     */
    public Transaction(UUID householdId, UUID accountId, UUID userId,
                       String providerTransactionId, BigDecimal amount, CurrencyCode currency,
                       TransactionType type, String description, String merchantName,
                       LocalDate date, Instant postedAt, boolean pending, boolean manual,
                       UUID importBatchId, Instant createdAt) {
        this.importBatchId = importBatchId;
        this.householdId = householdId;
        this.accountId = accountId;
        this.userId = userId;
        this.providerTransactionId = providerTransactionId;
        this.amount = currency.normalise(amount);
        this.currency = currency;
        this.type = type;
        this.description = description;
        this.merchantName = merchantName;
        this.date = date;
        this.postedAt = postedAt;
        this.pending = pending;
        this.manual = manual;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instants.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    /** Who caused the row to exist. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    /** Always non-negative; {@link #getType()} says which way the money went. */
    public BigDecimal getAmount() {
        return amount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public TransactionType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public LocalDate getDate() {
        return date;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public boolean isPending() {
        return pending;
    }

    /**
     * Promotes a pending row to posted — the one in-place change a re-sync ever makes. It takes the
     * posted {@code amount} too (B1-07): a 40.00 restaurant pre-authorisation posts at 48.00 with
     * the tip, and keeping 40.00 left every balance and budget short of the bank's.
     */
    public void markPosted(Instant when, BigDecimal postedAmount) {
        this.pending = false;
        this.postedAt = when;
        this.amount = currency.normalise(postedAmount);
    }

    public boolean isManual() {
        return manual;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getTransferGroupId() {
        return transferGroupId;
    }

    public void setTransferGroupId(UUID transferGroupId) {
        this.transferGroupId = transferGroupId;
    }

    /** The attached receipt, or null. Written only by {@code ReceiptLinkService} (V38). */
    public UUID getReceiptId() {
        return receiptId;
    }

    /**
     * Attaches or detaches a receipt.
     *
     * <p>Called from {@code finance.trove.receipts.receipt.ReceiptLinkService} and from nowhere
     * else: the receipt side of the link is written in the same transaction, and a second caller
     * here would be the half-written link that V38's header comment exists to rule out.
     */
    public void setReceiptId(UUID receiptId) {
        this.receiptId = receiptId;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? null : List.copyOf(tags);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Identity only: the other fields are a household's spending history. */
    @Override
    public String toString() {
        return "Transaction[id=" + id + ", householdId=" + householdId + ", accountId=" + accountId
            + ", date=" + date + "]";
    }
}
