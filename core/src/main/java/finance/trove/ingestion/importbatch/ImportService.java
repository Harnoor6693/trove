package finance.trove.ingestion.importbatch;

import finance.trove.accounts.AccountService;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.UserClock;
import finance.trove.ingestion.FileImportSource;
import finance.trove.ingestion.ImportConfig;
import finance.trove.ingestion.ImportContext;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.SourceRegistry;
import finance.trove.ingestion.SyncLog;
import finance.trove.ingestion.SyncOrchestrator;
import finance.trove.ingestion.SyncRunStatus;
import finance.trove.ingestion.importbatch.dto.ImportCommitRequest;
import finance.trove.ingestion.importbatch.dto.ImportPreviewResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Upload, preview, commit and undo for a CSV or OFX file
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12; {@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §4.4, §6 "CSV/OFX/QFX import").
 *
 * <h2>A preview writes nothing</h2>
 * That is the invariant this class exists to hold. {@code DeduplicationService.reconcileOne}
 * INSERTS — it is not a read-only classifier — so the preview goes through
 * {@link DeduplicationService#classify}, the read-only half of the SAME rules, rather than through
 * a transaction that is rolled back afterwards or through a second copy of the matching logic.
 *
 * <h2>A commit reuses the sync pipeline</h2>
 * {@link SyncOrchestrator#sync(UUID, UUID, UUID, Map, UUID)} is the one overload, and the commit
 * goes through it: the account claim, the fetch outside any transaction, the single short batch
 * transaction, the {@code sync_log} row and the {@code afterCommit} event are the same code a bank
 * sync runs. What the batch adds is its own configuration — where the file is and how to read it —
 * and its id, which is stamped onto every row inserted.
 *
 * <h2>An undo removes only what nobody has touched</h2>
 * Never a blanket delete by {@code import_batch_id}: V22 chose {@code ON DELETE SET NULL} on that
 * column exactly so that removing a batch can never take a household's ledger with it. See
 * {@link ImportUndoService}.
 */
@Service
public class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);

    /** Longest original filename kept for display. */
    private static final int MAX_FILENAME_CHARS = 255;

    private final ImportBatchRepository batches;
    private final ImportFileValidator validator;
    private final SourceRegistry sources;
    private final AccountService accounts;
    private final ImportPreviewBuilder previews;
    private final SyncOrchestrator syncOrchestrator;
    private final ImportUndoService undo;
    private final LocalDiskStorage storage;
    private final ImportLimits limits;
    private final ObjectMapper objectMapper;
    private final UserClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ImportService(ImportBatchRepository batches, ImportFileValidator validator,
                         SourceRegistry sources, AccountService accounts,
                         ImportPreviewBuilder previews, SyncOrchestrator syncOrchestrator,
                         ImportUndoService undo, LocalDiskStorage storage, ImportLimits limits,
                         ObjectMapper objectMapper, UserClock clock) {
        this.batches = batches;
        this.validator = validator;
        this.sources = sources;
        this.accounts = accounts;
        this.previews = previews;
        this.syncOrchestrator = syncOrchestrator;
        this.undo = undo;
        this.storage = storage;
        this.limits = limits;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * {@code POST /v1/imports} — stores the file, works out what is in it, and answers with a
     * preview. <strong>No transaction row is written.</strong>
     *
     * <p>The order is deliberate. The two ceilings that decide whether the bytes may be stored at
     * all — the per-file one and the household's allowance — are checked FIRST, from the part's
     * declared size, so an upload that cannot be kept is never read. Then the format, the account
     * and the parse, all of which can refuse the request; only a file that has survived every one
     * of those reaches {@code storage.put}, so a file that cannot be read leaves nothing behind on
     * the volume. Classification comes last because it needs the resolved account and the saved
     * batch row; {@link ImportPreviewBuilder} runs it in one short read-only transaction around
     * the classification alone.
     *
     * @param declaredKind what the caller says the file is, or {@code null} to let the bytes decide
     * @param accountId    required for CSV, which carries no account identity; optional for OFX,
     *                     where {@code ACCTID} resolves one
     */
    public ImportPreviewResponse create(UUID householdId, UUID userId, byte[] content,
                                        String contentType, String originalFilename,
                                        long declaredSize, UUID accountId,
                                        SourceKind declaredKind) {
        validator.requireAcceptableSize(declaredSize);
        requireStorageAllowance(householdId, declaredSize);
        validator.requireAcceptableContentType(contentType);
        SourceKind format = validator.detectFormat(content, declaredKind);
        FileImportSource source = fileSource(format);

        ConnectedAccount account = accountId == null ? null : accounts.get(householdId, accountId);
        if (format == SourceKind.CSV && account == null) {
            throw new ValidationException("A CSV file does not say which account its rows belong "
                + "to. Send accountId with the upload.");
        }
        if (account != null) {
            requireCompatible(account, format);
        }

        ParsedUpload parsed = read(source, content, null,
            new ImportContext(account == null ? null : account.getId()));
        if (account == null) {
            account = upsertFromFile(householdId, userId, format, parsed);
        }

        String storageKey = storage.put(StorageArea.UPLOADS, householdId, content,
            format.name().toLowerCase(Locale.ROOT));
        ImportMapping mapping = new ImportMapping(storageKey, displayFilename(originalFilename),
            format, parsed.mapping());
        ImportBatch batch = batches.save(new ImportBatch(householdId, account.getId(), format,
            mapping, parsed.rows().size(), clock.now()));
        LOG.info("Import batch {} previewed {} rows for account {}", batch.getId(),
            parsed.rows().size(), account.getId());
        return previews.build(batch, account, parsed);
    }

    /**
     * {@code GET /v1/imports/{id}/preview} — the same body, re-read.
     *
     * <p>Deliberately NOT transactional as a whole: it re-reads and re-parses the stored file,
     * which {@code trove.import.parse-budget} allows sixty seconds for, and a connection must not
     * be held across that. {@link ImportPreviewBuilder#build} is the read-only transaction, around
     * the classification alone.
     */
    public ImportPreviewResponse preview(UUID householdId, UUID batchId) {
        ImportBatch batch = require(householdId, batchId);
        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            return ImportPreviewResponse.ofStoredCounts(batch, "This batch is "
                + batch.getStatus().name().toLowerCase(Locale.ROOT) + ", so the counts below are "
                + "what the commit recorded rather than a fresh reading of the file. The uploaded "
                + "file is kept only while the batch can still be undone.");
        }
        ConnectedAccount account = accounts.get(householdId, batch.getAccountId());
        ImportMapping mapping = mappingOf(batch);
        ParsedUpload parsed = read(fileSource(batch.getSourceKind()),
            storage.readAllBytes(mapping.storageKey()), mapping,
            new ImportContext(account.getId()));
        return previews.build(batch, account, parsed);
    }

    /**
     * {@code POST /v1/imports/{id}/commit} — writes the rows, through the sync pipeline.
     *
     * <p>Not {@code @Transactional}: it runs a full sync, and {@code SyncOrchestrator}'s own
     * javadoc explains at length why that must not be wrapped in one transaction.
     */
    public SyncLog commit(UUID householdId, UUID userId, UUID batchId,
                          ImportCommitRequest request) {
        ImportBatch batch = require(householdId, batchId);
        if (batch.getStatus() != ImportBatchStatus.PREVIEW) {
            throw new ConflictException("This import has already been "
                + batch.getStatus().name().toLowerCase(Locale.ROOT)
                + ". Upload the file again to import it a second time.");
        }
        ConnectedAccount account = accounts.get(householdId, batch.getAccountId());
        // SyncOrchestrator resolves the source from the ACCOUNT's kind. create() refuses a batch
        // whose file is not the account's kind and connected_account.source_kind is never updated
        // after creation, so these two are equal by construction — asserted here rather than
        // assumed, because the alternative is a batch that can never be committed and can never
        // be undone (confirmed review finding 2).
        requireCompatible(account, batch.getSourceKind());
        ImportMapping mapping = supersede(batch, request);

        SyncLog log = syncOrchestrator.sync(householdId, userId, batch.getAccountId(),
            configFor(mapping, account), batch.getId());
        boolean everyRowRejected = log.getTransactionsFailed() > 0 && log.getTransactionsNew()
            + log.getTransactionsUpdated() + log.getTransactionsSkipped() == 0;
        if (log.getStatus() == SyncRunStatus.FAILED || everyRowRejected) {
            // Nothing was written, so the batch stays in PREVIEW and the member can fix the
            // mapping and commit again. Marking it COMMITTED here would strand the upload: a run
            // that rejects every row (the wrong date format) is PARTIAL_FAILURE, not FAILED (B1-17).
            return log;
        }
        undo.markCommitted(householdId, batchId, log.getTransactionsFetched(),
            log.getTransactionsNew(), log.getTransactionsSkipped() + log.getTransactionsUpdated());
        return log;
    }

    /** {@code DELETE /v1/imports/{id}} — see {@link ImportUndoService}. */
    public void undo(UUID householdId, UUID batchId) {
        ImportBatch batch = require(householdId, batchId);
        if (batch.getStatus() != ImportBatchStatus.COMMITTED) {
            throw new ConflictException(batch.getStatus() == ImportBatchStatus.PREVIEW
                ? "This import was never committed, so there is nothing to undo."
                : "This import has already been undone.");
        }
        String storageKey = mappingOf(batch).storageKey();
        int removed = undo.undo(householdId, batchId);
        // StorageArea.UPLOADS' own javadoc: the file is retained only while the batch can still be
        // undone. An UNDONE batch never can be again, so this is the moment it stops being needed.
        if (!storage.delete(storageKey)) {
            LOG.warn("The stored upload for import batch {} was already gone", batchId);
        }
        LOG.info("Import batch {} undone; {} row(s) removed", batchId, removed);
    }

    /**
     * A household's abandoned previews and their files, swept.
     *
     * <p>Confirmed review finding 3: {@code storage.delete} was reachable from {@link #undo}
     * alone, which requires a COMMITTED batch, so a batch abandoned in {@code PREVIEW} — the
     * member changed their mind, or the commit was refused — kept its upload on the shared data
     * volume for ever, and nothing anywhere swept {@code StorageArea.UPLOADS}.
     * {@code StorageArea.UPLOADS}' own javadoc says the file is retained only while an import
     * batch can still be undone; a PREVIEW batch past this window never will be.
     *
     * <p>The row goes with the file. There is no fourth {@code import_batch.status} — V22's CHECK
     * allows PREVIEW, COMMITTED and UNDONE only — and a PREVIEW batch whose file is gone could
     * neither be previewed nor committed, so keeping it would leave a row that answers 409 for
     * ever. A COMMITTED or UNDONE batch is never touched here: those rows are the record of what
     * was imported.
     *
     * @param cutoff batches created before this instant are swept
     * @return how many batches were removed
     */
    public int purgeAbandonedPreviews(Instant cutoff) {
        int removed = 0;
        for (UUID householdId : batches.findHouseholdIdsWithPreviewsBefore(cutoff)) {
            for (ImportBatch batch : batches.findAllByHouseholdIdAndStatusAndCreatedAtBefore(
                householdId, ImportBatchStatus.PREVIEW, cutoff)) {
                // The row first, and only while it is still a PREVIEW: a commit that recorded
                // itself since keeps both its row and the file its undo needs (B1-16).
                if (batches.deletePreview(batch.getId(), householdId) == 0) {
                    continue;
                }
                removed++;
                ImportMapping mapping = batch.getMappingJson();
                if (mapping != null && mapping.storageKey() != null) {
                    storage.delete(mapping.storageKey());
                }
            }
        }
        if (removed > 0) {
            LOG.info("import-preview-sweep removed {} abandoned preview batch(es) older than {}",
                removed, cutoff);
        }
        return removed;
    }

    /**
     * Refuses an upload that would take this household past its stored-upload allowance.
     *
     * <p>The other half of finding 3. The retention sweep bounds how LONG a file is kept; this
     * bounds how much is held at once, so that an authenticated member cannot grow the shared
     * volume ten megabytes at a time inside the window. Checked against the part's DECLARED size,
     * before a byte is stored, and answering the same 413 {@code upload-too-large} the per-file
     * ceiling does.
     */
    private void requireStorageAllowance(UUID householdId, long declaredSize) {
        long used = storage.usage(StorageArea.UPLOADS, householdId);
        if (used + Math.max(declaredSize, 0) > limits.householdUploadQuotaBytes()) {
            throw UploadTooLargeException.quotaExceeded(used, Math.max(declaredSize, 0),
                limits.householdUploadQuotaBytes());
        }
    }

    /**
     * A file may only be imported into an account of its own kind.
     *
     * <p>Confirmed review finding 2. {@code POST /v1/accounts} accepts any of SIMPLEFIN, OFX, CSV
     * and MANUAL, and nothing tied the two together: a CSV uploaded against a MANUAL account
     * previewed happily with full counts, and the commit — which resolves the {@code BankSource}
     * from the ACCOUNT's kind, not the batch's — then answered 409 for ever. The batch was
     * stranded in PREVIEW, could never be committed, could never be undone (undo requires
     * COMMITTED) and its upload stayed on disk. Against a CSV account an OFX file was worse: it
     * previewed through one parser and would have committed through the other.
     *
     * <p>So the refusal happens at upload, where the member can act on it, and it is a 400 rather
     * than a 409 because it is the request that is wrong and re-sending it unchanged will never
     * succeed. Neither the file's contents nor the account's name appears in the message.
     */
    private static void requireCompatible(ConnectedAccount account, SourceKind format) {
        if (account.getSourceKind() != format) {
            throw new ValidationException("This is a " + format + " file and that account takes "
                + account.getSourceKind() + " data, so the file cannot be imported into it. "
                + "Upload it against a " + format + " account.");
        }
    }

    /**
     * The mapping the commit will actually use, persisted before it is used.
     *
     * <p>Persisted first so that the batch row always says what was imported with, whatever
     * happens next: a commit that fails halfway leaves a batch whose stored mapping is the one the
     * member confirmed, which is what makes the retry reproducible.
     */
    private ImportMapping supersede(ImportBatch batch, ImportCommitRequest request) {
        ImportMapping mapping = mappingOf(batch);
        if (request == null || request.mapping() == null) {
            requireDateFormat(batch, mapping);
            return mapping;
        }
        if (batch.getSourceKind() != SourceKind.CSV) {
            throw new ValidationException("An OFX file has no columns to map, so a mapping cannot "
                + "be supplied for this import.");
        }
        ImportMapping superseded = mapping.withCsv(request.mapping().applyTo(mapping.csv()));
        requireDateFormat(batch, superseded);
        undo.storeMapping(batch.getHouseholdId(), batch.getId(), superseded);
        return superseded;
    }

    /**
     * A CSV commit needs a settled date format, and the server will not pick one.
     *
     * <p>The message covers both ways the format can still be open, because the caller cannot tell
     * them apart from the refusal alone: the file's dates read equally well two ways — the preview
     * offered {@code dateFormatCandidates} and one of them comes back here — or nothing this
     * server knows reads them, in which case the candidate list is empty and the fix is a pattern
     * or a corrected date column (confirmed review finding 4).
     */
    private static void requireDateFormat(ImportBatch batch, ImportMapping mapping) {
        if (batch.getSourceKind() == SourceKind.CSV && mapping.csv() != null
            && mapping.csv().dateFormat() == null) {
            throw new ValidationException("This file's dates have not been settled, so the server "
                + "will not choose for you. Send one of the preview's dateFormatCandidates as "
                + "mapping.dateFormat; if that list was empty no known format read the column at "
                + "all, so send the pattern this file uses, or correct the mapped date column.");
        }
    }

    /** What the batch tells the source: where the file is, how to read it, and for which account. */
    private Map<String, String> configFor(ImportMapping mapping, ConnectedAccount account) {
        return Map.of(ImportConfig.STORAGE_KEY, mapping.storageKey(),
            ImportConfig.MAPPING, objectMapper.writeValueAsString(mapping),
            ImportConfig.ACCOUNT_ID, account.getId().toString());
    }

    /** Resolves — or creates — the account an OFX file names in its own {@code ACCTID}. */
    private ConnectedAccount upsertFromFile(UUID householdId, UUID userId, SourceKind format,
                                            ParsedUpload parsed) {
        if (parsed.externalAccountId() == null || parsed.externalAccountId().isBlank()) {
            throw new ValidationException("This file does not say which account its rows belong "
                + "to. Send accountId with the upload.");
        }
        return accounts.upsertProviderAccount(householdId, userId, format,
            parsed.externalAccountId(), displayNameFor(parsed), parsed.institution(),
            parsed.accountType() == null ? finance.trove.accounts.AccountType.OTHER
                : parsed.accountType(),
            parsed.currency(), null);
    }

    /**
     * A name for an account created from a file: the institution the file names, plus the last few
     * characters of the provider's account id so that two accounts at one bank are told apart.
     */
    private static String displayNameFor(ParsedUpload parsed) {
        String id = parsed.externalAccountId();
        String tail = id.length() <= 4 ? id : id.substring(id.length() - 4);
        String institution = parsed.institution() == null || parsed.institution().isBlank()
            ? "Imported account"
            : parsed.institution();
        return institution + " (" + tail + ")";
    }

    private FileImportSource fileSource(SourceKind format) {
        return sources.find(format)
            .filter(FileImportSource.class::isInstance)
            .map(FileImportSource.class::cast)
            .orElseThrow(() -> new ConflictException("No source is registered for " + format
                + " files on this install, so there is nothing to import them with."));
    }

    /**
     * Reads a file, turning a parser refusal into the caller's 400.
     *
     * <p>{@code BankSourceConnectionException} is what a source throws for a file it cannot read.
     * On the sync path that is an outcome recorded in a log; on the upload path it is the member's
     * own request being wrong, so it is a 400 with the parser's reason — which never quotes the
     * file's content.
     */
    private static ParsedUpload read(FileImportSource source, byte[] content, ImportMapping mapping,
                                     ImportContext context) {
        try {
            return source.read(content, mapping, context);
        } catch (BankSourceConnectionException e) {
            throw new ValidationException(e.getMessage());
        }
    }

    private ImportBatch require(UUID householdId, UUID batchId) {
        return batches.findByIdAndHouseholdId(batchId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Import", batchId));
    }

    private static ImportMapping mappingOf(ImportBatch batch) {
        ImportMapping mapping = batch.getMappingJson();
        if (mapping == null || mapping.storageKey() == null) {
            throw new ConflictException("This import batch has no stored file.");
        }
        return mapping;
    }

    /**
     * The browser's filename, reduced to something safe to store and show.
     *
     * <p>It is display text and nothing else — {@code LocalDiskStorage} mints its own keys, so this
     * value never reaches a path. Path separators and control characters are stripped anyway,
     * because it is echoed back into a response that a client renders.
     */
    static String displayFilename(String original) {
        if (original == null || original.isBlank()) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(Math.min(original.length(), MAX_FILENAME_CHARS));
        for (int i = 0; i < original.length() && cleaned.length() < MAX_FILENAME_CHARS; i++) {
            char c = original.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) {
                continue;
            }
            cleaned.append(c);
        }
        String text = cleaned.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
