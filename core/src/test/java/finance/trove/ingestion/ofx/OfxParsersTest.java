package finance.trove.ingestion.ofx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.ofx.dto.OfxDocument;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Both OFX parsers, against real files checked in under {@code src/test/resources/ofx}.
 *
 * <p>The pre-collapse test suite carried its samples as string literals, which is why no OFX file
 * existed anywhere in the repository to test the byte-level behaviour that matters most: the
 * SGML parser's handling of a declared {@code CHARSET}. A literal is always UTF-16 in the JVM and
 * whatever the compiler's encoding says on disk, so a windows-1252 statement simply could not be
 * expressed. These are files.
 */
class OfxParsersTest {

    private static final int MAX_TRANSACTIONS = 10_000;

    private final OfxSgmlParser sgml = new OfxSgmlParser();
    private final OfxXmlParser xml = new OfxXmlParser();

    @Nested
    @DisplayName("OFX 1.x (SGML)")
    class Sgml {

        @Test
        @DisplayName("every transaction is parsed, and FITID is the identifier")
        void parsesEveryTransaction() {
            OfxDocument document = sgml.parse(fixture("bank-sgml-usascii.ofx"), MAX_TRANSACTIONS);

            assertThat(document.transactions()).extracting(OfxTransaction::fitId)
                .containsExactly("TXN001", "TXN002", "TXN003");
            assertThat(document.account().accountId()).isEqualTo("123456789");
            assertThat(document.account().bankId()).isEqualTo("021000021");
            assertThat(document.account().accountType()).isEqualTo("CHECKING");
            assertThat(document.transactions().get(0).amount())
                .isEqualByComparingTo(new BigDecimal("-42.50"));
            assertThat(document.transactions().get(0).datePosted())
                .as("a DTPOSTED with a time and a zone suffix keeps only its date")
                .isEqualTo(LocalDate.of(2024, 1, 15));
        }

        @Test
        @DisplayName("CURDEF is read; the file's own currency is never overwritten with a literal")
        void readsCurdef() {
            assertThat(sgml.parse(fixture("bank-sgml-usascii.ofx"), MAX_TRANSACTIONS).account()
                .currency()).isEqualTo("USD");
            assertThat(sgml.parse(fixture("bank-sgml-windows1252.ofx"), MAX_TRANSACTIONS).account()
                .currency())
                .as("the pre-collapse normaliser stamped every row USD, so this plugin could not "
                    + "produce a correct Canadian-dollar transaction at all (plugins.md §4.2)")
                .isEqualTo("CAD");
        }

        @Test
        @DisplayName("the header's declared CHARSET is honoured, not assumed to be UTF-8")
        void honoursTheDeclaredCharset() {
            byte[] content = fixture("bank-sgml-windows1252.ofx");

            OfxDocument document = sgml.parse(content, MAX_TRANSACTIONS);

            assertThat(document.transactions().get(0).name())
                .as("CHARSET:1252 with an accented merchant name; decoding it as UTF-8 turns "
                    + "these bytes into replacement characters and stores them that way for ever")
                .isEqualTo("CAFÉ DÉPANNEUR");
            assertThat(document.transactions().get(0).memo()).isEqualTo("Café au lait");
            assertThat(new String(content, StandardCharsets.UTF_8))
                .as("the fix has to be real: these bytes are NOT valid UTF-8")
                .contains("�");
        }

        @Test
        @DisplayName("a missing ACCTID is refused rather than defaulted to a shared literal")
        void refusesAMissingAcctId() {
            assertThatThrownBy(() -> sgml.parse(fixture("bank-sgml-no-acctid.ofx"),
                MAX_TRANSACTIONS))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageContaining("ACCTID");
        }

        @Test
        @DisplayName("the per-file transaction cap is enforced")
        void enforcesTheTransactionCap() {
            assertThatThrownBy(() -> sgml.parse(fixture("bank-sgml-usascii.ofx"), 2))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageContaining("more than 2 transactions");
        }

        @Test
        @DisplayName("ENCODING:UTF-8 wins over CHARSET; an unknown CHARSET falls back to 1252")
        void charsetSelection() {
            assertThat(OfxSgmlParser.charsetOf("OFXHEADER:100\nENCODING:UTF-8\nCHARSET:1252\n"
                .getBytes(StandardCharsets.US_ASCII))).isEqualTo(StandardCharsets.UTF_8);
            assertThat(OfxSgmlParser.charsetOf("OFXHEADER:100\nENCODING:USASCII\nCHARSET:8859-1\n"
                .getBytes(StandardCharsets.US_ASCII))).isEqualTo(StandardCharsets.ISO_8859_1);
            assertThat(OfxSgmlParser.charsetOf("OFXHEADER:100\nCHARSET:NOT-A-CHARSET\n"
                .getBytes(StandardCharsets.US_ASCII)).name()).isEqualTo("windows-1252");
        }
    }

    @Nested
    @DisplayName("OFX 2.x (XML)")
    class Xml {

        @Test
        @DisplayName("fields are parsed, and a per-transaction CURSYM overrides the document's")
        void parsesFieldsAndCurrency() {
            OfxDocument document = xml.parse(fixture("bank-xml.ofx"), MAX_TRANSACTIONS);

            assertThat(document.account().accountId()).isEqualTo("987654321");
            assertThat(document.account().currency()).isEqualTo("CAD");
            assertThat(document.transactions()).extracting(OfxTransaction::fitId)
                .containsExactly("TXN-XML-001", "TXN-XML-002");
            assertThat(document.transactions().get(0).dateUser())
                .isEqualTo(LocalDate.of(2024, 1, 31));
            assertThat(document.transactions().get(0).currency())
                .as("no CURRENCY block on this one, so it takes the document's CURDEF later")
                .isNull();
            assertThat(document.transactions().get(1).currency()).isEqualTo("USD");
        }

        @Test
        @DisplayName("a missing ACCTID is refused — the two parsers now agree")
        void refusesAMissingAcctId() {
            assertThatThrownBy(() -> xml.parse(fixture("bank-xml-no-acctid.ofx"), MAX_TRANSACTIONS))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageContaining("ACCTID");
        }

        @Test
        @DisplayName("a DOCTYPE with an external entity is refused, and the message quotes no markup")
        void refusesXxe() {
            assertThatThrownBy(() -> xml.parse(fixture("bank-xml-xxe.ofx"), MAX_TRANSACTIONS))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageContaining("could not be parsed as OFX XML")
                .hasMessageNotContaining("passwd");
        }

        @Test
        @DisplayName("the per-file transaction cap is enforced")
        void enforcesTheTransactionCap() {
            assertThatThrownBy(() -> xml.parse(fixture("bank-xml.ofx"), 1))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageContaining("more than 1 transactions");
        }
    }

    @Test
    @DisplayName("the first 512 bytes decide which parser reads the file")
    void detectsTheFormat() {
        OfxFormatDetector detector = new OfxFormatDetector();

        assertThat(detector.detect(fixture("bank-sgml-usascii.ofx")))
            .isEqualTo(OfxFormatDetector.Format.OFX_1_SGML);
        assertThat(detector.detect(fixture("bank-sgml-windows1252.ofx")))
            .isEqualTo(OfxFormatDetector.Format.OFX_1_SGML);
        assertThat(detector.detect(fixture("bank-xml.ofx")))
            .isEqualTo(OfxFormatDetector.Format.OFX_2_XML);
        assertThatThrownBy(() -> detector.detect("Date,Amount\n2026-09-01,1.00\n"
            .getBytes(StandardCharsets.US_ASCII)))
            .isInstanceOf(BankSourceConnectionException.class);
    }

    /** Reads one checked-in fixture as raw bytes — never as a String. */
    static byte[] fixture(String name) {
        try (InputStream in = OfxParsersTest.class.getResourceAsStream("/ofx/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture /ofx/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
