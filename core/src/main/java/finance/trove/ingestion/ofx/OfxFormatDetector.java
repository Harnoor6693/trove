package finance.trove.ingestion.ofx;

import finance.trove.common.plugin.BankSourceConnectionException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Decides whether a file is OFX 1.x (SGML) or OFX 2.x (XML) from its first 512 bytes.
 *
 * <p>Ported unchanged in substance from the pre-collapse plugin, which is the best-tested code in
 * the plugin tree. OFX 1.x opens with an {@code OFXHEADER:} block and a flat tag structure with no
 * closing tags; OFX 2.x is well-formed XML. The two need different parsers, and deciding from a
 * fixed prefix means the file is never parsed twice to find out which.
 *
 * <p>The prefix is read as US-ASCII deliberately, even for a file whose body is
 * windows-1252: an OFX header is ASCII by specification, and decoding the prefix with the
 * charset the header itself declares would be circular.
 */
public class OfxFormatDetector {

    /** How many leading bytes the decision is made from. */
    public static final int HEADER_BYTES = 512;

    /** The two wire formats of OFX. */
    public enum Format {

        /** OFX 1.x: an {@code OFXHEADER:} block followed by SGML with no closing tags. */
        OFX_1_SGML,

        /** OFX 2.x: a well-formed XML document. */
        OFX_2_XML
    }

    /**
     * @param firstBytes the file's leading bytes; at most {@link #HEADER_BYTES} are consulted
     * @throws BankSourceConnectionException if the bytes are neither format
     */
    public Format detect(byte[] firstBytes) {
        int length = Math.min(firstBytes.length, HEADER_BYTES);
        String header = new String(firstBytes, 0, length, StandardCharsets.US_ASCII)
            .toLowerCase(Locale.ROOT);
        if (header.contains("<?xml")
            || (header.contains("<ofx>") && !header.contains("ofxheader:"))) {
            return Format.OFX_2_XML;
        }
        if (header.contains("ofxheader:") || header.contains("data:ofxsgml")) {
            return Format.OFX_1_SGML;
        }
        throw new BankSourceConnectionException(
            "This file is not an OFX or QFX document: its header names neither format.");
    }
}
