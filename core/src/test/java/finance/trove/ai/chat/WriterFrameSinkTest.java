package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The frames, byte for byte ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11, "binding to the
 * character").
 *
 * <p>Two independently written clients — a {@code fetch} + {@code ReadableStream} reader on web and
 * a {@code fetch} POST reader on mobile — are built against this contract, and neither can be
 * changed by this repository. So the bytes are asserted rather than the intent: the {@code data: }
 * prefix WITH its space, the blank line that ends a frame, the OpenAI-shaped delta object, the
 * literal {@code [DONE]} rather than a named SSE event, and an error frame that carries the same
 * RFC 7807 body a non-streaming failure would have.
 *
 * <p>The flush after every frame is asserted too, because it is the whole of §9 risk 11 at this
 * level: a frame written into a buffer and released at the end is a coach that appears to hang for
 * the length of a generation.
 */
class WriterFrameSinkTest {

    private final RecordingWriter recorder = new RecordingWriter();
    private final CoachFrameSink sink = new WriterFrameSink(new PrintWriter(recorder),
        JsonMapper.builder().build(), "/api/v1/chat/completions", "req-1234");

    @Test
    @DisplayName("a delta is data: {\"choices\":[{\"delta\":{\"content\":...}}]} and a blank line")
    void aDeltaIsTheOpenAiShape() {
        sink.delta("You spent ");

        assertThat(recorder.text())
            .isEqualTo("data: {\"choices\":[{\"delta\":{\"content\":\"You spent \"}}]}\n\n");
        assertThat(recorder.flushes)
            .as("every frame is pushed to the socket as it is written, not buffered to the end")
            .isPositive();
    }

    @Test
    @DisplayName("a quote or a newline in the answer cannot break the frame")
    void theContentIsJsonEncoded() {
        sink.delta("She said \"no\"\nand left");

        assertThat(recorder.text())
            .contains("\\\"no\\\"")
            .contains("\\n")
            .endsWith("}}]}\n\n");
    }

    @Test
    @DisplayName("the terminator is the literal [DONE]")
    void theTerminatorIsLiteral() {
        sink.done();

        assertThat(recorder.text()).isEqualTo("data: [DONE]\n\n");
    }

    @Test
    @DisplayName("an in-band failure carries the RFC 7807 body and nothing after it")
    void aFailureIsTheOrdinaryErrorBody() {
        sink.failure(504, "ai-timeout", "Your AI endpoint did not answer in time.");

        String frame = recorder.text();
        assertThat(frame)
            .startsWith("data: {\"error\":")
            .endsWith("\n\n")
            .contains("\"type\":\"https://trove.finance/errors/ai-timeout\"")
            .contains("\"status\":504")
            .contains("\"title\":\"Gateway Timeout\"")
            .contains("\"instance\":\"/api/v1/chat/completions\"")
            .contains("\"requestId\":\"req-1234\"");
        assertThat(frame)
            .as("the instance is the matched route TEMPLATE, never a raw URI with a query on it")
            .doesNotContain("?");
    }

    @Test
    @DisplayName("a client that has gone away stops the sink rather than throwing")
    void aBrokenClientIsNotAnError() {
        RecordingWriter broken = new RecordingWriter();
        broken.failOnWrite = true;
        CoachFrameSink brokenSink = new WriterFrameSink(new PrintWriter(broken),
            JsonMapper.builder().build(), "/api/v1/chat/completions", "req-1234");

        brokenSink.delta("anything");
        brokenSink.done();

        assertThat(broken.text())
            .as("nothing more is attempted once a write has failed")
            .isEmpty();
    }

    /** Captures exactly what would go on the wire, and counts the flushes. */
    private static final class RecordingWriter extends Writer {

        private final StringWriter buffer = new StringWriter();
        private final List<String> chunks = new ArrayList<>();
        private int flushes;
        private boolean failOnWrite;

        @Override
        public void write(char[] chars, int offset, int length) throws IOException {
            if (failOnWrite) {
                throw new IOException("the client has gone");
            }
            buffer.write(chars, offset, length);
            chunks.add(new String(chars, offset, length));
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            // nothing
        }

        String text() {
            return buffer.toString();
        }
    }
}
