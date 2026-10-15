package finance.trove.ai;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiConnectionTestResponse;
import finance.trove.ai.dto.AiModel;
import finance.trove.ai.dto.AiSettingsResponse;
import finance.trove.ai.dto.AiSettingsUpdateRequest;
import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.OutboundUrlPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes one member's own AI configuration ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §4.11/§6).
 *
 * <p>Every method takes the user id from the caller's signed token; none of them takes it from a
 * path, a body or a header, and there is no method that reads another member's row. That is the
 * whole tenancy story for this resource: a household mate and a household administrator have
 * exactly the same access to your endpoint and your API key as a stranger does, which is none.
 *
 * <p><strong>What happens on a save.</strong> The URL is normalised, the field and cross-field
 * rules run, the egress policy validates the destination <em>when the save could widen where this
 * member egresses</em> (see {@link #update}), the key is encrypted, the row is written, and — if
 * and only if one of the three egress-relevant fields changed — an {@code EGRESS_SETTING_CHANGE}
 * security event is registered for after the commit. It is {@code recordAfterCommit} rather than
 * {@code record} because it describes a row this transaction has just written: a
 * {@code REQUIRES_NEW} write would race its own commit, and this recorder swallows database errors
 * by design, so the event would be lost silently.
 */
@Service
public class AiSettingsService {

    private final AiSettingsRepository repository;
    private final FieldEncryptionService encryption;
    private final OutboundUrlPolicy urlPolicy;
    private final OpenAiCompatibleClient client;
    private final DefaultAiGateway gateway;
    private final AiAuditRecorder audit;
    private final SecurityEventRecorder securityEvents;

    public AiSettingsService(AiSettingsRepository repository, FieldEncryptionService encryption,
                             OutboundUrlPolicy urlPolicy, OpenAiCompatibleClient client,
                             DefaultAiGateway gateway, AiAuditRecorder audit,
                             SecurityEventRecorder securityEvents) {
        this.repository = repository;
        this.encryption = encryption;
        this.urlPolicy = urlPolicy;
        this.client = client;
        this.gateway = gateway;
        this.audit = audit;
        this.securityEvents = securityEvents;
    }

    /** The caller's settings, or the untouched defaults — never a 404 (§4.11). */
    @Transactional(readOnly = true)
    public AiSettingsResponse read(UUID userId) {
        return AiSettingsResponse.of(currentOrDefaults(userId));
    }

    /** Replaces the caller's settings. See the class comment for the order of operations. */
    @Transactional
    public AiSettingsResponse update(UUID userId, AiSettingsUpdateRequest request) {
        AiSettings settings = currentOrDefaults(userId);

        boolean wasEnabled = settings.isEnabled();
        boolean wasAllowingPrivate = settings.isAllowPrivateEndpoint();
        String previousUrl = settings.getEndpointUrl();

        boolean enabled = Boolean.TRUE.equals(request.enabled());
        boolean allowPrivate = Boolean.TRUE.equals(request.allowPrivateEndpoint());
        String endpointUrl = normaliseUrl(request.endpointUrl());
        String model = trimToNull(request.model());
        String visionModel = trimToNull(request.visionModel());

        if (request.apiKey() != null && request.apiKey().isBlank()) {
            throw new AiSettingsInvalidException("apiKey",
                "must not be blank; use DELETE /v1/ai/settings/api-key to remove the stored key");
        }
        // The spec says minLength 1 on both, and the spec is the contract: a blank string is a
        // malformed request, not a shorthand for "clear this". Sending null clears it. Without
        // this, a form that cannot tell "untouched" from "emptied" wipes a member's model with a
        // 200 and no field error anywhere.
        if (isBlankButPresent(request.model())) {
            throw new AiSettingsInvalidException("model",
                "must not be blank; send null to clear the chat model");
        }
        if (isBlankButPresent(request.visionModel())) {
            throw new AiSettingsInvalidException("visionModel",
                "must not be blank; send null to clear the vision model");
        }
        if (enabled && endpointUrl == null) {
            throw new AiSettingsInvalidException("endpointUrl",
                "an endpoint URL is required to turn AI on");
        }
        if (enabled && model == null) {
            throw new AiSettingsInvalidException("model",
                "a chat model is required to turn AI on");
        }

        // The destination is validated here so a member finds out about a bad URL on the form
        // rather than on their first receipt. It is validated AGAIN, and the address re-pinned, on
        // every outbound call — this check is a courtesy, not the control.
        //
        // Which is exactly why it does NOT run on a save that neither changes the URL nor widens
        // where this member may egress. PUT is a replace, so every save resends the stored URL,
        // and a check here would refuse the two edits that make an install SAFER: withdrawing
        // allowPrivateEndpoint from a saved LAN endpoint, and turning AI off. It would also leave
        // a member whose public endpoint's DNS has gone away unable to edit their model or their
        // timeouts. A new or changed URL, and any save that turns AI on or opts into private
        // targets, still goes through the policy before anything is written.
        boolean urlChanged = !java.util.Objects.equals(previousUrl, endpointUrl);
        boolean widensEgress = (enabled && !wasEnabled) || (allowPrivate && !wasAllowingPrivate);
        String endpointHost = null;
        if (endpointUrl != null) {
            endpointHost = urlChanged || widensEgress
                ? urlPolicy.check(endpointUrl, allowPrivate).host()
                : hostOf(endpointUrl);
        }

        settings.replaceWith(enabled, endpointUrl, model, visionModel, allowPrivate,
            request.privacyTier(),
            request.connectTimeoutMs() == null
                ? settings.getConnectTimeoutMs() : request.connectTimeoutMs(),
            request.readTimeoutMs() == null
                ? settings.getReadTimeoutMs() : request.readTimeoutMs());
        if (request.apiKey() != null) {
            settings.setApiKeyEnc(encryption.encrypt(request.apiKey()));
        }
        AiSettings saved = repository.save(settings);

        List<String> changed = changedEgressFields(wasEnabled, enabled, wasAllowingPrivate,
            allowPrivate, previousUrl, endpointUrl);
        if (!changed.isEmpty()) {
            recordEgressChange(userId, endpointHost, enabled, allowPrivate, changed);
        }
        return AiSettingsResponse.of(saved);
    }

    /**
     * Forgets the stored key and leaves everything else alone.
     *
     * <p>Idempotent, and silent about whether there was a key: answering differently would turn
     * the route into a way of asking whether an account has one configured.
     */
    @Transactional
    public void clearApiKey(UUID userId) {
        repository.findByUserId(userId).ifPresent(settings -> {
            if (settings.hasApiKey()) {
                settings.clearApiKey();
                repository.save(settings);
            }
        });
    }

    /**
     * Calls the configured endpoint's model list and records the outcome.
     *
     * <p>It deliberately does <em>not</em> require {@code enabled}: an endpoint and a model are
     * enough. Testing before switching the feature on is the order a member actually works in, and
     * refusing it would mean turning AI on blind and finding out afterwards.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> Two reasons, and both are
     * mistakes that are easy to make here. A transaction around this method would be held open for
     * the length of an HTTP call to somebody else's server — up to two minutes on a slow local
     * model — with a pooled database connection inside it. And the failure path writes
     * {@code lastTestOk = false} and then rethrows, so a surrounding transaction would roll that
     * write back and the settings screen would show the result of the last test that <em>passed</em>
     * as though it were current. Each repository call here runs in its own short transaction.
     *
     * <p><strong>And that is why the outcome is written with a targeted statement</strong>
     * ({@link AiSettingsRepository#recordTestOutcome}) rather than by saving the entity back. The
     * row was read before an HTTP call that may take two minutes, so the object in hand is a
     * detached snapshot that old; {@code save()} on it is a JPA merge that rewrites every column,
     * so a {@code DELETE /v1/ai/settings/api-key} or a {@code PUT} turning AI off that landed
     * during the call would be silently reverted — restoring a key the member had just forgotten.
     * The two columns this method owns are the only two it writes.
     */
    public AiConnectionTestResponse testConnection(UUID userId) {
        AiSettings settings = requireConfigured(userId);
        AiEndpoint endpoint = gateway.resolve(settings);
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        try {
            List<AiModel> models = client.models(endpoint);
            boolean chatModelPresent = models.stream()
                .anyMatch(model -> settings.getModel().equals(model.id()));
            if (!chatModelPresent) {
                throw new AiModelMissingException("The endpoint " + endpoint.host()
                    + " does not serve the model '" + settings.getModel() + "'");
            }
            Boolean visionPresent = settings.getVisionModel() == null
                ? null
                : models.stream().anyMatch(model -> settings.getVisionModel().equals(model.id()));

            repository.recordTestOutcome(userId, checkedAt, true);
            audit.record(userId, "TEST", endpoint.host(), settings.getModel(),
                settings.getPrivacyTier(), null, null, elapsedMs(started), true, null);
            return AiConnectionTestResponse.passed(endpoint.host(), checkedAt, models.size(),
                visionPresent);
        } catch (AiException e) {
            repository.recordTestOutcome(userId, checkedAt, false);
            audit.record(userId, "TEST", endpoint.host(), settings.getModel(),
                settings.getPrivacyTier(), null, null, elapsedMs(started), false, e.getSlug());
            throw e;
        }
    }

    /**
     * The models the caller's endpoint serves.
     *
     * <p>This asks for an endpoint and <strong>nothing else</strong> — not {@code enabled}, and
     * pointedly not a model. Its whole purpose is to let a member pick a model from the real list
     * instead of typing an id into a free-text box, so requiring a model first would make the route
     * unusable by exactly the person it was added for. {@link #testConnection} does require one,
     * because verifying that the endpoint serves the chosen model is what that route is.
     *
     * <p>Not {@code @Transactional}, for the first of the two reasons on {@link #testConnection}:
     * it makes an HTTP call to a third-party server and must not hold a database connection while
     * it waits.
     */
    public List<AiModel> models(UUID userId) {
        AiSettings settings = requireEndpoint(userId);
        AiEndpoint endpoint = gateway.resolve(settings);
        long started = System.nanoTime();
        try {
            List<AiModel> models = client.models(endpoint);
            audit.record(userId, "MODELS", endpoint.host(), settings.getModel(),
                settings.getPrivacyTier(), null, null, elapsedMs(started), true, null);
            return models;
        } catch (AiException e) {
            audit.record(userId, "MODELS", endpoint.host(), settings.getModel(),
                settings.getPrivacyTier(), null, null, elapsedMs(started), false, e.getSlug());
            throw e;
        }
    }

    AiSettings currentOrDefaults(UUID userId) {
        return repository.findByUserId(userId).orElseGet(() -> AiSettings.defaultsFor(userId));
    }

    /** An endpoint is enough — {@code GET /v1/ai/models} is how a member finds a model. */
    private AiSettings requireEndpoint(UUID userId) {
        AiSettings settings = currentOrDefaults(userId);
        if (settings.getEndpointUrl() == null || settings.getEndpointUrl().isBlank()) {
            throw new AiDisabledException("No AI endpoint is configured for this account. Save an "
                + "endpoint URL in Settings first.");
        }
        return settings;
    }

    /** An endpoint and a model, because the test verifies that the endpoint serves that model. */
    private AiSettings requireConfigured(UUID userId) {
        AiSettings settings = requireEndpoint(userId);
        if (settings.getModel() == null || settings.getModel().isBlank()) {
            throw new AiDisabledException("No chat model is configured for this account. Pick one "
                + "from GET /v1/ai/models and save it in Settings first.");
        }
        return settings;
    }

    /**
     * Registers the {@code EGRESS_SETTING_CHANGE} event of §6.
     *
     * <p>The metadata carries the endpoint's <strong>host</strong> and never the full URL. A path
     * or a query string on an AI endpoint routinely carries an access token — several hosted
     * gateways authenticate that way — and this row is readable by its subject at
     * {@code GET /v1/me/security-events}, which is exactly where a key must not appear.
     */
    private void recordEgressChange(UUID userId, String endpointHost, boolean enabled,
                                    boolean allowPrivate, List<String> changed) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpointHost", endpointHost);
        metadata.put("enabled", enabled);
        metadata.put("allowPrivateEndpoint", allowPrivate);
        metadata.put("changed", changed);
        securityEvents.recordAfterCommit(SecurityEventType.EGRESS_SETTING_CHANGE, userId, userId,
            metadata);
    }

    private static List<String> changedEgressFields(boolean wasEnabled, boolean enabled,
                                                    boolean wasAllowingPrivate,
                                                    boolean allowPrivate, String previousUrl,
                                                    String endpointUrl) {
        List<String> changed = new ArrayList<>(3);
        if (wasEnabled != enabled) {
            changed.add("enabled");
        }
        if (!java.util.Objects.equals(previousUrl, endpointUrl)) {
            changed.add("endpointUrl");
        }
        if (wasAllowingPrivate != allowPrivate) {
            changed.add("allowPrivateEndpoint");
        }
        return List.copyOf(changed);
    }

    /**
     * Trims, drops a trailing slash, and treats blank as absent.
     *
     * <p>The trailing slash matters: the client appends {@code /chat/completions} to this value, so
     * a stored {@code https://host/v1/} would produce {@code https://host/v1//chat/completions},
     * which a surprising number of servers answer with a 404 that reads like a missing model.
     */
    private static String normaliseUrl(String url) {
        String trimmed = trimToNull(url);
        if (trimmed == null) {
            return null;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The host of an already-stored URL, for the audit metadata, when the policy did not run.
     *
     * <p>Only ever called with a URL a previous save put through {@link OutboundUrlPolicy}, so a
     * null here means the stored value has become unparseable rather than that a check was skipped;
     * the metadata then carries no host, which is the honest answer.
     */
    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static boolean isBlankButPresent(String value) {
        return value != null && value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
