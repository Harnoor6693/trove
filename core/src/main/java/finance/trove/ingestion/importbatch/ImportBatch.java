package finance.trove.ingestion.importbatch;

import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.ImportMapping;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One uploaded CSV or OFX file and what importing it did — the Java side of V22
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <h2>What {@code mapping_json} holds, and why</h2>
 * V22 gives this table no column for the stored upload, and step 7 adds no migration: every table
 * it writes to already exists. {@code LocalDiskStorage} also <em>mints</em> its own keys
 * ({@code uploads/&lt;householdId&gt;/&lt;uuid&gt;.&lt;ext&gt;}) rather than accepting one, precisely so that no
 * caller-controlled component ever appears in a path. So four things live inside the one JSONB
 * column V22 provides for exactly this purpose, as the closed {@link ImportMapping} record:
 * <ul>
 *   <li>{@code storageKey} — the key the object store minted for the bytes;</li>
 *   <li>{@code originalFilename} — the name the browser sent, for display only;</li>
 *   <li>{@code format} — what the byte sniffer decided the file is;</li>
 *   <li>{@code csv} — the detected-then-member-adjusted column mapping, or null for OFX.</li>
 * </ul>
 * A record and not a free {@code Map}: the commit reads this back long after the preview wrote it,
 * and a map cannot tell a renamed key from a missing one.
 *
 * <p><strong>The mapping belongs to THIS batch and is not a reusable preset.</strong> There is no
 * mapping-preset table and no preset route anywhere in this plan; a member who uploads a second
 * file maps it again, against that file's own header.
 *
 * <p><strong>{@link #getStatus()} is the only thing an undo rewrites.</strong> The three counts say
 * what the commit did and are left exactly as they were: they are the record of an event, and an
 * undo is a later, different event.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    /**
     * {@code CSV} or {@code OFX} only, which is what V22's CHECK allows: {@code SIMPLEFIN} is
     * fetched over the network and {@code MANUAL} is typed in, so neither has a file to batch.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20, updatable = false)
    private SourceKind sourceKind;

    /** See the class javadoc. Nullable in V22; in practice this step always writes one. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapping_json")
    private ImportMapping mappingJson;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "new_count", nullable = false)
    private int newCount;

    @Column(name = "dup_count", nullable = false)
    private int dupCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ImportBatchStatus status = ImportBatchStatus.PREVIEW;

    /**
     * Set by the application, not left to the column's {@code DEFAULT now()} — an
     * {@code insertable = false} column leaves this field null on the entity Hibernate has just
     * persisted, so the creation response would answer {@code "createdAt": null} for a row that
     * unmistakably exists (the same fix {@code ConnectedAccount} carries).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ImportBatch() {
        // JPA
    }

    public ImportBatch(UUID householdId, UUID accountId, SourceKind sourceKind,
                       ImportMapping mappingJson, int rowCount, Instant createdAt) {
        this.householdId = householdId;
        this.accountId = accountId;
        this.sourceKind = sourceKind;
        this.mappingJson = mappingJson;
        this.rowCount = rowCount;
        this.status = ImportBatchStatus.PREVIEW;
        this.createdAt = createdAt;
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

    public SourceKind getSourceKind() {
        return sourceKind;
    }

    public ImportMapping getMappingJson() {
        return mappingJson;
    }

    public void setMappingJson(ImportMapping mappingJson) {
        this.mappingJson = mappingJson;
    }

    public int getRowCount() {
        return rowCount;
    }

    public int getNewCount() {
        return newCount;
    }

    public int getDupCount() {
        return dupCount;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Records what the commit actually did and moves the batch to {@link ImportBatchStatus#COMMITTED}. */
    public void markCommitted(int rows, int inserted, int duplicates) {
        this.rowCount = rows;
        this.newCount = inserted;
        this.dupCount = duplicates;
        this.status = ImportBatchStatus.COMMITTED;
    }

    /**
     * Moves the batch to {@link ImportBatchStatus#UNDONE}. The counts are deliberately left alone:
     * they say what the commit did, and the status says what happened to it afterwards.
     */
    public void markUndone() {
        this.status = ImportBatchStatus.UNDONE;
    }

    /** Identity only: {@code mappingJson} carries a storage key and a member's own filename. */
    @Override
    public String toString() {
        return "ImportBatch[id=" + id + ", householdId=" + householdId + ", accountId=" + accountId
            + ", sourceKind=" + sourceKind + ", status=" + status + "]";
    }
}
