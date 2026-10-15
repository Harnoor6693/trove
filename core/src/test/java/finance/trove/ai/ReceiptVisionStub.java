package finance.trove.ai;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An {@link AiGateway} that points every member at a throwaway OpenAI-compatible vision server,
 * through the <strong>production</strong> {@link OpenAiCompatibleClient} (collapse step 14).
 *
 * <p><strong>Why the gateway is stubbed and the client is not.</strong> The stub binds to
 * {@code 127.0.0.1}, and {@code OutboundUrlPolicy} refuses loopback unless both keys of the two-key
 * opt-in are set. The install-wide half, {@code TROVE_EGRESS_ALLOW_PRIVATE}, is false for the whole
 * integration suite — {@code BaseIT} sets no such property, one Spring context is shared by every
 * IT, and a class cannot flip it without forking a second context, which is banned. So the
 * production {@code DefaultAiGateway} can never reach a stub from an IT, and every AI-backed
 * feature in this tree is tested the way {@code AiCategorisationIT} (step 17) and
 * {@code CoachStubGateway} (step 13) established: build the {@code ResolvedTarget} directly and
 * drive the real client. The egress policy's own decision table is {@code OutboundUrlPolicyTest}'s
 * subject and the route that runs it is {@code AiPolicyIT}'s.
 *
 * <p>It lives in {@code finance.trove.ai} because {@code AiStubServer} is package-private there,
 * and it is public because the tests that use it are collapse step 14's, in
 * {@code finance.trove.receipts}. It owns the stub server rather than exposing it, which is what
 * keeps that class package-private.
 *
 * <p>The tier is {@link PrivacyTier#LOCAL_ONLY} by default and {@link #vision} enforces it, so a
 * caller that passed {@link AiSensitivity#ORDINARY} where it should have passed {@code SENSITIVE}
 * is not silently accepted here either — and {@link #lastSensitivity()} records what was actually
 * passed, which is what the tests assert on rather than trusting the code.
 */
public final class ReceiptVisionStub implements AiGateway, AutoCloseable {

    /** The vision model the stub claims to serve. */
    public static final String VISION_MODEL = "qwen2.5-vl:7b";

    private static final String CHAT_PATH = "/chat/completions";

    private final AiStubServer stub;
    private final OpenAiCompatibleClient client = AiClientSupport.client();
    private final List<AiSensitivity> sensitivities = new ArrayList<>();
    private final List<String> contentTypes = new ArrayList<>();
    private volatile PrivacyTier tier = PrivacyTier.LOCAL_ONLY;
    private volatile boolean enabled = true;
    private volatile boolean hasVisionModel = true;

    private ReceiptVisionStub(AiStubServer stub) {
        this.stub = stub;
    }

    /** Starts a stub that has not yet been told what to answer. */
    public static ReceiptVisionStub start() {
        return new ReceiptVisionStub(AiStubServer.start());
    }

    /**
     * Makes the endpoint answer every vision call with this assistant message content.
     *
     * @param content what the model "says" — pass malformed JSON or prose to exercise the
     *                untrusted-answer path
     */
    public ReceiptVisionStub answering(String content) {
        stub.json(CHAT_PATH, "{\"choices\":[{\"message\":{\"content\":"
            + quote(content) + "}}],\"model\":\"" + VISION_MODEL + "\"}");
        return this;
    }

    /** Makes the endpoint answer with an HTTP status, so the client raises a typed failure. */
    public ReceiptVisionStub failingWith(int status) {
        stub.status(CHAT_PATH, status);
        return this;
    }

    /** Makes every handle report AI as switched off, without touching any database row. */
    public ReceiptVisionStub disabled() {
        this.enabled = false;
        return this;
    }

    /** Makes every handle report no vision model, which is the ordinary install. */
    public ReceiptVisionStub withoutVisionModel() {
        this.hasVisionModel = false;
        return this;
    }

    /** Puts every member on a cloud tier, so a SENSITIVE payload is refused before it is sent. */
    public ReceiptVisionStub cloudOnly() {
        this.tier = PrivacyTier.CLOUD_OK;
        return this;
    }

    /** What the caller actually passed on its most recent call. */
    public AiSensitivity lastSensitivity() {
        return sensitivities.isEmpty() ? null : sensitivities.get(sensitivities.size() - 1);
    }

    /** The content type the caller declared for the image on its most recent call. */
    public String lastContentType() {
        return contentTypes.isEmpty() ? null : contentTypes.get(contentTypes.size() - 1);
    }

    /** How many vision calls have been made through this gateway. */
    public int callCount() {
        return sensitivities.size();
    }

    /** How many HTTP requests the stub server itself received. Zero proves nothing was sent. */
    public int requestCount() {
        return stub.received().size();
    }

    /** The body of the most recent request the stub server saw. */
    public String lastRequestBody() {
        return stub.lastRequest().body();
    }

    @Override
    public void close() {
        stub.close();
    }

    @Override
    public AiHandle forUser(UUID userId) {
        AiEndpoint endpoint =
            stub.endpoint(null, VISION_MODEL, VISION_MODEL, Duration.ofSeconds(5));
        return new AiHandle() {

            @Override
            public boolean isEnabled() {
                return enabled;
            }

            @Override
            public PrivacyTier privacyTier() {
                return tier;
            }

            @Override
            public String chatModel() {
                return endpoint.model();
            }

            @Override
            public String visionModel() {
                return hasVisionModel ? VISION_MODEL : null;
            }

            @Override
            public AiCompletion chat(List<AiMessage> messages, AiSensitivity sensitivity) {
                throw new UnsupportedOperationException("step 13's, not step 14's");
            }

            @Override
            public AiCompletion chatStream(List<AiMessage> messages, AiSensitivity sensitivity,
                                           AiStreamListener listener) {
                throw new UnsupportedOperationException("step 13's, not step 14's");
            }

            @Override
            public AiCompletion vision(String prompt, byte[] image, String contentType,
                                       AiSensitivity sensitivity) {
                sensitivities.add(sensitivity);
                contentTypes.add(contentType);
                if (!enabled) {
                    throw new AiDisabledException("AI is off for this member");
                }
                if (!tier.permits(sensitivity)) {
                    // Exactly what the production gateway does, and before any connection opens:
                    // a member on CLOUD_OK has their receipt held back by their own setting.
                    throw new AiPolicyBlockedException(
                        "the member's privacy tier refuses this payload");
                }
                return client.vision(endpoint, prompt, image, contentType);
            }

            @Override
            public List<AiModel> models() {
                return client.models(endpoint);
            }
        };
    }

    /** Minimal JSON string quoting, enough for the answers these tests send. */
    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
