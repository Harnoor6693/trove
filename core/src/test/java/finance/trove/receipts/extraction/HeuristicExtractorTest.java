package finance.trove.receipts.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two per-member facts the source hard-coded (inventory receipt-processor D2 and D3).
 *
 * <p>Both regressions are asserted with the SAME input bytes read for two different members, which
 * is the only shape that proves the fix: a single-member test would pass just as well against the
 * constant that was there before.
 */
class HeuristicExtractorTest {

    private final HeuristicExtractor extractor = new HeuristicExtractor();

    // ------------------------------------------------------------------ D2: date order

    @Test
    @DisplayName("D2: 05/06/2026 is 5 June for a Canadian member and 6 May for an American one")
    void slashDateOrderFollowsTheMembersCountry() {
        String receipt = """
            BLUE BOTTLE COFFEE
            05/06/2026
            TOTAL  12.34
            """;

        // One receipt, two members. The source tried MM/dd/yyyy and nothing else
        // (HeuristicExtractor.java:107-113), so the Canadian answer below was five months wrong,
        // silently — and the date is the field the whole matching stage searches on.
        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).date())
            .isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(extractor.extract(receipt, Jurisdiction.US, CurrencyCode.USD).date())
            .isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    @DisplayName("D2: an unambiguous slash date reads the same for both members")
    void unambiguousSlashDatesAgree() {
        String receipt = "STORE\n25/12/2026\nTOTAL  10.00\n";

        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).date())
            .isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(extractor.extract(receipt, Jurisdiction.US, CurrencyCode.USD).date())
            .isEqualTo(LocalDate.of(2026, 12, 25));
    }

    @Test
    @DisplayName("D2: an ISO date wins over the member's order, because it is unambiguous")
    void isoDatesWin() {
        String receipt = "STORE\n2026-06-05\nTOTAL  10.00\n";
        assertThat(extractor.extract(receipt, Jurisdiction.US, CurrencyCode.USD).date())
            .isEqualTo(LocalDate.of(2026, 6, 5));
    }

    @Test
    @DisplayName("D2: with no country there is no slash date, rather than an assumed one")
    void noCountryMeansNoSlashDate() {
        assertThat(extractor.extract("STORE\n05/06/2026\nTOTAL  10.00\n", null, null).date())
            .isNull();
    }

    // ------------------------------------------------------------------ D3: currency

    @Test
    @DisplayName("D3: a receipt containing only '$' takes the uploader's currency, not a literal")
    void bareDollarSignTakesTheMembersCurrency() {
        String receipt = """
            CORNER STORE
            2026-06-05
            TOTAL  $12.34
            """;

        // The source returned "CAD" for any text containing a '$'
        // (HeuristicExtractor.java:154), so every American member's coffee was Canadian.
        assertThat(extractor.extract(receipt, Jurisdiction.US, CurrencyCode.USD).currency())
            .isEqualTo(CurrencyCode.USD);
        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).currency())
            .isEqualTo(CurrencyCode.CAD);
    }

    @Test
    @DisplayName("D3: an explicit ISO code on the receipt wins over the member's currency")
    void explicitIsoCodeWins() {
        String receipt = "DUTY FREE\n2026-06-05\nTOTAL  12.34 USD\n";
        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).currency())
            .isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("D3: with no member currency and no ISO code, the currency stays null")
    void noCurrencyIsBetterThanAGuess() {
        assertThat(extractor.extract("STORE\nTOTAL  $12.34\n", null, null).currency()).isNull();
    }

    // ------------------------------------------------------------------ the kept behaviour

    @Test
    @DisplayName("the largest keyword-labelled figure is the total, not the first subtotal")
    void theLargestLabelledFigureWins() {
        String receipt = """
            SHOP
            2026-06-05
            SUBTOTAL  10.00
            TOTAL  11.30
            """;
        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).amount())
            .isEqualByComparingTo(new BigDecimal("11.30"));
    }

    @Test
    @DisplayName("the merchant is the first line that is neither a date nor a price")
    void theMerchantIsTheFirstOrdinaryLine() {
        String receipt = """
            2026-06-05
            BLUE BOTTLE COFFEE
            TOTAL  11.30
            """;
        assertThat(extractor.extract(receipt, Jurisdiction.CA, CurrencyCode.CAD).merchant())
            .isEqualTo("BLUE BOTTLE COFFEE");
    }

    @Test
    @DisplayName("one price line is a total, not an item list: fewer than two candidates is none")
    void oneLineItemIsNotAnItemList() {
        String single = "SHOP\n2026-06-05\nOne thing    4.00\n";
        assertThat(extractor.extract(single, Jurisdiction.CA, CurrencyCode.CAD).lineItems())
            .isEmpty();

        String two = "SHOP\n2026-06-05\nOne thing    4.00\nAnother      6.00\n";
        assertThat(extractor.extract(two, Jurisdiction.CA, CurrencyCode.CAD).lineItems())
            .hasSize(2);
    }

    @Test
    @DisplayName("a clean receipt scores 1.000 and an empty one scores zero")
    void confidenceReflectsWhatWasFound() {
        String clean = """
            BLUE BOTTLE COFFEE
            2026-06-05
            Latte        5.75
            Croissant    4.25
            TOTAL  11.30
            """;
        assertThat(extractor.extract(clean, Jurisdiction.CA, CurrencyCode.CAD).confidence())
            .isEqualByComparingTo(BigDecimal.ONE);

        assertThat(extractor.extract("   ", Jurisdiction.CA, CurrencyCode.CAD).confidence())
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(extractor.extract(null, Jurisdiction.CA, CurrencyCode.CAD))
            .isEqualTo(HeuristicExtractionResult.empty());
    }

    // ---------------------------------------------------------- untrusted text, bounded numbers

    @Test
    @DisplayName("a total too wide for NUMERIC(19,4) is not a total, and is not returned")
    void anUnstorableTotalIsRefusedRatherThanParsed() {
        // This text is a vision model's transcription read back out of receipt.ocr_text: it is
        // untrusted, and what this method returns is written to receipt.extracted_amount,
        // NUMERIC(19,4). Sixteen integer digits parse perfectly well as a BigDecimal and are then
        // refused by Postgres — and a refusal at that depth is not a bad field, it is a receipt
        // that cannot reach a terminal status and is re-enqueued by the sweep every hour.
        String poison = "SHOP\n2026-06-05\nTOTAL  12345678901234567.89\n";

        assertThat(extractor.extract(poison, Jurisdiction.CA, CurrencyCode.CAD).amount())
            .as("VisionAnswerParser refuses this in the model's structured fields; the same "
                + "number arriving in the transcription must be refused too")
            .isNull();
    }

    @Test
    @DisplayName("an ordinary total beside an unstorable one still wins")
    void thePlausibleTotalSurvivesBesideAnImpossibleOne() {
        String mixed = "SHOP\n2026-06-05\nSUBTOTAL  40.00\nTOTAL  99999999999999999999.00\n";

        assertThat(extractor.extract(mixed, Jurisdiction.CA, CurrencyCode.CAD).amount())
            .isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("a line price too wide for its column is dropped, and the list is capped at 200")
    void lineItemsAreBoundedInBothDirections() {
        String withPoison = """
            SHOP
            2026-06-05
            Fine thing        4.00
            Impossible thing  99999999999999999999.00
            Other fine thing  6.00
            """;
        assertThat(extractor.extract(withPoison, Jurisdiction.CA, CurrencyCode.CAD).lineItems())
            .extracting(LineItemCandidate::description)
            .containsExactly("Fine thing", "Other fine thing");

        StringBuilder many = new StringBuilder("SHOP\n2026-06-05\n");
        for (int i = 0; i < ExtractionLimits.MAX_LINE_ITEMS + 50; i++) {
            many.append("Item ").append(i).append("   1.00\n");
        }
        assertThat(extractor.extract(many.toString(), Jurisdiction.CA, CurrencyCode.CAD)
            .lineItems())
            .as("a transcription of nothing but prices would otherwise become thousands of rows")
            .hasSize(ExtractionLimits.MAX_LINE_ITEMS);
    }
}
