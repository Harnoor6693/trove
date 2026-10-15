package finance.trove.ingestion.ofx;

import static finance.trove.ingestion.ofx.OfxParsersTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.accounts.AccountType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.ImportContext;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.importbatch.ImportLimits;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an OFX file becomes, and the three findings that made the pre-collapse version unusable.
 */
class OfxSourceTest {

    private final OfxSource source = new OfxSource(new OfxParser(), new OfxNormalizer(), null,
        ImportLimits.defaults());

    @Test
    @DisplayName("O7/M0: the identifier is FITID and never the literal \"ofx\"")
    void theIdentifierIsTheFitId() {
        ParsedUpload parsed = read("bank-sgml-usascii.ofx");

        assertThat(parsed.rows()).extracting(row -> row.row().providerTransactionId())
            .as("the source wrote a constant SOURCE_ID = \"ofx\" into this slot, so with the "
                + "unique index on (account_id, provider_transaction_id) an OFX account could "
                + "hold exactly one transaction, for ever")
            .containsExactly("TXN001", "TXN002", "TXN003")
            .doesNotContain("ofx");
    }

    @Test
    @DisplayName("ACCTID is carried out of the file, not discarded")
    void acctIdReachesTheCaller() {
        assertThat(read("bank-sgml-usascii.ofx").externalAccountId()).isEqualTo("123456789");
        assertThat(read("bank-xml.ofx").externalAccountId()).isEqualTo("987654321");
    }

    @Test
    @DisplayName("F4: currency comes from the file, and the account's is the only fallback")
    void currencyComesFromTheFile() {
        ParsedUpload canadian = read("bank-sgml-windows1252.ofx");
        assertThat(canadian.currency()).isEqualTo(CurrencyCode.CAD);
        assertThat(canadian.rows().get(0).row().currency()).isEqualTo("CAD");

        ParsedUpload xml = read("bank-xml.ofx");
        assertThat(xml.rows().get(0).row().currency())
            .as("no CURSYM on this row, so the document's CURDEF")
            .isEqualTo("CAD");
        assertThat(xml.rows().get(1).row().currency())
            .as("its own CURSYM wins over the document's CURDEF")
            .isEqualTo("USD");
    }

    @Test
    @DisplayName("the account's type and institution come from the file too")
    void accountMetadataComesFromTheFile() {
        ParsedUpload parsed = read("bank-sgml-windows1252.ofx");

        assertThat(parsed.accountType()).isEqualTo(AccountType.SAVINGS);
        assertThat(parsed.institution()).isEqualTo("000300002");
        assertThat(read("bank-xml.ofx").accountType()).isEqualTo(AccountType.SAVINGS);
    }

    @Test
    @DisplayName("amounts are absolute and TRNTYPE refines the direction")
    void directionAndAmount() {
        ParsedUpload parsed = read("bank-sgml-usascii.ofx");

        RawTransaction debit = parsed.rows().get(0).row();
        assertThat(debit.amount()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(debit.type()).isEqualTo(TransactionType.DEBIT);
        assertThat(parsed.rows().get(1).row().type()).isEqualTo(TransactionType.CREDIT);

        assertThat(read("bank-sgml-windows1252.ofx").rows().get(1).row().type())
            .as("TRNTYPE SRVCHG is a bank charge, which the ledger models as FEE rather than as "
                + "an undifferentiated debit")
            .isEqualTo(TransactionType.FEE);
    }

    @Test
    @DisplayName("every row parses cleanly, so none carries a reason")
    void everyRowIsClean() {
        assertThat(read("bank-xml.ofx").rows()).noneMatch(ParsedRow::hasError);
    }

    @Test
    @DisplayName("the source serves OFX accounts")
    void servesOfx() {
        assertThat(source.sourceKind()).isEqualTo(SourceKind.OFX);
    }

    private ParsedUpload read(String fixture) {
        return source.read(fixture(fixture), null, new ImportContext(null));
    }
}
