package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.SourceKind;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three gates an upload passes, and the order they are in. */
class ImportFileValidatorTest {

    private static final String COMMA_DELIMITED = "Date,Description,Amount\n2026-09-01,A,-1.00\n";

    private static final String SEMICOLON_DELIMITED = "Date;Amount\n2026-09-01;1.00\n";

    private static final String PIPE_DELIMITED = "Date|Amount\n2026-09-01|1.00\n";

    private static final String TAB_DELIMITED = "Date\tDescription\tAmount\n";

    private static final String OFX_SGML = "OFXHEADER:100\nDATA:OFXSGML\n\n<OFX><ACCTID>1";

    private static final String OFX_BARE_ROOT = "<OFX><ACCTID>1</ACCTID></OFX>";

    private final ImportFileValidator validator = new ImportFileValidator(ImportLimits.defaults());

    @Test
    @DisplayName("an over-size upload is 413 and is refused from the DECLARED size, unread")
    void refusesAnOverSizeUploadBeforeReadingIt() {
        assertThatThrownBy(() -> validator.requireAcceptableSize(
            ImportLimits.DEFAULT_MAX_FILE_BYTES + 1))
            .isInstanceOf(UploadTooLargeException.class)
            .hasMessageContaining(String.valueOf(ImportLimits.DEFAULT_MAX_FILE_BYTES));
        assertThatCode(() -> validator.requireAcceptableSize(ImportLimits.DEFAULT_MAX_FILE_BYTES))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a part that lies about its size is still bounded on the read itself")
    void boundsTheReadEvenWhenTheDeclaredSizeLies() {
        ImportFileValidator strict = new ImportFileValidator(
            new ImportLimits(16, 100, 100, java.time.Duration.ofSeconds(5), 5, 3,
                ImportLimits.DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES,
                ImportLimits.DEFAULT_PREVIEW_RETENTION));
        byte[] tooMuch = "x".repeat(64).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> strict.readWithinLimit(new ByteArrayInputStream(tooMuch), 8))
            .as("the declared size is the caller's claim about the body, not the body")
            .isInstanceOf(UploadTooLargeException.class);
    }

    @Test
    @DisplayName("an empty part is a 400, not a zero-row import")
    void refusesAnEmptyPart() {
        assertThatThrownBy(() -> validator.requireAcceptableSize(0))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("the content type must be one of the list, and there must be one")
    void checksTheContentType() {
        assertThatCode(() -> validator.requireAcceptableContentType("text/csv; charset=UTF-8"))
            .doesNotThrowAnyException();
        assertThatCode(() -> validator.requireAcceptableContentType("application/octet-stream"))
            .as("what a browser sends for a .ofx it has never heard of")
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.requireAcceptableContentType("image/png"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("does not accept image/png");
        assertThatThrownBy(() -> validator.requireAcceptableContentType(null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("the bytes decide the format, whatever the content type claimed")
    void sniffsTheFormat() {
        assertThat(validator.detectFormat(bytes("Date,Description,Amount\n2026-09-01,A,-1.00\n"),
            null)).isEqualTo(SourceKind.CSV);
        assertThat(validator.detectFormat(bytes("OFXHEADER:100\nDATA:OFXSGML\n\n<OFX><ACCTID>1"),
            null)).isEqualTo(SourceKind.OFX);
        assertThat(validator.detectFormat(
            bytes("<?xml version=\"1.0\"?><OFX><ACCTID>1</ACCTID></OFX>"), null))
            .isEqualTo(SourceKind.OFX);
    }

    @Test
    @DisplayName("a declared kind that the bytes contradict is refused, not honoured")
    void refusesAContradictedDeclaration() {
        assertThatThrownBy(() -> validator.detectFormat(
            bytes("Date,Amount\n2026-09-01,-1.00\n"), SourceKind.OFX))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("uploaded as OFX");
    }

    @Test
    @DisplayName("a zip or gzip wrapper is refused by name")
    void refusesArchives() {
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00}, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {(byte) 0x1F, (byte) 0x8B, 0x08, 0x00}, null))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("compressed archive");
    }

    @Test
    @DisplayName("a binary file mislabelled as octet-stream is refused by its own bytes")
    void refusesBinaryContent() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> validator.detectFormat(png, null))
            .as("this is what makes accepting application/octet-stream safe")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("neither an OFX/QFX document");
    }

    @Test
    @DisplayName("a text file with no delimiter at all is not a CSV")
    void refusesUndelimitedText() {
        assertThatThrownBy(() -> validator.detectFormat(bytes("just some prose\nand more\n"), null))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("every archive signature the list names is refused, and a short file is not one")
    void refusesEveryArchiveSignature() {
        assertThatThrownBy(() -> validator.detectFormat(new byte[] {0x50, 0x4B, 0x05, 0x06}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(new byte[] {0x50, 0x4B, 0x07, 0x08}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(new byte[] {0x42, 0x5A, 0x68, 0x39}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {0x52, 0x61, 0x72, 0x21, 0x1A}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00}, null))
            .isInstanceOf(ValidationException.class).hasMessageContaining("compressed archive");

        assertThatThrownBy(() -> validator.detectFormat(new byte[] {0x50}, null))
            .as("a one-byte file is shorter than every signature and is simply not a format")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("neither an OFX/QFX document");
    }

    @Test
    @DisplayName("control characters mark a file as binary; a tab-delimited export does not")
    void tellsBinaryFromDelimitedText() {
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {0x01, 0x02, 0x03, 0x2C, 0x0A}, null))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> validator.detectFormat(
            new byte[] {0x44, 0x0E, 0x2C, 0x41, 0x0A}, null))
            .isInstanceOf(ValidationException.class);

        assertThat(validator.detectFormat(bytes(TAB_DELIMITED), SourceKind.CSV))
            .as("a tab is a delimiter, not a control character to be afraid of")
            .isEqualTo(SourceKind.CSV);
        assertThat(validator.detectFormat(bytes(PIPE_DELIMITED), null)).isEqualTo(SourceKind.CSV);
        assertThat(validator.detectFormat(bytes(SEMICOLON_DELIMITED), SourceKind.CSV))
            .isEqualTo(SourceKind.CSV);
    }

    @Test
    @DisplayName("a declared CSV that really is CSV is accepted; the OFX sniff has three spellings")
    void acceptsWhatItSays() {
        assertThat(validator.detectFormat(bytes(COMMA_DELIMITED), SourceKind.CSV))
            .isEqualTo(SourceKind.CSV);
        assertThat(validator.detectFormat(bytes(OFX_SGML), SourceKind.OFX))
            .isEqualTo(SourceKind.OFX);
        assertThat(validator.detectFormat(bytes(OFX_BARE_ROOT), null)).isEqualTo(SourceKind.OFX);
    }

    @Test
    @DisplayName("readWithinLimit returns the whole part when it fits")
    void readsAPartThatFits() {
        byte[] content = bytes(COMMA_DELIMITED);

        assertThat(validator.readWithinLimit(new ByteArrayInputStream(content), content.length))
            .isEqualTo(content);
        assertThatThrownBy(() ->
            validator.readWithinLimit(new ByteArrayInputStream(new byte[0]), 1))
            .as("a part that declares a size and then streams nothing is still empty")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("empty");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
