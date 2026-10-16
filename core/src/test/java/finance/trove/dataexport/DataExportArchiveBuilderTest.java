package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.taxexport.CsvExportBuilder;
import finance.trove.taxexport.ExportRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The archive's shape, and the two claims gap row 16 makes about it that are easy to get wrong:
 * the manifest describes the archive that was actually written, and the transactions file goes
 * through {@link CsvExportBuilder} with its formula escaping intact.
 */
class DataExportArchiveBuilderTest {

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final Instant GENERATED_AT = Instant.parse("2026-09-06T04:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataExportArchiveBuilder builder =
        new DataExportArchiveBuilder(objectMapper, new CsvExportBuilder());

    private static ExportRow row(String merchant, String amount, CurrencyCode currency) {
        return new ExportRow(LocalDate.of(2025, 3, 4), merchant, "a description", "private notes",
            new BigDecimal(amount), currency, "groceries", "8523", "Meals", null, TransactionType.DEBIT, null, null, false);
    }

    private byte[] archive(List<ExportRow> transactions, Map<String, Object> datasets) {
        return builder.build(new DataExportArchiveBuilder.ArchiveRequest(RUN_ID, USER_ID,
            HOUSEHOLD_ID, GENERATED_AT, transactions, datasets));
    }

    private static Map<String, byte[]> entriesOf(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip),
            StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    private JsonNode manifestOf(byte[] zip) {
        return objectMapper.readTree(
            entriesOf(zip).get(DataExportArchiveBuilder.MANIFEST_ENTRY));
    }

    private static Map<String, Object> datasets() {
        Map<String, Object> datasets = new LinkedHashMap<>();
        datasets.put("accounts.json", List.of(Map.of("id", "a")));
        datasets.put("categories.json", List.of());
        datasets.put("receipts.json", List.of(Map.of("id", "r")));
        return datasets;
    }

    @Test
    @DisplayName("Every dataset handed in becomes an entry, and the manifest is the first of them")
    void everyDatasetIsWritten() {
        Map<String, byte[]> entries = entriesOf(archive(List.of(row("Bakery", "12.34",
            CurrencyCode.CAD)), datasets()));

        assertThat(entries.keySet())
            .as("a reader has to be able to find the manifest without scanning the archive")
            .startsWith(DataExportArchiveBuilder.MANIFEST_ENTRY)
            .containsExactly(DataExportArchiveBuilder.MANIFEST_ENTRY,
                DataExportArchiveBuilder.TRANSACTIONS_ENTRY,
                "accounts.json", "categories.json", "receipts.json");
    }

    @Test
    @DisplayName("The manifest's file list matches the entries actually written, byte counts too")
    void theManifestDescribesTheArchiveThatWasWritten() {
        byte[] zip = archive(List.of(row("Bakery", "12.34", CurrencyCode.CAD)), datasets());
        Map<String, byte[]> entries = entriesOf(zip);
        JsonNode manifest = manifestOf(zip);

        List<String> listed = new ArrayList<>();
        for (JsonNode file : manifest.path("files")) {
            String name = file.path("name").asString();
            listed.add(name);
            assertThat(file.path("sizeBytes").asLong())
                .as("the manifest must state the size of %s that this archive really holds", name)
                .isEqualTo(entries.get(name).length);
        }

        assertThat(listed)
            .as("a file listed but not written, or written but not listed, is exactly what a "
                + "manifest exists to make impossible")
            .containsExactlyInAnyOrderElementsOf(
                entries.keySet().stream()
                    .filter(name -> !DataExportArchiveBuilder.MANIFEST_ENTRY.equals(name))
                    .toList());
    }

    @Test
    @DisplayName("The manifest carries the schema version, the run, the member and the household")
    void theManifestCarriesItsProvenance() {
        JsonNode manifest = manifestOf(archive(List.of(), datasets()));

        assertThat(manifest.path("schemaVersion").asInt())
            .as("a reader years from now has to be able to tell what shape this is")
            .isEqualTo(DataExportArchiveBuilder.SCHEMA_VERSION);
        assertThat(manifest.path("runId").asString()).isEqualTo(RUN_ID.toString());
        assertThat(manifest.path("userId").asString()).isEqualTo(USER_ID.toString());
        assertThat(manifest.path("householdId").asString()).isEqualTo(HOUSEHOLD_ID.toString());
        assertThat(manifest.path("generatedAt").asString()).isEqualTo(GENERATED_AT.toString());
        assertThat(manifest.path("serverVersion").asString())
            .as("present even outside a packaged jar, where it reads 'unknown'")
            .isNotBlank();
    }

    @Test
    @DisplayName("The manifest says what the archive does NOT contain, in the member's terms")
    void theManifestStatesTheReceiptAndCurrencyDecisions() {
        JsonNode manifest = manifestOf(archive(List.of(), datasets()));

        assertThat(manifest.path("receipts").asString())
            .as("a member opening this has to be told the images are not in it, or they will "
                + "believe they have a complete backup and delete the originals")
            .contains("metadata only")
            .contains("no storage key");
        assertThat(manifest.path("amounts").asString())
            .as("owner decision 2 and risk 15: no blended total, ever")
            .contains("carries its own currency")
            .contains("no total is blended")
            .as("the full ledger file carries no totals section, so nothing in it is computed "
                + "and it stays rectangular for an importer (B2-03)")
            .contains("No figure in this archive is computed")
            .contains("transactions.csv");
        assertThat(manifest.path("ledgerScope").asString())
            .as("\"my export\" containing a household mate's spending surprises anybody who was "
                + "not told")
            .contains("household")
            .as("the manifest names what is shared and what is the member's own, so its text "
                + "cannot drift from the entry list without this failing")
            .contains("subscription alerts")
            .contains("profile, webhooks, notifications, coach insights")
            .contains("userId");
    }

    @Test
    @DisplayName("transactions.csv is CsvExportBuilder's, with its exact header set")
    void theTransactionsFileIsTheSharedBuilders() {
        byte[] zip = archive(List.of(row("Bakery", "12.34", CurrencyCode.CAD)), datasets());
        List<CSVRecord> records = parse(entriesOf(zip)
            .get(DataExportArchiveBuilder.TRANSACTIONS_ENTRY));

        assertThat(records.get(0).toList())
            .as("collapse step 18 reuses the column list and must never widen it: a second "
                + "header set means a second copy of the escaping to get wrong")
            .containsExactlyElementsOf(
                Stream.concat(CsvExportBuilder.HEADERS.stream(),
                    CsvExportBuilder.LEDGER_HEADERS.stream()).toList());
    }

    @Test
    @DisplayName("A merchant named =HYPERLINK(...) round-trips escaped through THIS pipeline")
    void theFormulaInjectionRegressionIsClosedEndToEnd() {
        // Gap row 16's named regression, asserted through the archive rather than through the
        // builder alone: the claim being made is that the FULL EXPORT is safe to open, and a
        // pipeline that reached the CSV some other way would pass CsvExportBuilderTest and fail
        // the member.
        String hostile = "=HYPERLINK(\"http://evil\",\"click\")";
        byte[] zip = archive(List.of(row(hostile, "12.34", CurrencyCode.CAD)), datasets());

        String csv = new String(entriesOf(zip).get(DataExportArchiveBuilder.TRANSACTIONS_ENTRY),
            StandardCharsets.UTF_8);
        assertThat(csv)
            .as("on the wire the cell is CSV-quoted, so the assertion is on the apostrophe that "
                + "immediately precedes the equals sign — a spreadsheet evaluates a cell that "
                + "begins with '=', and the apostrophe is what makes it text")
            .contains("\"'=HYPERLINK(")
            .doesNotContain(",=HYPERLINK(");
        assertThat(parse(entriesOf(zip).get(DataExportArchiveBuilder.TRANSACTIONS_ENTRY))
            .get(1).get(1))
            .as("and the value is still readable after the escape — it is neutralised, not lost")
            .isEqualTo("'" + hostile);
    }

    @Test
    @DisplayName("A negative amount is escaped too, because a minus starts a formula as well")
    void aNegativeAmountIsEscaped() {
        byte[] zip = archive(List.of(row("Refund", "-12.34", CurrencyCode.CAD)), datasets());

        assertThat(parse(entriesOf(zip).get(DataExportArchiveBuilder.TRANSACTIONS_ENTRY))
            .get(1).get(4))
            .as("exempting cells that happen to parse as numbers would be a second rule about "
                + "which cells are dangerous, and the value of the one rule is that there is no "
                + "second one to get wrong")
            .isEqualTo("'-12.3400");
    }

    @Test
    @DisplayName("An empty account still produces a complete, readable archive")
    void anEmptyAccountStillProducesAnArchive() {
        byte[] zip = archive(List.of(), Map.of("accounts.json", List.of()));

        Map<String, byte[]> entries = entriesOf(zip);
        assertThat(entries).containsKeys(DataExportArchiveBuilder.MANIFEST_ENTRY,
            DataExportArchiveBuilder.TRANSACTIONS_ENTRY, "accounts.json");
        assertThat(new String(entries.get("accounts.json"), StandardCharsets.UTF_8))
            .as("an empty dataset is an empty array, not a missing file: a reader must be able "
                + "to tell 'you have no bank connections' from 'this export skipped them'")
            .isEqualTo("[]");
    }

    private static List<CSVRecord> parse(byte[] content) {
        try (CSVParser parser = CSVParser.parse(
            new StringReader(new String(content, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "")), CSVFormat.DEFAULT)) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
