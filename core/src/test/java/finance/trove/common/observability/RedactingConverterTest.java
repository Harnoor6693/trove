package finance.trove.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactingConverterTest {

    private final RedactingConverter converter = new RedactingConverter();

    @Test
    void masks_bearerHeader() {
        String out = convert("got Authorization: Bearer eyJabc.eyJdef.signaturepart");
        assertTrue(out.contains("Bearer ***"), out);
        assertTrue(!out.contains("eyJabc"), out);
    }

    @Test
    void masks_standaloneJwt() {
        String out = convert("token=eyJabc.eyJdef.signaturepart was sent");
        assertTrue(out.contains("***JWT***"), out);
        assertTrue(!out.contains("eyJabc"), out);
    }

    @Test
    void masks_sixteenDigitCard() {
        String out = convert("charged 4111111111111111 today");
        assertTrue(out.contains("****-****-****-1111"), out);
        assertTrue(!out.contains("4111111111111111"), out);
    }

    @Test
    void leavesBenignMessageUnchanged() {
        String out = convert("user u-bd91 viewed dashboard");
        assertEquals("user u-bd91 viewed dashboard", out);
    }

    @Test
    void masks_dashedSixteenDigitCard() {
        String out = convert("charged 4111-1111-1111-1111 today");
        assertTrue(out.contains("****-****-****-1111"), out);
        assertTrue(!out.contains("4111-1111-1111-1111"), out);
    }

    @Test
    void masks_spacedSixteenDigitCard() {
        String out = convert("charged 4111 1111 1111 1111 today");
        assertTrue(out.contains("****-****-****-1111"), out);
        assertTrue(!out.contains("4111 1111 1111 1111"), out);
    }

    @Test
    void masks_thirteenDigitCard() {
        String out = convert("legacy card 4111111111111 used");
        assertTrue(out.contains("****-****-****-1111"), out);
        assertTrue(!out.contains("4111111111111 "), out);
    }

    @Test
    void masks_nineteenDigitCard() {
        String out = convert("max card 4111111111111111111 used");
        assertTrue(out.contains("****-****-****-1111"), out);
        assertTrue(!out.contains("4111111111111111111"), out);
    }

    @Test
    void leavesTwelveDigitNumberUnchanged() {
        String out = convert("ref 123456789012 ok");
        assertTrue(out.contains("123456789012"), out);
    }

    @Test
    void leavesTwentyDigitNumberUnchanged() {
        String out = convert("snowflake 12345678901234567890 ok");
        assertTrue(out.contains("12345678901234567890"), out);
    }

    private String convert(String message) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = ctx.getLogger("test");
        LoggingEvent event = new LoggingEvent(
            "test", logger, ch.qos.logback.classic.Level.INFO, message, null, new Object[0]);
        return converter.convert(event);
    }
}
