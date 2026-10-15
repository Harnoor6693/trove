package finance.trove.receipts.receipt;

import finance.trove.common.domain.CurrencyCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One receipt of the household's ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, §3 V37-row,
 * §4.10, §6 "Receipts").
 *
 * <p><strong>Tenancy: {@code householdId} is the scope and {@code userId} is the uploader.</strong>
 * This is the ordinary ledger rule (§1.5, §3's tenancy note) and the opposite of what collapse
 * steps 11, 13 and 15 do with their own tables. A household mate can see, download and delete a
 * receipt somebody else in the household photographed, because that is what a shared ledger is;
 * getting it backwards would hide a member's receipts from their own household. Household B
 * cannot reach it at all, which {@code AccessControlIT} asserts by name (§9 risk 2 lists
 * {@code receipt} among its five entities).
 *
 * <p><strong>The aggregate owns its images and line items.</strong> Neither has a repository:
 * neither table carries a {@code household_id}, so a repository over either would be an unscoped
 * by-id lookup — the shape {@code ArchitectureTest} exists to prevent. Mapping the two
 * associations with {@code orphanRemoval} is the stronger answer, because it leaves no unscoped
 * finder to reach for and it makes "delete the receipt, its image rows and its line items" one
 * statement rather than three that can half-happen.
 *
 * <p><strong>{@code matchedTransactionId} has exactly one writer.</strong> V38 adds the other half
 * of the link, {@code transaction.receipt_id}, and {@link ReceiptLinkService} is the only code in
 * the tree that writes either column — both in one transaction. Two half-written links is the
 * failure the design is shaped to prevent.
 *
 * <p><strong>The tax split stays here.</strong> {@code gstAmount}, {@code pstAmount} and
 * {@code hstAmount} are reverse-calculated for Canadian members only and are never exported: the
 * tax-time export carries the member's own amounts and labels, with no rate and no computed figure
 * of any kind (§2.9, §4.12, and step 15's {@code TaxExportIT} greps the generated files for
 * exactly this).
 */
@Entity
@Table(name = "receipt")
public class Receipt {

    /** The composite-score ceiling above which a receipt does not need a human (§2.6, D1). */
    public static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.920");

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    /**
     * Who uploaded it. NOT the scope — see the class javadoc — and <strong>nullable since
     * V43</strong>.
     *
     * <p>V37 declared this column {@code NOT NULL ... ON DELETE CASCADE}, which was the one
     * foreign key in the schema that disagreed with its own table's tenancy: under that cascade,
     * deleting ONE member silently deleted the HOUSEHOLD'S receipts — shared ledger rows two other
     * people still rely on — and left their images on the volume with nothing pointing at them.
     * V43 makes it {@code NULL ... ON DELETE SET NULL}, the shape every other household-scoped
     * table already uses (V18-V21, V23, V25-V30, and {@code created_by} in V14/V15), which is
     * owner decision 17 in schema form: the shared ledger wins and the author is nulled.
     *
     * <p>So a receipt whose uploader has left the household reads null here, and its two readers
     * handle that rather than throwing: {@code ReceiptPipelineOrchestrator} degrades to the same
     * neutral context an unknown uploader already produced, and {@code VisionExtractionStage}
     * takes the AI-off path — {@code REVIEW_REQUIRED}, no exception, and no 500 out of the orphan
     * sweep.
     */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ReceiptStatus status = ReceiptStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10, updatable = false)
    private ReceiptSource source = ReceiptSource.UPLOAD;

    /** Size of the file the member sent, before re-encoding. The stored JPEG is usually smaller. */
    @Column(name = "upload_size_bytes", nullable = false, updatable = false)
    private long uploadSizeBytes;

    /**
     * Whatever text the extraction produced, which the heuristic parser then reads.
     *
     * <p>It survives PaddleOCR's deletion under its old name: the vision model returns text, and
     * renaming the column would have been a migration for no behaviour.
     */
    @Column(name = "ocr_text")
    private String ocrText;

    @Column(name = "extracted_merchant", length = 200)
    private String extractedMerchant;

    @Column(name = "extracted_amount", precision = 19, scale = 4)
    private BigDecimal extractedAmount;

    /**
     * Null until something sets it, and never defaulted to a literal.
     *
     * <p>The source returned a hard-coded Canadian dollar code for any receipt merely containing
     * a {@code $} (inventory D3), which made every American member's coffee Canadian. The column
     * has no database default either, and {@code SchemaDefaultsIT} fails any {@code %currency%}
     * column that grows one.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "extracted_currency", length = 3)
    private CurrencyCode extractedCurrency;

    @Column(name = "extracted_date")
    private LocalDate extractedDate;

    /** The extractor's line items as JSON, before the scoring stage promotes them to rows. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_line_items")
    private String extractedLineItems;

    @Column(name = "gst_amount", precision = 19, scale = 4)
    private BigDecimal gstAmount;

    @Column(name = "pst_amount", precision = 19, scale = 4)
    private BigDecimal pstAmount;

    @Column(name = "hst_amount", precision = 19, scale = 4)
    private BigDecimal hstAmount;

    /** Composite pipeline confidence in {@code [0.000, 1.000]}; {@code NUMERIC(4,3)}. */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "matched_transaction_id")
    private UUID matchedTransactionId;

    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired;

    /** One sentence. Never a stack trace, never a path, never the address of anything. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processing_completed_at")
    private Instant processingCompletedAt;

    /**
     * Set in the constructor rather than left to the column's {@code DEFAULT now()}.
     *
     * <p>A DB-defaulted, {@code insertable = false} column reads back as null on the entity the
     * insert returned, and {@code POST /v1/receipts/upload} answers straight out of that entity —
     * so the 202 would carry {@code "createdAt": null}, which the contract does not allow and the
     * conformance check catches. {@code TaxExportRun} sets it the same way for the same reason.
     * The database default stays as the backstop for a row written by anything but this class.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * The stored files, {@code EAGER} deliberately.
     *
     * <p>A receipt has at most three image rows and every route that loads one needs them —
     * the download and the thumbnail to find a key, the delete to find the files to remove, the
     * detail response to build {@code thumbnailUrl}. More to the point, the pipeline runs on a
     * background thread with no open session between stages: a lazy collection there is a
     * {@code LazyInitializationException} waiting for the first stage that touches it. The list
     * route does not pay for this — it reads a constructor projection and never materialises a
     * {@link Receipt} at all.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "receipt_id", nullable = false)
    @OrderBy("kind ASC, pageNo ASC")
    private List<ReceiptImage> images = new ArrayList<>();

    /** The extracted lines. {@code EAGER} for the same reason as {@link #images}. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "receipt_id", nullable = false)
    @OrderBy("createdAt ASC")
    private List<LineItem> lineItems = new ArrayList<>();

    protected Receipt() {
        // JPA
    }

    public Receipt(UUID householdId, UUID userId, ReceiptSource source, long uploadSizeBytes) {
        this.householdId = householdId;
        this.userId = userId;
        this.source = source == null ? ReceiptSource.UPLOAD : source;
        this.uploadSizeBytes = uploadSizeBytes;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** The stored full-size image, or empty when the files were removed underneath the row. */
    public Optional<ReceiptImage> original() {
        return imageOf(ReceiptImageKind.ORIGINAL);
    }

    /** The thumbnail, or empty. */
    public Optional<ReceiptImage> thumbnail() {
        return imageOf(ReceiptImageKind.THUMBNAIL);
    }

    private Optional<ReceiptImage> imageOf(ReceiptImageKind kind) {
        return images.stream().filter(image -> image.getKind() == kind).findFirst();
    }

    public void addImage(ReceiptImage image) {
        images.add(image);
    }

    /**
     * Replaces the whole set of line items.
     *
     * <p>Replace rather than append: a second extraction of the same receipt must not leave the
     * first extraction's lines beside its own, and {@code orphanRemoval} turns the clear into the
     * deletes.
     */
    public void replaceLineItems(List<LineItem> replacements) {
        lineItems.clear();
        lineItems.addAll(replacements);
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    /** The uploader, or null when they have left the household (V43). */
    public UUID getUserId() {
        return userId;
    }

    public ReceiptStatus getStatus() {
        return status;
    }

    public void setStatus(ReceiptStatus status) {
        this.status = status;
    }

    public ReceiptSource getSource() {
        return source;
    }

    public long getUploadSizeBytes() {
        return uploadSizeBytes;
    }

    public String getOcrText() {
        return ocrText;
    }

    public void setOcrText(String ocrText) {
        this.ocrText = ocrText;
    }

    public String getExtractedMerchant() {
        return extractedMerchant;
    }

    public void setExtractedMerchant(String extractedMerchant) {
        this.extractedMerchant = extractedMerchant;
    }

    public BigDecimal getExtractedAmount() {
        return extractedAmount;
    }

    public void setExtractedAmount(BigDecimal extractedAmount) {
        this.extractedAmount = extractedAmount;
    }

    public CurrencyCode getExtractedCurrency() {
        return extractedCurrency;
    }

    public void setExtractedCurrency(CurrencyCode extractedCurrency) {
        this.extractedCurrency = extractedCurrency;
    }

    public LocalDate getExtractedDate() {
        return extractedDate;
    }

    public void setExtractedDate(LocalDate extractedDate) {
        this.extractedDate = extractedDate;
    }

    public String getExtractedLineItems() {
        return extractedLineItems;
    }

    public void setExtractedLineItems(String extractedLineItems) {
        this.extractedLineItems = extractedLineItems;
    }

    public BigDecimal getGstAmount() {
        return gstAmount;
    }

    public BigDecimal getPstAmount() {
        return pstAmount;
    }

    public BigDecimal getHstAmount() {
        return hstAmount;
    }

    /** The three CA components move together or not at all; there is no way to set one. */
    public void setTaxSplit(BigDecimal gst, BigDecimal pst, BigDecimal hst) {
        this.gstAmount = gst;
        this.pstAmount = pst;
        this.hstAmount = hst;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public UUID getMatchedTransactionId() {
        return matchedTransactionId;
    }

    /**
     * Package-private on purpose: {@link ReceiptLinkService} is the only writer of either half of
     * the receipt/transaction link, and a public setter here would be an invitation to write one
     * half from somewhere else.
     */
    void setMatchedTransactionId(UUID matchedTransactionId) {
        this.matchedTransactionId = matchedTransactionId;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }

    public void setReviewRequired(boolean reviewRequired) {
        this.reviewRequired = reviewRequired;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(Instant processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public Instant getProcessingCompletedAt() {
        return processingCompletedAt;
    }

    public void setProcessingCompletedAt(Instant processingCompletedAt) {
        this.processingCompletedAt = processingCompletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<ReceiptImage> getImages() {
        return List.copyOf(images);
    }

    public List<LineItem> getLineItems() {
        return List.copyOf(lineItems);
    }
}
