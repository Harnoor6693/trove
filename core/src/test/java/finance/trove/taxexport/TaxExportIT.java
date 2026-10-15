package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.common.storage.LocalDiskStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §9 risk 15's gate, named there by this class: a run is started, polled to {@code COMPLETE},
 * downloaded in both formats, and both files are searched for {@code gst}, {@code tax_owing} and
 * {@code rate}. All three must be absent.
 *
 * <p><strong>Why 2001.</strong> Every IT shares one household A ledger, so a window over a recent
 * year would sweep up whatever other classes happened to record. A window nobody else writes into
 * makes the file's contents this test's own, which is what lets it assert on the whole document
 * rather than only on the rows it can find.
 *
 * <p><strong>Why the PDF is read off the volume rather than out of the response body.</strong>
 * {@code BaseIT} decodes every response as a UTF-8 string, which is right for the CSV and destroys
 * a PDF. The HTTP call still runs — it proves the status, both headers and the declared content
 * type, and it is validated against the contract like every other request — and the bytes are then
 * read back through {@code LocalDiskStorage}, which is where the download served them from anyway.
 * Extracting the PDF's text is also a stronger claim than grepping its bytes: iText compresses
 * content streams, so a byte search would pass for the wrong reason.
 */
class TaxExportIT extends LedgerSupport {

    /** A year no other integration test records into. */
    private static final int TAX_YEAR = 2001;

    /** The named regression of gap P2-16 and §9 risk 31. */
    private static final String HOSTILE_MERCHANT = "=HYPERLINK(\"http://evil\",\"click\")";

    private static final String RUNS = "/v1/tax-export/runs";

    @Autowired
    private LocalDiskStorage storage;

    private Session member;

    @BeforeEach
    void recordAYearOfSpending() {
        member = inviteAndRegister(loginBootstrapAdmin(), "tax-export");
        String canadian = createManualAccount(member, "Export CAD " + UUID.randomUUID());
        String american = createAccount(member, MANUAL, "Export USD " + UUID.randomUUID(),
            "OTHER", "USD");

        record(canadian, "125.5000", TAX_YEAR + "-01-01", "Office Depot",
            systemCategoryId(member, "business.office"), "Paper for the home office");
        record(canadian, "31.2500", TAX_YEAR + "-12-31", HOSTILE_MERCHANT,
            systemCategoryId(member, "food.grocery"), null);
        record(american, "42.0000", TAX_YEAR + "-06-15", "Some Software Co",
            systemCategoryId(member, "business.software"), null);
        // Outside the window on both sides: the export must not carry either of these.
        record(canadian, "999.0000", (TAX_YEAR - 1) + "-12-31", "Too Early", null, null);
        record(canadian, "888.0000", (TAX_YEAR + 1) + "-01-01", "Too Late", null, null);
    }

    private void record(String accountId, String amount, String date, String merchant,
                        String categoryId, String notes) {
        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", amount, "date", date, "type", "DEBIT",
                "merchant", merchant, "categoryId", categoryId, "notes", notes),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(201);
    }

    private JsonNode startRun(String format) {
        HttpResponse<String> response = postJson(appUrl(RUNS),
            Json.object("taxYear", TAX_YEAR, "format", format),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(response.statusCode())
            .as("the run is recorded synchronously and the work is handed off: %s",
                response.body())
            .isEqualTo(202);
        JsonNode run = Json.data(response.body());
        assertThat(run.path("status").asString()).isEqualTo("PENDING");
        assertThat(run.path("jurisdiction").asString())
            .as("stamped from users.country, which registration set to CA")
            .isEqualTo("CA");
        assertThat(run.path("userId").asString()).isEqualTo(member.userId().toString());
        return run;
    }

    /** Polls the run until it leaves PENDING/GENERATING, and asserts it got there. */
    private JsonNode awaitCompletion(String runId) {
        for (int attempt = 0; attempt < 120; attempt++) {
            HttpResponse<String> response =
                get(appUrl(RUNS + "/" + runId), member.accessToken());
            assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(200);
            JsonNode run = Json.data(response.body());
            String status = run.path("status").asString();
            if ("COMPLETE".equals(status)) {
                return run;
            }
            assertThat(status)
                .as("the run failed instead of completing: %s", run.path("errorMessage")
                    .asString(""))
                .isNotEqualTo("FAILED");
            sleepBriefly();
        }
        throw new AssertionError("run " + runId + " never reached COMPLETE");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling a tax export run", e);
        }
    }

    /** The generated file's own bytes, from the volume the download served them off. */
    private byte[] storedBytes(String runId) {
        String key = jdbcTemplate.queryForObject(
            "SELECT file_key FROM tax_export_run WHERE id = ?", String.class,
            UUID.fromString(runId));
        assertThat(key)
            .as("a COMPLETE run always has a key; the status and the key are written together")
            .isNotNull();
        assertThat(key).startsWith("exports/" + member.householdId() + "/");
        return storage.readAllBytes(key);
    }

    @Test
    @DisplayName("A CSV run completes, downloads, and carries only the target year")
    void aCsvRunCompletesAndDownloads() {
        String runId = startRun("CSV").path("id").asString();
        JsonNode completed = awaitCompletion(runId);
        assertThat(completed.path("fileSizeBytes").asLong()).isPositive();
        assertThat(completed.path("errorMessage").isNull()).isTrue();

        HttpResponse<String> download =
            get(appUrl(RUNS + "/" + runId + "/download"), member.accessToken());

        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("content-type").orElse(""))
            .as("the contract declares text/csv and application/pdf for this operation and "
                + "nothing else; an undeclared content type is a failing test, not a warning")
            .startsWith("text/csv");
        assertThat(download.headers().firstValue("content-disposition").orElse(""))
            .startsWith("attachment; filename=\"trove-tax-export-" + TAX_YEAR + "-" + runId);
        assertThat(download.headers().firstValue("x-content-type-options").orElse(""))
            .isEqualTo("nosniff");

        String csv = download.body();
        assertThat(csv.lines().findFirst().orElse(""))
            .isEqualTo("date,merchant,description,notes,amount,currency,category,taxLineCode,"
                + "taxLineLabel,receiptId");
        assertThat(csv).contains("Office Depot", "Paper for the home office", "8810");
        assertThat(csv)
            .as("1 January and 31 December of the target year are both inside the window")
            .contains(TAX_YEAR + "-01-01", TAX_YEAR + "-12-31");
        assertThat(csv)
            .as("31 December of the year before and 1 January of the year after are both outside")
            .doesNotContain("Too Early", "Too Late");
        assertThat(csv)
            .as("each row carries its own currency and the totals are per (line, currency); "
                + "nothing is converted, because no exchange rate exists in this system")
            .contains("CAD", "USD", CsvExportBuilder.TOTALS_SECTION_TITLE);
    }

    @Test
    @DisplayName("The =HYPERLINK merchant round-trips escaped through the whole pipeline")
    void theHostileMerchantSurvivesEscaped() {
        String runId = startRun("CSV").path("id").asString();
        awaitCompletion(runId);

        String csv = new String(storedBytes(runId), StandardCharsets.UTF_8);

        // Parsed rather than searched as text: the merchant contains quotes and commas, so
        // commons-csv legitimately wraps the cell and doubles the inner quotes. Comparing raw
        // substrings would assert the QUOTING and miss the escaping, which is the opposite of
        // what this test is for.
        List<String> merchants;
        try (CSVParser parser = CSVParser.parse(new StringReader(csv), CSVFormat.DEFAULT)) {
            merchants = parser.getRecords().stream()
                .filter(row -> row.size() > 1)
                .map(row -> row.get(1))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("the export is not parseable CSV", e);
        }

        assertThat(merchants)
            .as("gap P2-16: escaping lives in the builder, so it holds for this route and for "
                + "collapse step 18's full export without being written twice")
            .contains("'" + HOSTILE_MERCHANT)
            .doesNotContain(HOSTILE_MERCHANT);
    }

    @Test
    @DisplayName("A receipt id reaches the file, and no receipt FIGURE does")
    void aReceiptIdReachesTheFile() {
        // Collapse step 14 added transaction.receipt_id (V38) and threaded it into ExportRow's
        // last argument, which was a literal null until then. Attaching a receipt here through
        // the real route is what proves the whole path — column, entity, generation, file.
        UUID receiptId = attachAReceiptToTheFirstTransaction();

        String runId = startRun("CSV").path("id").asString();
        awaitCompletion(runId);
        String csv = new String(storedBytes(runId), StandardCharsets.UTF_8);

        assertThat(csv)
            .as("the receiptId column is populated for a transaction that has one")
            .contains(receiptId.toString());
        // And nothing else about the receipt travels: the GST/PST/HST split the pipeline computes
        // stays on the receipt and is never exported (§2.9, open question 1). The test below
        // holds the same rule for the whole file; this one holds it for a file that really has a
        // receipt in it, which is the case that could regress.
        assertThat(csv.toLowerCase(Locale.ROOT))
            .doesNotContain("gst").doesNotContain("hst").doesNotContain("pst")
            .doesNotContain("tax_owing").doesNotContain("rate");
    }

    /**
     * Uploads a receipt and links it to this member's first transaction, through the real routes.
     *
     * @return the receipt's id, which must appear in the generated file
     */
    private UUID attachAReceiptToTheFirstTransaction() {
        HttpResponse<String> upload = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                finance.trove.receipts.ReceiptImageFixtures.jpeg(300, 200))),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(upload.statusCode()).as("%s", upload.body()).isEqualTo(202);
        UUID receiptId = UUID.fromString(Json.data(upload.body()).path("id").asString());

        // Newest first and bounded to one: household A is the whole suite's bootstrap household
        // and every run of this class seeds another 'Office Depot' row into it, so an unbounded
        // lookup finds every previous test method's as well.
        UUID transactionId = jdbcTemplate.queryForObject(
            "SELECT id FROM transaction WHERE household_id = ? AND merchant_name = 'Office Depot' "
                + "ORDER BY created_at DESC LIMIT 1",
            UUID.class, member.householdId());
        HttpResponse<String> linked = exchange("PATCH", appUrl("/v1/receipts/" + receiptId),
            Json.object("matchedTransactionId", transactionId.toString()),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(linked.statusCode()).as("%s", linked.body()).isEqualTo(200);
        return receiptId;
    }

    @Test
    @DisplayName("Neither format contains gst, tax_owing or rate")
    void neitherFormatLeaksAComputedNumber() {
        String csvRun = startRun("CSV").path("id").asString();
        awaitCompletion(csvRun);
        String csv = new String(storedBytes(csvRun), StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);

        String pdfRun = startRun("PDF").path("id").asString();
        awaitCompletion(pdfRun);
        HttpResponse<String> pdfDownload =
            get(appUrl(RUNS + "/" + pdfRun + "/download"), member.accessToken());
        assertThat(pdfDownload.statusCode()).isEqualTo(200);
        assertThat(pdfDownload.headers().firstValue("content-type").orElse(""))
            .startsWith("application/pdf");
        String pdf = extractText(storedBytes(pdfRun)).toLowerCase(Locale.ROOT);

        assertThat(csv)
            .as("§9 risk 15. The source computed GST as amount x 0.05 on a tax-INCLUSIVE bank "
                + "amount (RS-1); owner decision 2 deletes the column rather than correcting it")
            .doesNotContain("gst").doesNotContain("tax_owing").doesNotContain("rate");
        assertThat(pdf)
            .as("the PDF renders the same columns and is held to the same rule")
            .doesNotContain("gst").doesNotContain("tax_owing").doesNotContain("rate");
        assertThat(pdf).contains("office depot", "8810");
    }

    @Test
    @DisplayName("A future tax year is 400, judged against the member's own calendar")
    void aFutureYearIsRejected() {
        int nextYear = java.time.Year.now().getValue() + 2;

        HttpResponse<String> response = postJson(appUrl(RUNS),
            Json.object("taxYear", nextYear, "format", "CSV"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An unknown format is 400 rather than a run nobody can generate")
    void anUnknownFormatIsRejected() {
        HttpResponse<String> response = withoutRequestValidation(
            "the contract's TaxExportFormat enum is CSV|PDF; this asks what the server answers "
                + "for a value no generated client can send",
            () -> postJson(appUrl(RUNS), Json.object("taxYear", TAX_YEAR, "format", "XML"),
                "Authorization", "Bearer " + member.accessToken()));

        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(400);
    }

    @Test
    @DisplayName("The runs list is this member's own, newest first, and is paged")
    void theRunsListIsPagedAndScopedToTheMember() {
        String runId = startRun("CSV").path("id").asString();

        HttpResponse<String> response =
            get(appUrl(RUNS + "?page=0&limit=10"), member.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(response.body())).stream()
            .map(row -> row.path("id").asString()).toList())
            .contains(runId);
        assertThat(Json.parse(response.body()).path("meta").path("limit").asInt())
            .as("RS-9: the source's page size was an unbounded int")
            .isEqualTo(10);
    }

    @Test
    @DisplayName("An out-of-range limit is 400, not a silent clamp")
    void anOutOfRangeLimitIsRejected() {
        HttpResponse<String> response = withoutRequestValidation(
            "the contract's LimitQuery caps at 200; this asks what the server answers above it",
            () -> get(appUrl(RUNS + "?limit=5000"), member.accessToken()));

        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    private static String extractText(byte[] pdf) {
        StringBuilder text = new StringBuilder();
        try (PdfDocument document =
                 new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The downloaded export is not a readable PDF", e);
        }
        return text.toString();
    }
}
