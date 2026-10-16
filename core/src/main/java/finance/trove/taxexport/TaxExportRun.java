package finance.trove.taxexport;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tax-year export a member asked for ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9,
 * §3 V41-row (plan numbering; shipped as V35__tax_export_run.sql), §4.12, §6 "Tax export").
 *
 * <p><strong>Tenancy: this row is scoped by {@code user_id}, not by {@code household_id}</strong>,
 * and that is the deliberate exception to the rule every other ledger table follows. A tax filing
 * is individual even inside a shared household ledger (§1.5, §6), so a household mate must not
 * see, download or delete another member's run — the opposite of every route steps 6-10 added.
 * The ROWS inside the generated file still come from the household's shared ledger; only the run
 * does not, and the operation description says so because "my tax export" containing a household
 * mate's spending surprises anybody who was not told.
 *
 * <p><strong>{@code jurisdiction} is stamped at creation</strong> from the member's own
 * {@code users.country} and is never taken from the request. A completed run therefore stays
 * reproducible from its own row after the member changes country — which is also why the caller
 * cannot override it.
 *
 * <p><strong>{@code fileKey} is a {@code LocalDiskStorage} key</strong>
 * ({@code exports/<householdId>/<uuid>.<ext>}), not a MinIO object name: MinioConfig and
 * MinioStorageService are deleted rather than moved (RS-7 — presigned URLs signed against
 * internal Docker DNS were unreachable from any browser and had no public-endpoint override).
 * The download streams the bytes instead. The key's second segment is the HOUSEHOLD id because
 * that is what {@code LocalDiskStorage.usage(householdId)} accounts against; ownership of the run
 * lives in {@link #getUserId()}, which is what the download route checks.
 *
 * <p>The source's {@code report_type}, {@code requested_filters} and {@code period_start}/
 * {@code period_end} have no counterpart here: the report types are out of scope (§2.9),
 * {@code requested_filters} was a column nothing ever wrote (RS-8), and the tax year IS the
 * period.
 */
@Entity
@Table(name = "tax_export_run")
public class TaxExportRun {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "tax_year", nullable = false, updatable = false)
    private int taxYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "jurisdiction", nullable = false, length = 2, updatable = false)
    private Jurisdiction jurisdiction;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 5, updatable = false)
    private TaxExportFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private TaxExportStatus status = TaxExportStatus.PENDING;

    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected TaxExportRun() {
        // JPA
    }

    /** A run as the POST records it: PENDING, no file, no error. */
    public TaxExportRun(UUID userId, int taxYear, Jurisdiction jurisdiction,
                        TaxExportFormat format) {
        this.userId = userId;
        this.taxYear = taxYear;
        this.jurisdiction = jurisdiction;
        this.format = format;
        this.status = TaxExportStatus.PENDING;
        this.createdAt = Instants.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instants.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getTaxYear() {
        return taxYear;
    }

    public Jurisdiction getJurisdiction() {
        return jurisdiction;
    }

    public TaxExportFormat getFormat() {
        return format;
    }

    public TaxExportStatus getStatus() {
        return status;
    }

    public String getFileKey() {
        return fileKey;
    }

    public Long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Moves the run to {@code status}, recording {@code errorMessage} when one is given.
     *
     * <p>There is no bare {@code setStatus}: a status and the reason for it are written together
     * or the row can end up FAILED with no explanation, which is exactly the state a member
     * cannot act on.
     */
    void transitionTo(TaxExportStatus newStatus, String reason) {
        this.status = newStatus;
        if (reason != null) {
            this.errorMessage = reason;
        }
    }

    /** Marks the run complete and records where its bytes are and how many there are. */
    void complete(String storageKey, long sizeBytes) {
        this.fileKey = storageKey;
        this.fileSizeBytes = sizeBytes;
        this.status = TaxExportStatus.COMPLETE;
    }
}
