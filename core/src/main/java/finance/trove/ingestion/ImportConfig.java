package finance.trove.ingestion;

/**
 * The keys a file import puts into the configuration map {@code SyncOrchestrator} hands a source.
 *
 * <p>{@code BankSource.fetch} takes the account's decrypted configuration, and for a SimpleFIN
 * account that is what it is. A file import has no stored credential at all — an OFX or CSV
 * account's {@code config_encrypted} is null, which is why V18 made the column nullable — so the
 * commit passes the BATCH's configuration instead, through the orchestrator overload that exists
 * for it. These are the three things it carries.
 *
 * <p>Nothing here is a secret: a storage key, a column mapping and an account id. The encrypted
 * blob a SimpleFIN account carries never travels this way.
 */
public final class ImportConfig {

    /** The key {@code LocalDiskStorage} minted for the uploaded bytes. */
    public static final String STORAGE_KEY = "storageKey";

    /** The batch's {@link ImportMapping}, as JSON. */
    public static final String MAPPING = "mappingJson";

    /** The {@code connected_account} the rows belong to, as a UUID string. */
    public static final String ACCOUNT_ID = "accountId";

    private ImportConfig() {
        throw new UnsupportedOperationException("Utility class");
    }
}
