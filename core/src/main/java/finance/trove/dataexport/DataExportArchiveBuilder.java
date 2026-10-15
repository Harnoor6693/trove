package finance.trove.dataexport;

import finance.trove.taxexport.CsvExportBuilder;
import finance.trove.taxexport.ExportRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one member's collected data into the ZIP the download serves (collapse step 18; gap row
 * 16).
 *
 * <h2>The transactions file is {@code CsvExportBuilder}'s, unchanged</h2>
 * {@link CsvExportBuilder#build(List)} takes ONE fixed column list and appends a tax-line totals
 * section, and this step neither widens it nor copies its escaping. That escaping — a leading
 * apostrophe on any cell beginning with an equals sign, a plus, a minus, an at sign, a tab or a
 * carriage return — is the named regression of gap row 16, and the entire value of it living in
 * one class is that there is exactly one copy to get right. A merchant literally called
 * {@code =HYPERLINK("http://evil","click")} therefore arrives escaped through this pipeline for
 * the same reason it does through the tax export, and {@code DataExportArchiveBuilderTest} proves
 * it end to end rather than trusting the shared code.
 *
 * <h2>Everything that is not a transaction is JSON</h2>
 * Which is what gap row 16 asks for, and which needs no formula escaping at all because no
 * spreadsheet evaluates a JSON string. It also means the builder never has to answer "what are
 * this dataset's columns?" for thirteen different shapes.
 *
 * <h2>The manifest lists what was actually written</h2>
 * Not what was meant to be. {@link #build} renders every entry into bytes first, then builds
 * {@code manifest.json} from that map, so a dataset that came back empty and a dataset that was
 * skipped are distinguishable by a reader, and a file listed in the manifest is always a file in
 * the archive. The schema version is there so that a future reader can tell what it is looking at.
 */
@Component
public class DataExportArchiveBuilder {

    /** The archive format's own version. Bumped when an entry's shape changes meaning. */
    public static final int SCHEMA_VERSION = 1;

    /** The manifest, always the first entry. */
    public static final String MANIFEST_ENTRY = "manifest.json";

    /** The transactions file, written by {@link CsvExportBuilder} and nothing else. */
    public static final String TRANSACTIONS_ENTRY = "transactions.csv";

    private final ObjectMapper objectMapper;
    private final CsvExportBuilder csvBuilder;

    public DataExportArchiveBuilder(ObjectMapper objectMapper, CsvExportBuilder csvBuilder) {
        this.objectMapper = objectMapper;
        this.csvBuilder = csvBuilder;
    }

    /**
     * Everything one archive is built from.
     *
     * @param runId        the {@code data_export_run} row this archive belongs to
     * @param userId       the member who asked for it
     * @param householdId  the ledger the rows came from — shared, and the manifest says so
     * @param generatedAt  the instant the archive was built, in UTC
     * @param transactions the household's whole spendable history, already transfer-leg-free
     * @param datasets     entry name to serialisable content, in the order they are written
     */
    public record ArchiveRequest(UUID runId, UUID userId, UUID householdId, Instant generatedAt,
                                 List<ExportRow> transactions, Map<String, Object> datasets) {
    }

    /** Builds the whole archive. Never returns null. */
    public byte[] build(ArchiveRequest request) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(TRANSACTIONS_ENTRY, csvBuilder.build(request.transactions()));
        for (Map.Entry<String, Object> dataset : request.datasets().entrySet()) {
            entries.put(dataset.getKey(), json(dataset.getValue()));
        }

        byte[] manifest = json(manifest(request, entries));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            write(zip, MANIFEST_ENTRY, manifest, request.generatedAt());
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                write(zip, entry.getKey(), entry.getValue(), request.generatedAt());
            }
        } catch (IOException e) {
            // A ByteArrayOutputStream does no I/O, so this is unreachable in practice. It is
            // rethrown rather than swallowed so that it can never become a silently truncated
            // archive that a member believes is their whole account.
            throw new UncheckedIOException("Could not assemble the export archive", e);
        }
        return out.toByteArray();
    }

    /**
     * The manifest.
     *
     * <p>It carries no storage key, no path and no URL — only names that exist inside this
     * archive. {@code householdId} is here because the rows are the household's and a reader has
     * to be able to tell; it is not a credential and it is already the member's own household.
     */
    private static Map<String, Object> manifest(ArchiveRequest request,
                                                Map<String, byte[]> entries) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            files.add(fileEntry(entry.getKey(), entry.getValue().length));
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("generator", "trove-core");
        manifest.put("serverVersion", serverVersion());
        manifest.put("runId", request.runId().toString());
        manifest.put("userId", request.userId().toString());
        manifest.put("householdId", request.householdId().toString());
        manifest.put("generatedAt", request.generatedAt().toString());
        manifest.put("ledgerScope",
            "The transactions, accounts, categories, rules, budgets, envelopes, goals, snapshots, "
                + "assets, liabilities, subscriptions and receipts in this archive belong to the "
                + "household and are shared with its other members. The run itself belongs to the "
                + "member named by userId.");
        manifest.put("receipts",
            "Receipts are exported as metadata only: no image bytes, no storage key and no URL. "
                + "The images stay with the household and are downloaded from the receipts API.");
        manifest.put("amounts",
            "Every amount is a decimal string at the ledger's four-decimal scale and carries its "
                + "own currency. Nothing is converted and no total is blended across currencies. "
                + "The only computed figures in this archive are the plain per-currency sums in "
                + "the totals section the shared builder appends under the last row of "
                + TRANSACTIONS_ENTRY + ": one row per tax line per currency, added and nothing "
                + "else.");
        // Every entry except this one. The manifest cannot state its own size without changing
        // it, and a self-referential count that is wrong the moment it is written is worse than
        // an absence a reader can see.
        manifest.put("files", files);
        return manifest;
    }

    private static Map<String, Object> fileEntry(String name, long sizeBytes) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("sizeBytes", sizeBytes);
        return entry;
    }

    /**
     * The build's own version, or {@code "unknown"} outside a packaged jar.
     *
     * <p>Read from the jar manifest rather than injected: this class has no business holding a
     * Spring {@code BuildProperties} bean that is absent in half the ways the application runs,
     * and an archive whose provenance line reads {@code unknown} is still honest.
     */
    private static String serverVersion() {
        String version = DataExportArchiveBuilder.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    private byte[] json(Object value) {
        return objectMapper.writeValueAsBytes(value);
    }

    private static void write(ZipOutputStream zip, String name, byte[] content, Instant at)
        throws IOException {
        ZipEntry entry = new ZipEntry(name);
        // A fixed timestamp rather than "now": two archives of the same data then differ only
        // where the data differs, which is what makes an archive comparable to the one before it.
        entry.setTime(at.toEpochMilli());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
