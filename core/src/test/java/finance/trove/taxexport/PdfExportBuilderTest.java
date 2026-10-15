package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.domain.Jurisdiction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §9 risk 15's second half: the PDF renders the same columns as the CSV and carries no computed
 * figure either.
 *
 * <p>The assertions read the PDF's <em>extracted text</em> rather than its raw bytes: iText
 * compresses content streams, so a byte grep would pass for the wrong reason. Extraction is the
 * stronger claim — it is what a person actually reads off the page.
 */
class PdfExportBuilderTest {

    private final PdfExportBuilder builder = new PdfExportBuilder();

    private static List<ExportRow> sampleRows() {
        return List.of(
            new ExportRow(LocalDate.of(2025, 6, 1), "Office Depot", "Printer paper", "For the desk",
                new BigDecimal("42.5000"), CurrencyCode.CAD, "Office Supplies", "8810",
                "T2125 line 8810 - Office expenses", null, TransactionType.DEBIT),
            new ExportRow(LocalDate.of(2025, 6, 2), "Corner Shop", "Milk", null,
                new BigDecimal("3.2500"), CurrencyCode.USD, "Groceries", "NONE",
                "No tax line - personal expense", null, TransactionType.DEBIT));
    }

    private static String textOf(byte[] pdf) {
        StringBuilder text = new StringBuilder();
        try (PdfDocument document =
                 new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The builder did not produce a readable PDF", e);
        }
        return text.toString();
    }

    @Test
    @DisplayName("The bytes are a real PDF and carry the CSV's column headings")
    void thePdfCarriesTheSameColumns() {
        byte[] pdf = builder.build(sampleRows(), 2025, Jurisdiction.CA);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text)
            .as("§2.9 gives one column list; the source rendered six of its ten columns to PDF, "
                + "which is how a member could reconcile one file and not the other")
            .contains(CsvExportBuilder.HEADERS);
    }

    @Test
    @DisplayName("The rows, their currencies and the per-line totals are on the page")
    void theRowsAndTotalsAreRendered() {
        String text = textOf(builder.build(sampleRows(), 2025, Jurisdiction.CA));

        assertThat(text).contains("Office Depot", "For the desk", "42.5000", "CAD", "8810");
        assertThat(text).contains("Corner Shop", "3.2500", "USD");
        assertThat(text)
            .as("two currencies stay two totals; nothing is converted")
            .contains(CsvExportBuilder.TOTALS_SECTION_TITLE);
    }

    @Test
    @DisplayName("No computed figure reaches the page: gst, tax_owing and rate are absent")
    void noComputedFigureReachesThePage() {
        String text = textOf(builder.build(sampleRows(), 2025, Jurisdiction.CA))
            .toLowerCase(Locale.ROOT);

        assertThat(text)
            .as("§9 risk 15. Note that this also forbids the innocent-looking word 'Generated', "
                + "which contains 'rate' — the cover line says 'Trove tax export' instead")
            .doesNotContain("gst")
            .doesNotContain("tax_owing")
            .doesNotContain("rate");
    }

    @Test
    @DisplayName("An empty year renders a document rather than failing the run")
    void anEmptyYearStillRenders() {
        byte[] pdf = builder.build(List.of(), 2025, Jurisdiction.US);

        assertThat(textOf(pdf))
            .as("a member who joined in March asking for last year is an ordinary case, not an "
                + "error; a table with no body cells is not something iText will lay out")
            .contains("No transactions in this window.");
    }
}
