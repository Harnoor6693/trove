package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.ai.dto.AiConnectionTestResponse;
import finance.trove.ai.dto.AiModel;
import finance.trove.ai.dto.AiSettingsResponse;
import finance.trove.ai.dto.AiSettingsUpdateRequest;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.app.HttpClientProperties;
import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.net.ResolvedTarget;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The save path: what is written, what is refused, what is encrypted, and what is audited.
 */
class AiSettingsServiceTest {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
    private static final String KEY_HEX =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String URL = "https://ai.example.test/v1";

    private AiSettingsRepository repository;
    private OutboundUrlPolicy urlPolicy;
    private OpenAiCompatibleClient client;
    private SecurityEventRecorder securityEvents;
    private FieldEncryptionService encryption;
    private AiSettingsService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AiSettingsRepository.class);
        urlPolicy = mock(OutboundUrlPolicy.class);
        client = mock(OpenAiCompatibleClient.class);
        securityEvents = mock(SecurityEventRecorder.class);
        encryption = new FieldEncryptionService(KEY_HEX);
        AiAuditRecorder audit = mock(AiAuditRecorder.class);

        InetAddress ip = InetAddress.getByName("203.0.113.10");
        when(urlPolicy.check(anyString(), anyBoolean())).thenReturn(new ResolvedTarget(
            "ai.example.test", ip, 443, URI.create(URL), List.of(ip)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DefaultAiGateway gateway = new DefaultAiGateway(repository, encryption, urlPolicy, client,
            new HttpClientProperties(5_000, 30_000), audit);
        service = new AiSettingsService(repository, encryption, urlPolicy, client, gateway, audit,
            securityEvents);
    }

    @Test
    @DisplayName("a member who has never saved anything reads the defaults, not a 404")
    void readsDefaults() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        AiSettingsResponse response = service.read(USER);

        assertThat(response.enabled()).isFalse();
        assertThat(response.endpointUrl()).isNull();
        assertThat(response.hasApiKey()).isFalse();
        assertThat(response.privacyTier()).isEqualTo(PrivacyTier.CLOUD_OK);
        assertThat(response.updatedAt())
            .as("nothing has been written, so there is no write to timestamp")
            .isNull();
    }

    @Test
    @DisplayName("the API key is stored encrypted and never comes back")
    void encryptsTheKeyAndNeverReturnsIt() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        AiSettingsResponse response = service.update(USER,
            request(true, URL, "sk-live-key", "llama3"));

        assertThat(response.hasApiKey()).isTrue();
        ArgumentCaptor<AiSettings> saved = ArgumentCaptor.forClass(AiSettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getApiKeyEnc())
            .as("the column holds versioned ciphertext, never the key")
            .startsWith("v1:")
            .doesNotContain("sk-live-key");
        assertThat(encryption.decrypt(saved.getValue().getApiKeyEnc()))
            .as("and it round-trips, so the gateway can actually use it")
            .isEqualTo("sk-live-key");
    }

    @Test
    @DisplayName("a save with no apiKey leaves the stored key alone")
    void anAbsentKeyIsNotAClear() {
        AiSettings existing = new AiSettings(USER);
        existing.setApiKeyEnc(encryption.encrypt("sk-existing"));
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));

        AiSettingsResponse response = service.update(USER, request(true, URL, null, "llama3"));

        assertThat(response.hasApiKey()).isTrue();
        assertThat(encryption.decrypt(existing.getApiKeyEnc())).isEqualTo("sk-existing");
    }

    @Test
    @DisplayName("a blank apiKey is refused, because clearing has its own route")
    void aBlankKeyIsRefused() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        AiSettingsUpdateRequest request = request(true, URL, "   ", "llama3");

        assertThatThrownBy(() -> service.update(USER, request))
            .isInstanceOf(AiSettingsInvalidException.class)
            .satisfies(thrown -> assertThat(
                ((AiSettingsInvalidException) thrown).getFieldErrors())
                .as("the message has to land under the API key box, not as a banner")
                .singleElement()
                .extracting(ErrorResponse.FieldError::field)
                .isEqualTo("apiKey"));
    }

    @Test
    @DisplayName("turning AI on without an endpoint or a model is refused, field by field")
    void enablingNeedsAnEndpointAndAModel() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER, request(true, null, null, "llama3")))
            .isInstanceOf(AiSettingsInvalidException.class)
            .hasMessageContaining("endpoint URL");
        assertThatThrownBy(() -> service.update(USER, request(true, URL, null, null)))
            .isInstanceOf(AiSettingsInvalidException.class)
            .hasMessageContaining("chat model");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an endpoint the egress policy refuses never reaches the database")
    void aRefusedUrlIsNotSaved() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        when(urlPolicy.check(anyString(), anyBoolean()))
            .thenThrow(new OutboundUrlPolicyException("resolves to a loopback address"));
        AiSettingsUpdateRequest request =
            request(true, "http://localhost:11434/v1", null, "llama3");

        assertThatThrownBy(() -> service.update(USER, request))
            .isInstanceOf(OutboundUrlPolicyException.class);
        verify(repository, never()).save(any());
        verify(securityEvents, never()).recordAfterCommit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a trailing slash is trimmed, or every request path would be doubled")
    void normalisesTheUrl() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        AiSettingsResponse response =
            service.update(USER, request(true, "https://ai.example.test/v1///", null, "llama3"));

        assertThat(response.endpointUrl()).isEqualTo(URL);
    }

    @Test
    @DisplayName("an egress-relevant change writes EGRESS_SETTING_CHANGE with the host, not the URL")
    void writesTheEgressSecurityEvent() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        service.update(USER, request(true, URL + "/secret-token-path", null, "llama3"));

        ArgumentCaptor<Map<String, Object>> metadata = captureMetadata();
        assertThat(metadata.getValue())
            .containsEntry("endpointHost", "ai.example.test")
            .containsEntry("enabled", true)
            .containsEntry("allowPrivateEndpoint", true);
        assertThat(metadata.getValue().get("changed").toString())
            .contains("enabled")
            .contains("endpointUrl");
        assertThat(metadata.getValue().toString())
            .as("a path or query on an AI endpoint routinely carries a token, and this row is "
                + "readable by its subject at GET /v1/me/security-events")
            .doesNotContain("secret-token-path");
    }

    @Test
    @DisplayName("a save that changes nothing egress-relevant writes no security event")
    void noEventForANonEgressChange() {
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(true, URL, "llama3", null, true, PrivacyTier.CLOUD_OK, 5_000,
            120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));

        service.update(USER, request(true, URL, null, "qwen2.5:14b"));

        verify(securityEvents, never()).recordAfterCommit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("clearing the key leaves the endpoint and the models in place")
    void clearingTheKeyKeepsEverythingElse() {
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(true, URL, "llama3", "llava", false, PrivacyTier.CLOUD_OK, 5_000,
            120_000);
        existing.setApiKeyEnc(encryption.encrypt("sk-live-key"));
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));

        service.clearApiKey(USER);

        assertThat(existing.hasApiKey()).isFalse();
        assertThat(existing.getEndpointUrl()).isEqualTo(URL);
        assertThat(existing.getModel()).isEqualTo("llama3");
        assertThat(existing.getVisionModel()).isEqualTo("llava");
    }

    @Test
    @DisplayName("clearing a key that was never there is a no-op, not a 404")
    void clearingIsIdempotent() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        service.clearApiKey(USER);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a connection test records its outcome and reports the vision model separately")
    void testConnectionRecordsTheOutcome() {
        AiSettings existing = configured("llama3", "llava");
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(
            new AiModel("llama3", null, null), new AiModel("llava", null, null)));

        AiConnectionTestResponse response = service.testConnection(USER);

        assertThat(response.ok()).isTrue();
        assertThat(response.endpointHost()).isEqualTo("ai.example.test");
        assertThat(response.modelCount()).isEqualTo(2);
        assertThat(response.visionModelPresent()).isTrue();
        verify(repository).recordTestOutcome(eq(USER), any(Instant.class), eq(true));
    }

    @Test
    @DisplayName("a connection test writes its two columns and never merges the row back")
    void testConnectionDoesNotRewriteTheSettingsRow() {
        AiSettings existing = configured("llama3", null);
        existing.setApiKeyEnc(encryption.encrypt("sk-live-key"));
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(new AiModel("llama3", null, null)));

        service.testConnection(USER);

        verify(repository, never()).save(any());
        verify(repository).recordTestOutcome(eq(USER), any(Instant.class), eq(true));
        // The row is read, then an HTTP call runs for as long as the endpoint likes, then the
        // outcome is written. save() on that stale snapshot would merge every column of it, so a
        // DELETE .../api-key or an `enabled: false` that landed in the window would be silently
        // undone — the key would come back. There is no @Version to catch it.
    }

    @Test
    @DisplayName("a chat model the endpoint does not serve fails the test and is recorded as failed")
    void testConnectionFailsOnAMissingChatModel() {
        AiSettings existing = configured("llama3", null);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(new AiModel("mistral", null, null)));

        assertThatThrownBy(() -> service.testConnection(USER))
            .isInstanceOf(AiModelMissingException.class)
            .hasMessageContaining("llama3");
        verify(repository).recordTestOutcome(eq(USER), any(Instant.class), eq(false));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a missing vision model is reported, not refused — receipts fall back to manual")
    void aMissingVisionModelIsOnlyReported() {
        AiSettings existing = configured("llama3", "llava");
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(new AiModel("llama3", null, null)));

        assertThat(service.testConnection(USER).visionModelPresent()).isFalse();
    }

    @Test
    @DisplayName("test and models refuse with ai-disabled when nothing is configured")
    void unconfiguredCannotBeTested() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection(USER))
            .isInstanceOf(AiDisabledException.class);
        assertThatThrownBy(() -> service.models(USER))
            .isInstanceOf(AiDisabledException.class);
    }

    @Test
    @DisplayName("the model list is reachable with no model saved — it is how a model is chosen")
    void modelsNeedsOnlyAnEndpoint() {
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(false, URL, null, null, false, PrivacyTier.CLOUD_OK, 5_000, 120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(new AiModel("llama3", null, null)));

        assertThat(service.models(USER))
            .as("requiring a model here would mean typing one into the free-text box this route "
                + "was added to replace")
            .extracting(AiModel::id)
            .containsExactly("llama3");
    }

    @Test
    @DisplayName("the connection test still needs a model — verifying it is served IS the test")
    void testConnectionStillNeedsAModel() {
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(false, URL, null, null, false, PrivacyTier.CLOUD_OK, 5_000, 120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.testConnection(USER))
            .isInstanceOf(AiDisabledException.class)
            .hasMessageContaining("chat model");
    }

    @Test
    @DisplayName("a blank model or vision model is a field error, never a silent clear")
    void aBlankModelIsRefused() {
        when(repository.findByUserId(USER)).thenReturn(Optional.empty());
        AiSettingsUpdateRequest blankModel = new AiSettingsUpdateRequest(false, URL, null, "  ",
            null, false, PrivacyTier.CLOUD_OK, null, null);
        AiSettingsUpdateRequest blankVision = new AiSettingsUpdateRequest(false, URL, null,
            "llama3", "", false, PrivacyTier.CLOUD_OK, null, null);

        assertThatThrownBy(() -> service.update(USER, blankModel))
            .isInstanceOf(AiSettingsInvalidException.class)
            .satisfies(thrown -> assertThat(((AiSettingsInvalidException) thrown).getFieldErrors())
                .singleElement()
                .extracting(ErrorResponse.FieldError::field)
                .isEqualTo("model"));
        assertThatThrownBy(() -> service.update(USER, blankVision))
            .isInstanceOf(AiSettingsInvalidException.class)
            .satisfies(thrown -> assertThat(((AiSettingsInvalidException) thrown).getFieldErrors())
                .singleElement()
                .extracting(ErrorResponse.FieldError::field)
                .isEqualTo("visionModel"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("turning AI off, or withdrawing the private opt-in, is never refused for the "
        + "URL it has to resend")
    void aSafetyIncreasingSaveIsNotRefusedByTheSaveTimeCheck() {
        String lanUrl = "http://192.168.1.50:11434/v1";
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(true, lanUrl, "llama3", null, true, PrivacyTier.LOCAL_ONLY, 5_000,
            120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        // The install has since withdrawn TROVE_EGRESS_ALLOW_PRIVATE, so the stored URL no longer
        // passes anything. PUT is a replace: the form sends the URL back with every save.
        when(urlPolicy.check(anyString(), anyBoolean()))
            .thenThrow(new OutboundUrlPolicyException("Plain http is only allowed for a private "
                + "LAN target (TROVE_EGRESS_ALLOW_PRIVATE)"));

        AiSettingsResponse offAndClosed = service.update(USER, new AiSettingsUpdateRequest(
            false, lanUrl, null, "llama3", null, false, PrivacyTier.LOCAL_ONLY, null, null));

        assertThat(offAndClosed.enabled())
            .as("refusing the request that turns the risky thing OFF is the wrong way round")
            .isFalse();
        assertThat(offAndClosed.allowPrivateEndpoint()).isFalse();
        assertThat(offAndClosed.endpointUrl()).isEqualTo(lanUrl);
        verify(urlPolicy, never()).check(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a save that turns AI on, or opts into private targets, is still checked")
    void awideningSaveIsStillChecked() {
        String lanUrl = "http://192.168.1.50:11434/v1";
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(false, lanUrl, "llama3", null, false, PrivacyTier.LOCAL_ONLY, 5_000,
            120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(urlPolicy.check(anyString(), anyBoolean()))
            .thenThrow(new OutboundUrlPolicyException("resolves to a private (RFC 1918) address"));

        assertThatThrownBy(() -> service.update(USER, new AiSettingsUpdateRequest(
            true, lanUrl, null, "llama3", null, false, PrivacyTier.LOCAL_ONLY, null, null)))
            .as("turning it on is a widening save")
            .isInstanceOf(OutboundUrlPolicyException.class);
        assertThatThrownBy(() -> service.update(USER, new AiSettingsUpdateRequest(
            false, lanUrl, null, "llama3", null, true, PrivacyTier.LOCAL_ONLY, null, null)))
            .as("opting into private targets is a widening save")
            .isInstanceOf(OutboundUrlPolicyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("test works before the feature is switched on, which is the order people work in")
    void testDoesNotRequireEnabled() {
        AiSettings existing = new AiSettings(USER);
        existing.replaceWith(false, URL, "llama3", null, false, PrivacyTier.CLOUD_OK, 5_000,
            120_000);
        when(repository.findByUserId(USER)).thenReturn(Optional.of(existing));
        when(client.models(any())).thenReturn(List.of(new AiModel("llama3", null, null)));

        assertThat(service.testConnection(USER).ok()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> captureMetadata() {
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(securityEvents).recordAfterCommit(eq(SecurityEventType.EGRESS_SETTING_CHANGE),
            eq(USER), eq(USER), metadata.capture());
        return metadata;
    }

    private static AiSettings configured(String model, String visionModel) {
        AiSettings settings = new AiSettings(USER);
        settings.replaceWith(true, URL, model, visionModel, false, PrivacyTier.CLOUD_OK, 5_000,
            120_000);
        return settings;
    }

    private static AiSettingsUpdateRequest request(boolean enabled, String url, String apiKey,
                                                   String model) {
        return new AiSettingsUpdateRequest(enabled, url, apiKey, model, null, true,
            PrivacyTier.CLOUD_OK, null, null);
    }
}
