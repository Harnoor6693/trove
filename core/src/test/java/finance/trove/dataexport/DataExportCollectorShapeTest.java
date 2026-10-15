package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.receipt.ReceiptImageKind;
import finance.trove.receipts.receipt.ReceiptSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * What one row looks like in the archive — the three promises the operation description makes to
 * the member, asserted on the shapes rather than on a running export.
 *
 * <ul>
 *   <li>a receipt is metadata: no bytes, no storage key, no URL;</li>
 *   <li>an amount is a decimal string carrying its own currency, never a JSON number and never
 *       blended;</li>
 *   <li>a bank connection never carries the encrypted configuration that would let somebody use
 *       it.</li>
 * </ul>
 */
class DataExportCollectorShapeTest {

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Receipt receiptWithImage(String storageKey, UUID uploaderId) {
        Receipt receipt = new Receipt(HOUSEHOLD_ID, uploaderId, ReceiptSource.UPLOAD, 4096L);
        ReflectionTestUtils.setField(receipt, "id", UUID.randomUUID());
        receipt.setExtractedMerchant("Bakery");
        receipt.setExtractedAmount(new BigDecimal("12.3"));
        receipt.setExtractedCurrency(CurrencyCode.CAD);
        receipt.setTaxSplit(new BigDecimal("0.6"), null, null);
        ReceiptImage image = new ReceiptImage(ReceiptImageKind.ORIGINAL, 1, storageKey,
            "image/jpeg", 4096L, "0".repeat(64));
        ReflectionTestUtils.setField(image, "id", UUID.randomUUID());
        receipt.addImage(image);
        return receipt;
    }

    @Test
    @DisplayName("A receipt exports as metadata: no image bytes, no storage key, no URL")
    void aReceiptCarriesNoBytesAndNoKey() {
        String storageKey = "receipts/" + HOUSEHOLD_ID + "/" + UUID.randomUUID() + ".jpg";
        Map<String, Object> shaped =
            DataExportCollector.receipt(receiptWithImage(storageKey, USER_ID));

        String json = objectMapper.writeValueAsString(shaped);
        assertThat(json)
            .as("the key's shape is <area>/<householdId>/<uuid>, and this archive already carries "
                + "the household id — leaking the key would hand out a resolvable path")
            .doesNotContain(storageKey)
            .doesNotContain("http://")
            .doesNotContain("https://");
        assertThat(shaped).doesNotContainKey("ocrText");
        assertThat(shaped.get("merchant")).isEqualTo("Bakery");
        assertThat(shaped.get("taxSplit")).isNotNull();
    }

    @Test
    @DisplayName("An exported image is named from the receipt's id, not derived from its key")
    void anImageIsNamedNotAddressed() {
        Receipt receipt = receiptWithImage(
            "receipts/" + HOUSEHOLD_ID + "/" + UUID.randomUUID() + ".jpg", USER_ID);
        ReceiptImage image = receipt.getImages().get(0);

        Map<String, Object> shaped = DataExportCollector.image(receipt.getId(), image);

        assertThat(shaped.get("fileName"))
            .as("a label a member can match against a download, which addresses nothing")
            .isEqualTo("receipt-" + receipt.getId() + "-original-p1");
        assertThat(shaped).doesNotContainKey("fileKey");
        assertThat(shaped.get("sizeBytes")).isEqualTo(4096L);
    }

    @Test
    @DisplayName("A receipt whose uploader has been purged exports with a null uploader")
    void aPurgedUploaderIsNullNotMissing() {
        // V43: receipt.user_id is nullable with ON DELETE SET NULL, because the receipt is a
        // shared household ledger row and the ledger outlives its author (owner decision 17).
        Map<String, Object> shaped = DataExportCollector.receipt(receiptWithImage(
            "receipts/" + HOUSEHOLD_ID + "/" + UUID.randomUUID() + ".jpg", null));

        assertThat(shaped).containsKey("uploadedBy");
        assertThat(shaped.get("uploadedBy"))
            .as("the archive says the author is gone rather than pretending the row has no "
                + "history")
            .isNull();
    }

    @Test
    @DisplayName("An amount is a decimal string at the ledger scale, carrying its own currency")
    void anAmountIsAStringWithItsCurrency() {
        Map<String, Object> money =
            DataExportCollector.money(new BigDecimal("12.3"), CurrencyCode.CAD);

        assertThat(money.get("amount"))
            .as("a JSON number is an IEEE double in most readers, and NUMERIC(19,4) does not "
                + "survive that round trip")
            .isInstanceOf(String.class)
            .isEqualTo("12.3000");
        assertThat(money.get("currency")).isEqualTo(CurrencyCode.CAD.name());
    }

    @Test
    @DisplayName("No amount at all is null, so a reader can tell it from zero")
    void aMissingAmountIsNullNotZero() {
        assertThat(DataExportCollector.money(null, CurrencyCode.CAD)).isNull();
        assertThat(DataExportCollector.decimal(null)).isNull();
    }

    @Test
    @DisplayName("A bank connection exports without the encrypted configuration")
    void aBankConnectionCarriesNoCredentials() {
        ConnectedAccount account = new ConnectedAccount(HOUSEHOLD_ID, USER_ID,
            SourceKind.MANUAL, "Chequing", "A Bank", AccountType.CHECKING, CurrencyCode.CAD,
            Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(account, "configEncrypted", "v1:not-a-real-ciphertext");

        Map<String, Object> shaped = DataExportCollector.account(account);

        assertThat(shaped)
            .as("the archive says which institutions the household connected, never how to "
                + "connect to them — this file is emailed to accountants")
            .doesNotContainKey("configEncrypted");
        assertThat(objectMapper.writeValueAsString(shaped))
            .doesNotContain("not-a-real-ciphertext");
    }
}
