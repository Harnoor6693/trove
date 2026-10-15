package finance.trove.ai;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An {@link AiGateway} that points every member at an {@link AiStubServer}, through the
 * <strong>production</strong> {@link OpenAiCompatibleClient} (collapse step 13).
 *
 * <p><strong>Why the gateway is stubbed and the client is not.</strong> {@link AiStubServer} binds
 * to {@code 127.0.0.1}, and {@code OutboundUrlPolicy} refuses loopback unless both keys of the
 * two-key opt-in are set. The install-wide half, {@code TROVE_EGRESS_ALLOW_PRIVATE}, is false for
 * the whole integration suite — {@code BaseIT} sets no such property, one Spring context is shared
 * by every IT, and a class cannot flip it without forking a second context, which is banned. So the
 * production {@code DefaultAiGateway} can never reach a stub from an IT, and every AI-backed
 * feature in this tree is tested the same way {@code AiCategorisationIT} (step 17) established:
 * build the {@code ResolvedTarget} directly through {@link AiStubServer#endpoint} and drive the
 * real client. The egress policy's own decision table is {@code OutboundUrlPolicyTest}'s subject
 * and the route that runs it is {@code AiPolicyIT}'s; the per-call audit row is
 * {@code DefaultAiGatewayTest}'s.
 *
 * <p>The tier is {@link PrivacyTier#LOCAL_ONLY} and {@link AiHandle#chat} and
 * {@link AiHandle#chatStream} both enforce it, so a caller that passed
 * {@link AiSensitivity#ORDINARY} where it should have passed {@code SENSITIVE} is not silently
 * accepted here either — and {@link #lastSensitivity()} records what was actually passed, which is
 * what the coach's tests assert on rather than trusting the code.
 */
final class CoachStubGateway implements AiGateway {

    /** The model the stub claims to be. */
    static final String MODEL = "qwen2.5:14b";

    private final AiStubServer stub;
    private final OpenAiCompatibleClient client = AiClientSupport.client();
    private final List<AiSensitivity> sensitivities = new ArrayList<>();
    private final List<List<AiMessage>> prompts = new ArrayList<>();
    private volatile PrivacyTier tier = PrivacyTier.LOCAL_ONLY;
    private volatile boolean enabled = true;

    CoachStubGateway(AiStubServer stub) {
        this.stub = stub;
    }

    /** Makes every handle report AI as switched off, without touching any database row. */
    CoachStubGateway disabled() {
        this.enabled = false;
        return this;
    }

    /** Puts every member on a cloud tier, so a SENSITIVE payload is refused. */
    CoachStubGateway cloudOnly() {
        this.tier = PrivacyTier.CLOUD_OK;
        return this;
    }

    /** What the caller actually passed on its most recent call. */
    AiSensitivity lastSensitivity() {
        return sensitivities.isEmpty() ? null : sensitivities.get(sensitivities.size() - 1);
    }

    /** Every sensitivity passed so far, in order. */
    List<AiSensitivity> sensitivities() {
        return List.copyOf(sensitivities);
    }

    /** The exact message list the most recent call sent. */
    List<AiMessage> lastPrompt() {
        return prompts.isEmpty() ? List.of() : prompts.get(prompts.size() - 1);
    }

    /** The prompt of the {@code index}-th call, in call order. */
    List<AiMessage> promptAt(int index) {
        return prompts.get(index);
    }

    /** How many calls have been made through this gateway. */
    int callCount() {
        return sensitivities.size();
    }

    @Override
    public AiHandle forUser(UUID userId) {
        AiEndpoint endpoint = stub.endpoint(null, MODEL);
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
                return null;
            }

            @Override
            public AiCompletion chat(List<AiMessage> messages, AiSensitivity sensitivity) {
                record(messages, sensitivity);
                return client.chat(endpoint, messages);
            }

            @Override
            public AiCompletion chatStream(List<AiMessage> messages, AiSensitivity sensitivity,
                                           AiStreamListener listener) {
                record(messages, sensitivity);
                return client.chatStream(endpoint, messages, listener);
            }

            @Override
            public AiCompletion vision(String prompt, byte[] image, String contentType,
                                       AiSensitivity sensitivity) {
                throw new UnsupportedOperationException("step 14's, not step 13's");
            }

            @Override
            public List<AiModel> models() {
                return client.models(endpoint);
            }

            private void record(List<AiMessage> messages, AiSensitivity sensitivity) {
                sensitivities.add(sensitivity);
                prompts.add(List.copyOf(messages));
                if (!enabled) {
                    throw new AiDisabledException("AI is off for this member");
                }
                if (!tier.permits(sensitivity)) {
                    throw new AiPolicyBlockedException(
                        "the member's privacy tier refuses this payload");
                }
            }
        };
    }
}
