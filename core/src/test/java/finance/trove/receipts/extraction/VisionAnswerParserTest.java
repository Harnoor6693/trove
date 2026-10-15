package finance.trove.receipts.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model's answer is a suggestion, never a value
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 44).
 *
 * <p>Every case here is an answer some endpoint could really send, and every one of them must
 * become "nothing usable" rather than something written into a {@code NUMERIC} column on a
 * background thread. Nothing here ever throws: the fallback is a receipt a member reviews, which
 * is the ordinary outcome on an install with no AI at all.
 */
class VisionAnswerParserTest {

    /** A real control character, written as an escape so the source file stays printable. */
    private static final String BELL = "\u0007";

    private final VisionAnswerParser parser = new VisionAnswerParser();

    @Test
    @DisplayName("a well-formed answer is read field by field")
    void aGoodAnswerIsRead() {
        VisionAnswer answer = parser.parse("""
            {"merchant":"Blue Bottle Coffee","amount":12.34,"currency":"CAD",
             "date":"05/06/2026","text":"BLUE BOTTLE","confidence":0.9,
             "lineItems":[{"description":"Latte","quantity":1,"unitPrice":5.75,
                           "totalPrice":5.75,"taxable":true}]}""");

        assertThat(answer.merchant()).isEqualTo("Blue Bottle Coffee");
        assertThat(answer.amount()).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(answer.currencyCode()).isEqualTo("CAD");
        // The date is carried UNPARSED: parsing it needs the member's country, which this type
        // does not have and must not guess at (inventory D2).
        assertThat(answer.rawDate()).isEqualTo("05/06/2026");
        assertThat(answer.confidence()).isEqualByComparingTo(new BigDecimal("0.900"));
        assertThat(answer.lineItems()).hasSize(1);
        assertThat(answer.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("prose becomes nothing usable")
    void proseIsAMiss() {
        assertThat(parser.parse("I'm sorry, I can't read that receipt.").isEmpty()).isTrue();
        // Deliberately not "find the JSON inside the prose": the part of an answer that looks like
        // JSON is an attacker-chooseable substring.
        assertThat(parser.parse("Here is the JSON you asked for: {\"amount\": 1}").isEmpty())
            .isTrue();
    }

    @Test
    @DisplayName("malformed JSON becomes nothing usable")
    void malformedJsonIsAMiss() {
        assertThat(parser.parse("{\"merchant\": \"Shop\",").isEmpty()).isTrue();
        assertThat(parser.parse("```json {\"amount\":1} ```").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("JSON that is not an object becomes nothing usable")
    void nonObjectJsonIsAMiss() {
        assertThat(parser.parse("[1, 2, 3]").isEmpty()).isTrue();
        assertThat(parser.parse("\"just a string\"").isEmpty()).isTrue();
        assertThat(parser.parse("42").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a missing field is simply absent; the rest of the answer survives")
    void missingFieldsAreAbsent() {
        VisionAnswer answer = parser.parse("{\"merchant\":\"Shop\"}");
        assertThat(answer.merchant()).isEqualTo("Shop");
        assertThat(answer.amount()).isNull();
        assertThat(answer.rawDate()).isNull();
        assertThat(answer.lineItems()).isEmpty();
    }

    @Test
    @DisplayName("a ten-kilobyte answer is refused BEFORE it is parsed")
    void anOversizeAnswerIsRefused() {
        String huge = "{\"text\":\"" + "a".repeat(10_000) + "\"}";
        assertThat(huge.length()).isGreaterThan(VisionAnswerParser.MAX_ANSWER_CHARACTERS);
        assertThat(parser.parse(huge).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a control character makes the WHOLE answer a miss, not a stripped field")
    void aControlCharacterIsAMiss() {
        // Refusing outright is strictly stronger than acting on the stripped remains: the only use
        // for this text is structured extraction.
        assertThat(parser.parse("{\"merchant\":\"Shop" + BELL + "\",\"amount\":1.00}").isEmpty())
            .isTrue();
        // Tab, newline and carriage return are legal JSON whitespace and a transcription is made
        // of them, so they are excluded from the rule.
        assertThat(parser.parse("{\"merchant\":\"Shop\",\n \"amount\":1.00}").isEmpty()).isFalse();
    }

    @Test
    @DisplayName("an out-of-range or non-numeric amount is dropped, not coerced")
    void badAmountsAreDropped() {
        assertThat(parser.parse("{\"merchant\":\"S\",\"amount\":-5}").amount()).isNull();
        assertThat(parser.parse("{\"merchant\":\"S\",\"amount\":1000000001}").amount()).isNull();
        assertThat(parser.parse("{\"merchant\":\"S\",\"amount\":\"lots\"}").amount()).isNull();
        assertThat(parser.parse("{\"merchant\":\"S\",\"amount\":true}").amount()).isNull();
        assertThat(parser.parse("{\"merchant\":\"S\",\"amount\":{\"n\":1}}").amount()).isNull();
        // A numeric STRING is accepted, because OpenAI-compatible endpoints differ on which they
        // emit for the same prompt.
        assertThat(parser.parse("{\"amount\":\"12.34\"}").amount())
            .isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    @DisplayName("a currency that is not a three-letter code is dropped")
    void badCurrencyCodesAreDropped() {
        assertThat(parser.parse("{\"currency\":\"dollars\"}").currencyCode()).isNull();
        assertThat(parser.parse("{\"currency\":\"$\"}").currencyCode()).isNull();
        assertThat(parser.parse("{\"currency\":\"usd\"}").currencyCode()).isEqualTo("USD");
    }

    @Test
    @DisplayName("a line without a description or a total is dropped")
    void badLineItemsAreDropped() {
        VisionAnswer answer = parser.parse("""
            {"lineItems":[{"description":"Latte","totalPrice":5.75},
                          {"totalPrice":1.00},
                          {"description":"No price"},
                          "not an object",
                          {"description":"Bun","totalPrice":-1}]}""");
        assertThat(answer.lineItems()).hasSize(1);
        assertThat(answer.lineItems().get(0).description()).isEqualTo("Latte");
        assertThat(answer.lineItems().get(0).quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    @DisplayName("null, blank and empty answers are all nothing usable")
    void emptyAnswers() {
        assertThat(parser.parse(null).isEmpty()).isTrue();
        assertThat(parser.parse("   ").isEmpty()).isTrue();
        assertThat(parser.parse("{}").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the merchant is truncated to the column's width rather than overflowing it")
    void theMerchantIsBounded() {
        assertThat(parser.parse("{\"merchant\":\"" + "M".repeat(500) + "\"}").merchant())
            .hasSize(200);
    }
}
