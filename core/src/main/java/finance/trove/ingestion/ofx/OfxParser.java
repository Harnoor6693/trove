package finance.trove.ingestion.ofx;

import finance.trove.ingestion.ofx.dto.OfxDocument;
import org.springframework.stereotype.Component;

/**
 * Detects which OFX dialect a file is and routes it to the parser for it.
 *
 * <p>Ported from the pre-collapse plugin with its two size guards intact — the per-file byte
 * ceiling and the per-file transaction cap — but taking both from {@code ImportLimits} instead of
 * from constants of its own: the multipart cap, the import's file cap and this one were three
 * numbers in three places, and the one that fired first decided the error message.
 *
 * <p>The byte ceiling is enforced by the caller before it ever reads the body, so what remains
 * here is the routing and the transaction cap.
 */
@Component
public class OfxParser {

    private final OfxFormatDetector detector = new OfxFormatDetector();
    private final OfxSgmlParser sgmlParser = new OfxSgmlParser();
    private final OfxXmlParser xmlParser = new OfxXmlParser();

    /**
     * @param content         the whole file, already size-checked by the caller
     * @param maxTransactions the per-file transaction cap
     */
    public OfxDocument parse(byte[] content, int maxTransactions) {
        return switch (detector.detect(content)) {
            case OFX_1_SGML -> sgmlParser.parse(content, maxTransactions);
            case OFX_2_XML -> xmlParser.parse(content, maxTransactions);
        };
    }
}
