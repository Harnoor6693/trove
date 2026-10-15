package finance.trove.receipts.receipt;

import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored file behind a receipt ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V37-row).
 *
 * <p><strong>There is no {@code ReceiptImageRepository} and there will not be one.</strong> An
 * image is reachable only through its {@link Receipt} — the aggregate owns the collection with
 * {@code orphanRemoval} — because an image row carries no {@code household_id} of its own and a
 * repository over it would therefore be a by-id lookup with nothing to scope it. That is the
 * shape {@code ArchitectureTest} exists to prevent, and mapping the association instead of
 * allowlisting a repository is the stronger answer: there is no unscoped finder to reach for.
 *
 * <p><strong>{@code receipt_id} is deliberately not a field here.</strong> The parent's
 * {@code @JoinColumn} owns that column, and a second mapping of it would be either a Hibernate
 * "column is duplicated in mapping" failure at boot or, with {@code insertable = false}, a getter
 * that silently answers null on a freshly written row. Not having it also says the thing the
 * design means: an image has no identity apart from its receipt.
 *
 * <p><strong>{@code fileKey} is a {@link finance.trove.common.storage.LocalDiskStorage} key</strong>
 * of the form {@code receipts/<householdId>/<uuid>.<ext>}. It is not a MinIO object name — MinIO
 * is deleted, not moved — and it is not a path: the storage generates every key itself from a v4
 * UUID and a re-validated extension hint, so no component of it is caller-controlled, and it
 * re-checks the key against its root every time it is read back.
 */
@Entity
@Table(name = "receipt_image")
public class ReceiptImage {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 9, updatable = false)
    private ReceiptImageKind kind;

    /**
     * Zero for an {@link ReceiptImageKind#ORIGINAL} and a {@link ReceiptImageKind#THUMBNAIL}.
     *
     * <p>Not nullable, deliberately (§3): the unique constraint is over
     * {@code (receipt_id, kind, page_no)}, and a plain {@code UNIQUE} treats two NULLs as distinct
     * from each other — so a nullable column here would let one receipt accumulate any number of
     * ORIGINAL rows without the constraint noticing. A non-null default sidesteps that entirely.
     */
    @Column(name = "page_no", nullable = false, updatable = false)
    private int pageNo;

    @Column(name = "file_key", nullable = false, updatable = false)
    private String fileKey;

    @Column(name = "mime_type", nullable = false, length = 100, updatable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** Lowercase hex SHA-256 of the stored bytes, for integrity checks. Never a key. */
    @Column(name = "sha256", length = 64, updatable = false)
    private String sha256;

    /** Set here, not left to the column default: see {@link Receipt#getCreatedAt()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReceiptImage() {
        // JPA
    }

    public ReceiptImage(ReceiptImageKind kind, int pageNo, String fileKey, String mimeType,
                        long sizeBytes, String sha256) {
        this.kind = kind;
        this.pageNo = pageNo;
        this.fileKey = fileKey;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.createdAt = Instants.now();
    }

    public UUID getId() {
        return id;
    }

    public ReceiptImageKind getKind() {
        return kind;
    }

    public int getPageNo() {
        return pageNo;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
