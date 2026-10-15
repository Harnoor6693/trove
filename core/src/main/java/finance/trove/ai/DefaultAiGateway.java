package finance.trove.ai;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import finance.trove.app.HttpClientProperties;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.ResolvedTarget;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * The one implementation of {@link AiGateway} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6).
 *
 * <p>It is where the four things that must happen on every AI call actually happen, in this order,
 * so that no later step has to remember them:
 * <ol>
 *   <li><strong>Is this member's AI on?</strong> Off or unconfigured raises
 *       {@link AiDisabledException}, which callers treat as "the feature is off" rather than as an
 *       error.</li>
 *   <li><strong>Does their privacy tier allow this payload?</strong> Checked <em>before</em> any
 *       connection is opened: a refusal that leaked the payload on its way to being refused would
 *       be worse than no refusal at all.</li>
 *   <li><strong>Does the destination still pass the egress policy?</strong> Re-evaluated here, on
 *       every call, and never cached from the moment the settings were saved. The install can
 *       withdraw {@code TROVE_EGRESS_ALLOW_PRIVATE} between a save and a call, and a name that
 *       resolved publicly a minute ago can resolve to {@code 169.254.169.254} now — which is
 *       exactly the attack the pinning exists to stop, and it is only stopped if the check is here
 *       rather than at save time.</li>
 *   <li><strong>Write the audit row.</strong> Success or failure, always, in its own transaction.</li>
 * </ol>
 *
 * <p>The settings themselves are read once per {@link #forUser} and held for the life of the handle,
 * which is one operation. That is a snapshot of the member's own preferences, not of a security
 * decision: the security decision is the policy check, and that is redone per call above.
 */
@Service
public class DefaultAiGateway implements AiGateway {

    private final AiSettingsRepository repository;
    private final FieldEncryptionService encryption;
    private final OutboundUrlPolicy urlPolicy;
    private final OpenAiCompatibleClient client;
    private final HttpClientProperties httpProperties;
    private final AiAuditRecorder audit;

    public DefaultAiGateway(AiSettingsRepository repository, FieldEncryptionService encryption,
                            OutboundUrlPolicy urlPolicy, OpenAiCompatibleClient client,
                            HttpClientProperties httpProperties, AiAuditRecorder audit) {
        this.repository = repository;
        this.encryption = encryption;
        this.urlPolicy = urlPolicy;
        this.client = client;
        this.httpProperties = httpProperties;
        this.audit = audit;
    }

    @Override
    public AiHandle forUser(UUID userId) {
        // An inactive member reads as one who never configured AI: off (B3-14).
        AiSettings settings = repository.findForActiveUser(userId)
            .orElseGet(() -> AiSettings.defaultsFor(userId));
        return new SettingsBackedHandle(userId, settings);
    }

    /**
     * Resolves the destination and the credentials for one call.
     *
     * <p>Public so that {@link AiSettingsService} can use the identical path for
     * {@code POST /v1/ai/settings/test} and {@code GET /v1/ai/models}: a "test connection" button
     * that resolved the endpoint differently from the real calls would be able to pass while the
     * real calls failed, which is the one thing such a button must never do.
     */
    AiEndpoint resolve(AiSettings settings) {
        ResolvedTarget target = urlPolicy.check(settings.getEndpointUrl(),
            settings.isAllowPrivateEndpoint());
        String apiKey = settings.hasApiKey() ? encryption.decrypt(settings.getApiKeyEnc()) : null;
        return new AiEndpoint(target, apiKey, settings.getModel(), settings.getVisionModel(),
            AiTimeouts.connect(settings.getConnectTimeoutMs(), httpProperties),
            AiTimeouts.read(settings.getReadTimeoutMs(), httpProperties));
    }

    /** True when the member has AI on and something to call. */
    static boolean usable(AiSettings settings) {
        return settings.isEnabled()
            && settings.getEndpointUrl() != null && !settings.getEndpointUrl().isBlank()
            && settings.getModel() != null && !settings.getModel().isBlank();
    }

    private final class SettingsBackedHandle implements AiHandle {

        private final UUID userId;
        private final AiSettings settings;

        private SettingsBackedHandle(UUID userId, AiSettings settings) {
            this.userId = userId;
            this.settings = settings;
        }

        @Override
        public boolean isEnabled() {
            return usable(settings);
        }

        @Override
        public PrivacyTier privacyTier() {
            return settings.getPrivacyTier();
        }

        @Override
        public String chatModel() {
            return settings.getModel();
        }

        @Override
        public String visionModel() {
            return settings.getVisionModel();
        }

        @Override
        public AiCompletion chat(List<AiMessage> messages, AiSensitivity sensitivity) {
            AiEndpoint endpoint = prepare(sensitivity);
            return audited("CHAT", endpoint, endpoint.model(),
                () -> client.chat(endpoint, messages));
        }

        @Override
        public AiCompletion chatStream(List<AiMessage> messages, AiSensitivity sensitivity,
                                       AiStreamListener listener) {
            AiEndpoint endpoint = prepare(sensitivity);
            return audited("CHAT_STREAM", endpoint, endpoint.model(),
                () -> client.chatStream(endpoint, messages, listener));
        }

        @Override
        public AiCompletion vision(String prompt, byte[] image, String contentType,
                                   AiSensitivity sensitivity) {
            AiEndpoint endpoint = prepare(sensitivity);
            return audited("VISION", endpoint, endpoint.visionModel(),
                () -> client.vision(endpoint, prompt, image, contentType));
        }

        @Override
        public List<AiModel> models() {
            AiEndpoint endpoint = prepare(AiSensitivity.ORDINARY);
            long started = System.nanoTime();
            try {
                List<AiModel> models = client.models(endpoint);
                record("MODELS", endpoint, null, started, true, null, null, null);
                return models;
            } catch (AiException e) {
                record("MODELS", endpoint, null, started, false, e.getSlug(), null, null);
                throw e;
            }
        }

        /**
         * The three guards, in the order that keeps the payload from travelling before it is
         * allowed to.
         */
        private AiEndpoint prepare(AiSensitivity sensitivity) {
            if (!usable(settings)) {
                throw new AiDisabledException(
                    "AI is not configured for this account. Turn it on in Settings and give it an "
                        + "endpoint and a model.");
            }
            if (!settings.getPrivacyTier().permits(sensitivity)) {
                throw new AiPolicyBlockedException(
                    "Your privacy setting is " + settings.getPrivacyTier()
                        + ", which does not allow your financial data to be sent to this endpoint. "
                        + "Set it to LOCAL_ONLY if the endpoint is a model you control.");
            }
            return resolve(settings);
        }

        private AiCompletion audited(String operation, AiEndpoint endpoint, String model,
                                     Supplier<AiCompletion> call) {
            long started = System.nanoTime();
            try {
                AiCompletion completion = call.get();
                record(operation, endpoint, model, started, true, null,
                    completion.promptTokens(), completion.completionTokens());
                return completion;
            } catch (AiException e) {
                record(operation, endpoint, model, started, false, e.getSlug(), null, null);
                throw e;
            }
        }

        private void record(String operation, AiEndpoint endpoint, String model, long startedAt,
                            boolean success, String slug, Integer promptTokens,
                            Integer outputTokens) {
            audit.record(userId, operation, endpoint.host(), model, settings.getPrivacyTier(),
                promptTokens, outputTokens, (System.nanoTime() - startedAt) / 1_000_000L, success,
                slug);
        }
    }
}
