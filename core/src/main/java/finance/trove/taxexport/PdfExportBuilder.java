package finance.trove.taxexport;

import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.UnitValue;
import finance.trove.common.domain.Jurisdiction;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Renders the same {@link ExportRow}s {@link CsvExportBuilder} does, as a printable PDF
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9 — the source's {@code PdfReportBuilder}, kept
 * and renamed).
 *
 * <p><strong>The columns are the CSV's columns.</strong> {@link CsvExportBuilder#HEADERS} is the
 * single list; this class renders it rather than declaring a second one, so the two files cannot
 * disagree about what an export contains. The source rendered six different columns from the ten
 * it wrote to CSV, which is how a member could reconcile one file and not the other.
 *
 * <p><strong>No system font path, and no network access.</strong> Every string is drawn in iText's
 * built-in Helvetica, one of the fourteen standard Type 1 fonts, which needs no file from the
 * host and no download. A font loaded from {@code /usr/share/fonts} would be a path that exists on
 * a developer's machine and not in the container; a downloaded one would be an outbound call this
 * step is specified never to make (§2.4 — this step opens no socket at all).
 *
 * <p><strong>No computed figure.</strong> Same rule as the CSV: line codes and labels are strings
 * a human reads off a form, the amounts are the member's own, and the totals are plain sums per
 * {@code (line, currency)} with no conversion (§9 risk 15).
 *
 * <p>No formula escaping here, deliberately: a PDF has no formula evaluator, and an apostrophe in
 * front of a printed merchant name would be a defect rather than a defence.
 */
@Component
public class PdfExportBuilder {

    private static final float TITLE_SIZE = 16f;
    private static final float SUBTITLE_SIZE = 10f;
    private static final float TABLE_SIZE = 7f;

    /** Relative widths for the ten columns; the wide text columns get the space. */
    private static final float[] COLUMN_WIDTHS = {8, 13, 15, 13, 8, 5, 10, 7, 11, 4, 6};

    /** The scale every ledger amount is stored and summed at, from {@code NUMERIC(19,4)}. */
    private static final int LEDGER_SCALE = 4;

    /**
     * Builds the document.
     *
     * @param rows         the year's transactions, already resolved to lines
     * @param taxYear      the calendar year the window covers, printed on the cover line
     * @param jurisdiction which form set the line codes are read off
     */
    public byte[] build(List<ExportRow> rows, int taxYear, Jurisdiction jurisdiction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Landscape: ten columns do not fit portrait at a readable size, and a PDF a member
        // cannot read is not evidence of anything.
        // The writer is not itself a resource here: PdfDocument.close() closes it, and
        // PdfWriter.close() declares IOException while PdfDocument's and Document's do not.
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(out));
             Document document = new Document(pdf, PageSize.A4.rotate())) {
            document.add(new Paragraph("Trove tax export " + taxYear + " (" + jurisdiction + ")")
                .setFontSize(TITLE_SIZE).simulateBold());
            document.add(new Paragraph(
                "Transactions dated 1 January to 31 December " + taxYear + ", inclusive. "
                    + "Amounts and labels only: this document contains no computed tax figure.")
                .setFontSize(SUBTITLE_SIZE));
            if (rows.isEmpty()) {
                // A table whose body has no cells is not a document iText will lay out, and an
                // empty year is a perfectly ordinary answer — a member who joined in March asking
                // for last year gets one. Say so rather than fail the run.
                document.add(new Paragraph("No transactions in this window.")
                    .setFontSize(SUBTITLE_SIZE));
            } else {
                document.add(transactionsTable(rows));
                document.add(new Paragraph(CsvExportBuilder.TOTALS_SECTION_TITLE)
                    .setFontSize(TITLE_SIZE).simulateBold());
                document.add(totalsTable(rows));
            }
        }
        return out.toByteArray();
    }

    private static Table transactionsTable(List<ExportRow> rows) {
        Table table = new Table(UnitValue.createPercentArray(COLUMN_WIDTHS))
            .useAllAvailableWidth().setFontSize(TABLE_SIZE);
        for (String header : CsvExportBuilder.HEADERS) {
            table.addHeaderCell(new Cell().add(new Paragraph(header).simulateBold()));
        }
        for (ExportRow row : rows) {
            table.addCell(text(row.date()));
            table.addCell(text(row.merchant()));
            table.addCell(text(row.description()));
            table.addCell(text(row.notes()));
            table.addCell(amount(row.amount()));
            table.addCell(row.currency() == null ? "" : row.currency().name());
            table.addCell(text(row.category()));
            table.addCell(text(row.taxLineCode()));
            table.addCell(text(row.taxLineLabel()));
            table.addCell(text(row.receiptId()));
            table.addCell(text(row.type()));
        }
        return table;
    }

    private static Table totalsTable(List<ExportRow> rows) {
        Table table = new Table(UnitValue.createPercentArray(new float[] {10, 45, 10, 15}))
            .useAllAvailableWidth().setFontSize(TABLE_SIZE);
        for (String header : CsvExportBuilder.TOTALS_HEADERS) {
            table.addHeaderCell(new Cell().add(new Paragraph(header).simulateBold()));
        }
        for (CsvExportBuilder.LineTotal total : CsvExportBuilder.totalsByLineAndCurrency(rows)) {
            table.addCell(total.code());
            table.addCell(total.label());
            table.addCell(total.currency().name());
            table.addCell(amount(total.total()));
        }
        return table;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String amount(BigDecimal value) {
        return value == null
            ? ""
            : value.setScale(LEDGER_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
