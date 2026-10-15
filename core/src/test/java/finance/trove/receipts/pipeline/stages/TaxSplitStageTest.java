package finance.trove.receipts.pipeline.stages;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.taxsplit.TaxSplitService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The CA gate on the tax split (collapse step 14 scope item 5). */
class TaxSplitStageTest {

    private final TaxSplitStage stage = new TaxSplitStage(new TaxSplitService());

    private static Receipt receiptOf(String total) {
        Receipt receipt = new Receipt(UUID.randomUUID(), UUID.randomUUID(), ReceiptSource.UPLOAD,
            100L);
        receipt.setExtractedAmount(new BigDecimal(total));
        return receipt;
    }

    @Test
    @DisplayName("a Canadian member's receipt gets the split for their own province")
    void canadianMembersGetASplit() throws Exception {
        Receipt receipt = receiptOf("113.00");
        stage.process(receipt,
            new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD));

        assertThat(receipt.getHstAmount()).isEqualByComparingTo(new BigDecimal("13.00"));
    }

    @Test
    @DisplayName("a US member's receipt gets NO split at all — not even three zeros")
    void americanMembersGetNoSplit() throws Exception {
        Receipt receipt = receiptOf("113.00");
        stage.process(receipt,
            new ReceiptProcessingContext(Jurisdiction.US, "NY", CurrencyCode.USD));

        // Null rather than zero: "does not apply" and "computed to be nothing" are different
        // statements, and a screen that renders 0.00 GST for an American member is claiming the
        // second.
        assertThat(receipt.getGstAmount()).isNull();
        assertThat(receipt.getPstAmount()).isNull();
        assertThat(receipt.getHstAmount()).isNull();
    }

    @Test
    @DisplayName("a member with no country gets no split")
    void noCountryMeansNoSplit() throws Exception {
        Receipt receipt = receiptOf("113.00");
        stage.process(receipt, new ReceiptProcessingContext(null, null, null));
        assertThat(receipt.getHstAmount()).isNull();
    }

    @Test
    @DisplayName("a Canadian member with no total gets no split")
    void noTotalMeansNoSplit() throws Exception {
        Receipt receipt = new Receipt(UUID.randomUUID(), UUID.randomUUID(), ReceiptSource.UPLOAD,
            100L);
        stage.process(receipt,
            new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD));
        assertThat(receipt.getHstAmount()).isNull();
    }

    @Test
    @DisplayName("a Canadian member with no province on their row gets three zeros, not a guess")
    void noProvinceMeansZeros() throws Exception {
        Receipt receipt = receiptOf("113.00");
        stage.process(receipt,
            new ReceiptProcessingContext(Jurisdiction.CA, null, CurrencyCode.CAD));
        assertThat(receipt.getHstAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receipt.getGstAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
