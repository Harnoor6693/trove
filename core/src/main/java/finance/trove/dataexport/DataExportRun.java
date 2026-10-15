package finance.trove.dataexport;

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
 * One full-data-export a member asked for (collapse step 18; gap row 16; V41).
 *
 * <p><strong>Tenancy: this row is scoped by {@code user_id}, not by {@code household_id}</strong>,
 * for the same reason {@code TaxExportRun} is and not by imitation of it. The archive is a copy of
 * the caller's own account, so a household mate must not see, download or delete it — the
 * opposite of every ledger route in the tree. The ROWS inside the archive still come from the
 * household's shared ledger, and the operation description says so, because "my export"
 * containing a household mate's spending surprises anybody who was not told.
 *
 * <p><strong>{@code householdId} is on this row and is NOT a second scope.</strong> No finder keys
 * on it. It is here because {@code LocalDiskStorage.put} takes the household id as its second
 * argument and generates a three-segment key {@code exports/<householdId>/<uuid>.zip} — there is
 * no per-user segment and one may not be added, since {@code pathOf} refuses any other shape and
 * that refusal is the traversal guard. The expiry sweep and the account purge both have to resolve
 * a row to its file with no request in hand, so the row carries the id the key was built from.
 *
 * <p><strong>{@code expiresAt} is enforced, not decorative.</strong> Gap row 16 asks for a single
 * 24-hour download link; {@link #isDownloadable} refuses a lapsed run with the same 409 an
 * unfinished one gets, and {@code DataExportSweep} deletes the bytes rather than merely hiding
 * them.
 *
 * <p>{@code fileKey} is never exposed on the wire. The download route resolves it itself, and a
 * client that held it could only be tempted to build a path from it.
 */
@Entity
@Table(name = "data_export_run")
public class DataExportRun {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private DataExportStatus status = DataExportStatus.PENDING;

    @Column(name = "file_key")
    private String fileKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected DataExportRun() {
        // JPA
    }

    /**
     * A run as the POST records it: PENDING, no file, no error, no expiry.
     *
     * <p>{@code createdAt} is set here rather than left to the column DEFAULT, deliberately: the
     * response schema declares it required under {@code additionalProperties: false}, and an
     * {@code insertable = false} column with a database default answers null for the row it has
     * just inserted — which the conformance validator fails, as decision 25 records.
     */
    public DataExportRun(UUID userId, UUID householdId, Instant now) {
        this.userId = userId;
        this.householdId = householdId;
        this.status = DataExportStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
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

    public UUID getHouseholdId() {
        return householdId;
    }

    public DataExportStatus getStatus() {
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Whether the archive can be served right now.
     *
     * <p>Three conditions, and the third is the one gap row 16 asks for: the run completed, its
     * bytes are still referenced, and the 24-hour link has not lapsed. All three failures answer
     * the same 409, because "not yet" and "not any more" are both "there is nothing to send" and
     * neither is a reason to tell a polling client its id is dead.
     */
    public boolean isDownloadable(Instant now) {
        return status.canHaveFile() && fileKey != null
            && (expiresAt == null || now.isBefore(expiresAt));
    }

    /**
     * Moves the run to {@code newStatus}, recording {@code reason} when one is given.
     *
     * <p>There is no bare setter: a status and the reason for it are written together, or a row
     * can end up FAILED with no explanation, which is the one state a member cannot act on.
     */
    void transitionTo(DataExportStatus newStatus, String reason) {
        this.status = newStatus;
        if (reason != null) {
            this.errorMessage = reason;
        }
    }

    /** Marks the run complete: where its bytes are, how many there are, and when they lapse. */
    void complete(String storageKey, long sizeBytes, Instant linkExpiresAt) {
        this.fileKey = storageKey;
        this.fileSizeBytes = sizeBytes;
        this.expiresAt = linkExpiresAt;
        this.status = DataExportStatus.COMPLETE;
    }

    /**
     * Forgets an archive the sweep has deleted from disk.
     *
     * <p>The status stays {@code COMPLETE} — the run really did complete, and rewriting history to
     * say otherwise would tell a member their export failed when it did not. What changes is that
     * there is nothing left to send, which {@link #isDownloadable} already answers from a null
     * key, and the size goes with it so the response does not advertise bytes that are gone.
     */
    void forgetFile() {
        this.fileKey = null;
        this.fileSizeBytes = null;
    }
}
