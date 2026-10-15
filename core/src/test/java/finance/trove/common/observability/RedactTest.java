package finance.trove.common.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RedactTest {

    @Test
    void token_masksAllButFirstFour() {
        assertEquals("abcd***", Redact.token("abcdefghij"));
    }

    @Test
    void token_nullSafe() {
        assertEquals("<null>", Redact.token(null));
    }

    @Test
    void token_emptySafe() {
        assertEquals("<empty>", Redact.token(""));
    }

    @Test
    void token_shorterThanFour_returnsAllStars() {
        assertEquals("***", Redact.token("abc"));
    }

    @Test
    void email_masksLocalPartExceptFirstChar() {
        assertEquals("a***@example.com", Redact.email("alice@example.com"));
    }

    @Test
    void email_invalidEmail_returnsStars() {
        assertEquals("***", Redact.email("notanemail"));
    }

    @Test
    void email_nullSafe() {
        assertEquals("<null>", Redact.email(null));
    }

    @Test
    void phone_masksAllButLastFour() {
        assertEquals("***1234", Redact.phone("+1-555-555-1234"));
    }

    @Test
    void phone_shortNumber_returnsStars() {
        assertEquals("***", Redact.phone("123"));
    }

    @Test
    void cardNumber_masksFirstTwelve() {
        assertEquals("****-****-****-1111", Redact.cardNumber("4111111111111111"));
    }

    @Test
    void cardNumber_invalidLength_returnsStars() {
        assertEquals("***", Redact.cardNumber("1234"));
    }
}
