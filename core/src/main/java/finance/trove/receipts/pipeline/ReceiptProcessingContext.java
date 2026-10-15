package finance.trove.receipts.pipeline;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import java.math.BigDecimal;

/**
 * What one pipeline run carries between its stages and does not persist
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p>Three changes from the source's version, each closing a defect:
 * <ul>
 *   <li>{@code ocrConfidence} is gone with the OCR stage. The vision model returns text and a
 *       confidence for the extraction as a whole; there is no separate transcription step left to
 *       score, and a weight over a value nothing sets was half of why an unmatched receipt could
 *       never clear the review threshold (inventory D1).</li>
 *   <li>{@code userProvince} came from an {@code X-Trove-Province} <em>request header</em> — a
 *       client-supplied input that decided a tax calculation. It is replaced by
 *       {@link #region()}, read from the uploader's own {@code users.region} row, alongside
 *       {@link #country()} and {@link #currency()} which the parsing and tax stages need for the
 *       same reason (§2.6 says {@code user_preferences.region}; that table was never written —
 *       V3 put region, locale, timezone and onboarding on {@code users}).</li>
 *   <li>{@code matchAttempted} is new and is the other half of the D1 fix: the scoring stage has
 *       to tell "no candidate existed" from "a candidate existed and did not match", because only
 *       the first justifies renormalising the weights.</li>
 * </ul>
 *
 * <p>Confidences are {@link BigDecimal} rather than {@code double}: {@code ArchitectureTest} bans
 * binary floating point on domain API types (§9 risk 6), and the column they end up in is
 * {@code NUMERIC(4,3)}.
 */
public class ReceiptProcessingContext {

    private final Jurisdiction country;
    private final String region;
    private final CurrencyCode currency;

    private BigDecimal extractionConfidence = BigDecimal.ZERO;
    private BigDecimal matchConfidence = BigDecimal.ZERO;
    private boolean matchAttempted;
    private String detectedMimeType;

    /**
     * @param country  the uploader's {@code users.country}; the tax split runs only for CA and it
     *                 also decides the order slash-dates are tried in (inventory D2)
     * @param region   the uploader's {@code users.region} — an ISO 3166-2 CA province code, or null
     * @param currency the uploader's {@code users.currency}, the fallback when the receipt names
     *                 no ISO code (inventory D3)
     */
    public ReceiptProcessingContext(Jurisdiction country, String region, CurrencyCode currency) {
        this.country = country;
        this.region = region;
        this.currency = currency;
    }

    public Jurisdiction country() {
        return country;
    }

    public String region() {
        return region;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public BigDecimal getExtractionConfidence() {
        return extractionConfidence;
    }

    public void setExtractionConfidence(BigDecimal extractionConfidence) {
        this.extractionConfidence = extractionConfidence;
    }

    public BigDecimal getMatchConfidence() {
        return matchConfidence;
    }

    public void setMatchConfidence(BigDecimal matchConfidence) {
        this.matchConfidence = matchConfidence;
    }

    /**
     * Whether the matching stage had anything to look for.
     *
     * <p>False means there was no amount or no date to search on, or the household's ledger held
     * no candidate at all in the window — in which case the match weight is meaningless and the
     * scoring stage renormalises without it (inventory D1). True means a candidate set existed and
     * the confidence in {@link #getMatchConfidence()} is a real measurement.
     */
    public boolean isMatchAttempted() {
        return matchAttempted;
    }

    public void setMatchAttempted(boolean matchAttempted) {
        this.matchAttempted = matchAttempted;
    }

    public String getDetectedMimeType() {
        return detectedMimeType;
    }

    public void setDetectedMimeType(String detectedMimeType) {
        this.detectedMimeType = detectedMimeType;
    }
}
