package finance.trove.accounts;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
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
 * An account whose transactions Trove holds — a bank connection, an uploaded-file account, or the
 * credential-less manual account of gap P2-13
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2, §3 V18, §4.3).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope, not {@link #getUserId()}.
 * The owner's decision is one shared ledger per household (§1.5), so every member sees and may
 * change every account; {@code user_id} records who added it, for audit. The pre-collapse entity
 * scoped by {@code user_id} and took that id from a client-supplied {@code X-User-Id} header,
 * which is finding G1.
 *
 * <p><strong>{@link #getConfigEncrypted()} is nullable, and that is a fix.</strong> The column was
 * {@code NOT NULL} and the field was {@code @Column(nullable = false)}, which made a MANUAL, CSV
 * or OFX account impossible to insert: none of them has a credential blob to encrypt. Both change
 * together in this commit, because {@code ddl-auto: validate} compares them and a mismatch fails
 * the context refresh on boot rather than at first use.
 *
 * <p><strong>The credentials never leave this class.</strong> There is no {@code configEncrypted}
 * field on any response DTO — not a field that gets stripped, no field at all — and
 * {@link #toString()} does not mention it. It is decrypted in exactly one place, immediately
 * before a source is called, and the plaintext never outlives that call.
 *
 * <p>{@link #getExternalAccountId()} is the provider's own id for this account and the third
 * column of {@code UNIQUE (household_id, source_kind, external_account_id) NULLS NOT DISTINCT}.
 * That key is what lets step 7's re-exchange or repeat upload find and reuse an existing account
 * instead of creating a second one and duplicating every transaction on it (§6 M0). Because nulls
 * compare equal there, a MANUAL account is given its own id as a synthetic value — see
 * {@link AccountService#create}.
 */
@Entity
@Table(name = "connected_account")
public class ConnectedAccount {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 20, updatable = false)
    private SourceKind sourceKind;

    @Column(name = "external_account_id", length = 255)
    private String externalAccountId;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "institution", length = 200)
    private String institution;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    /**
     * No initialiser and no column default (§9 risk 5). A default currency is how a US account
     * silently becomes a Canadian-dollar one; the value comes from the request or from the
     * creating member's own {@code users.currency}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    /** See the class javadoc. Nullable because three of the four source kinds have no config. */
    @Column(name = "config_encrypted")
    private String configEncrypted;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 10)
    private SyncStatus syncStatus = SyncStatus.PENDING;

    @Column(name = "error_message")
    private String errorMessage;

    /**
     * Set by the application at construction, not left to the column's {@code DEFAULT now()}: an
     * {@code insertable = false} column leaves this field null on the entity Hibernate has just
     * persisted, so the creation response would answer {@code "createdAt": null} for a row that
     * unmistakably exists (the defect step 3b's conformance check found on {@code
     * HouseholdInvite}, fixed there the same way). The column keeps its default for any row
     * inserted outside JPA.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ConnectedAccount() {
        // JPA
    }

    public ConnectedAccount(UUID householdId, UUID userId, SourceKind sourceKind,
                            String displayName, String institution, AccountType accountType,
                            CurrencyCode currency, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.sourceKind = sourceKind;
        this.displayName = displayName;
        this.institution = institution;
        this.accountType = accountType;
        this.currency = currency;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    /** Who added the account. Audit only — never a scope. */
    public UUID getUserId() {
        return userId;
    }

    public SourceKind getSourceKind() {
        return sourceKind;
    }

    /** Whether a hand-entered transaction may be recorded against this account (gap P2-13). */
    public boolean acceptsManualEntry() {
        return sourceKind == SourceKind.MANUAL;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public void setExternalAccountId(String externalAccountId) {
        this.externalAccountId = externalAccountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    /** The encrypted source credentials. Never serialised, never logged, never returned. */
    public String getConfigEncrypted() {
        return configEncrypted;
    }

    public void setConfigEncrypted(String configEncrypted) {
        this.configEncrypted = configEncrypted;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Identity and nothing else: this object holds a household's credentials. */
    @Override
    public String toString() {
        return "ConnectedAccount[id=" + id + ", householdId=" + householdId + ", sourceKind="
            + sourceKind + ", syncStatus=" + syncStatus + "]";
    }
}
