package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.app.HttpClientProperties;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.ResolvedTarget;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The three guards {@link DefaultAiGateway} runs before anything leaves the process, and the audit
 * row it writes afterwards.
 *
 * <p>These are the properties collapse steps 13, 14 and 17 will rely on without re-checking, so
 * each one is asserted about the gateway rather than about a caller: "AI off means nothing is
 * sent", "the privacy tier is honoured before a connection is opened", "the egress policy runs on
 * every call and not once at save time", and "every call is recorded, including the ones that
 * failed".
 */
class DefaultAiGatewayTest {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final String KEY_HEX =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private AiSettingsRepository repository;
    private OutboundUrlPolicy urlPolicy;
    private OpenAiCompatibleClient client;
    private AiAuditRecorder audit;
    private DefaultAiGateway gateway;
    private FieldEncryptionService encryption;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AiSettingsRepository.class);
        urlPolicy = mock(OutboundUrlPolicy.class);
        client = mock(OpenAiCompatibleClient.class);
        audit = mock(AiAuditRecorder.class);
        encryption = new FieldEncryptionService(KEY_HEX);

        InetAddress ip = InetAddress.getByName("8.8.8.8");
        URI uri = URI.create("https://ai.example.test/v1");
        when(urlPolicy.check(anyString(), anyBoolean()))
            .thenReturn(new ResolvedTarget("ai.example.test", ip, 443, uri, List.of(ip)));

        gateway = new DefaultAiGateway(repository, encryption, urlPolicy, client,
            new HttpClientProperties(5_000, 30_000), audit);
    }

    @Test
    @DisplayName("a member with no row at all gets a handle that is simply off")
    void unconfiguredIsDisabledRatherThanAbsent() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.empty());

        AiHandle handle = gateway.forUser(USER);

        assertThat(handle).isNotNull();
        assertThat(handle.isEnabled()).isFalse();
        assertThat(handle.privacyTier())
            .as("a caller reading the tier before checking isEnabled must not get null")
            .isEqualTo(PrivacyTier.CLOUD_OK);
        assertThatThrownBy(() -> handle.chat(List.of(AiMessage.user("hi")),
            AiSensitivity.ORDINARY))
            .isInstanceOf(AiDisabledException.class);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("AI switched off refuses without resolving or connecting")
    void disabledRefusesBeforeAnythingLeaves() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(false,
            PrivacyTier.LOCAL_ONLY)));

        AiHandle handle = gateway.forUser(USER);

        assertThat(handle.isEnabled()).isFalse();
        assertThatThrownBy(() -> handle.vision("read this", new byte[] {1}, "image/png",
            AiSensitivity.SENSITIVE))
            .isInstanceOf(AiDisabledException.class);
        verifyNoInteractions(urlPolicy, client, audit);
    }

    @Test
    @DisplayName("a CLOUD_OK endpoint refuses a SENSITIVE payload before opening a connection")
    void privacyTierIsCheckedBeforeTheConnection() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.CLOUD_OK)));

        AiHandle handle = gateway.forUser(USER);

        assertThatThrownBy(() -> handle.chat(List.of(AiMessage.user("my salary is 92000")),
            AiSensitivity.SENSITIVE))
            .isInstanceOf(AiPolicyBlockedException.class);
        verifyNoInteractions(client);
        verify(urlPolicy, never()).check(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a LOCAL_ONLY endpoint takes the same SENSITIVE payload")
    void localOnlyPermitsSensitivePayloads() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.LOCAL_ONLY)));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", 3, 1));

        AiCompletion completion = gateway.forUser(USER)
            .chat(List.of(AiMessage.user("my salary is 92000")), AiSensitivity.SENSITIVE);

        assertThat(completion.content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("an ORDINARY payload goes to a cloud endpoint — the tier is not a blanket block")
    void ordinaryPayloadsGoToCloudEndpoints() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.CLOUD_OK)));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", null, null));

        assertThat(gateway.forUser(USER).chat(List.of(AiMessage.user("what is a TFSA?")),
            AiSensitivity.ORDINARY).content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("the egress policy runs on every call, not once when the settings were saved")
    void thePolicyIsRevaluatedPerCall() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.LOCAL_ONLY)));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", null, null));

        AiHandle handle = gateway.forUser(USER);
        handle.chat(List.of(AiMessage.user("one")), AiSensitivity.ORDINARY);
        handle.chat(List.of(AiMessage.user("two")), AiSensitivity.ORDINARY);
        handle.models();

        verify(urlPolicy, times(3))
            .check("https://ai.example.test/v1", true);
    }

    @Test
    @DisplayName("the stored key is decrypted for the call and reaches the client as plaintext")
    void decryptsTheKeyPerCall() {
        AiSettings settings = settings(true, PrivacyTier.LOCAL_ONLY);
        settings.setApiKeyEnc(encryption.encrypt("sk-live-key"));
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", null, null));

        gateway.forUser(USER).chat(List.of(AiMessage.user("hi")), AiSensitivity.ORDINARY);

        ArgumentCaptor<AiEndpoint> endpoint = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chat(endpoint.capture(), any());
        assertThat(endpoint.getValue().apiKey()).isEqualTo("sk-live-key");
        assertThat(endpoint.getValue().target().host()).isEqualTo("ai.example.test");
    }

    @Test
    @DisplayName("every call is audited, and a failure is audited with its slug")
    void auditsSuccessAndFailure() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.LOCAL_ONLY)));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", 11, 3));

        gateway.forUser(USER).chat(List.of(AiMessage.user("hi")), AiSensitivity.ORDINARY);

        verify(audit).record(eq(USER), eq("CHAT"), eq("ai.example.test"), eq("llama3"),
            eq(PrivacyTier.LOCAL_ONLY), eq(11), eq(3), anyLong(), eq(true), isNull());

        when(client.chat(any(), any()))
            .thenThrow(new AiUnreachableException("The AI endpoint could not be reached"));

        AiHandle handle = gateway.forUser(USER);
        assertThatThrownBy(() -> handle.chat(List.of(AiMessage.user("hi")),
            AiSensitivity.ORDINARY))
            .isInstanceOf(AiUnreachableException.class);

        verify(audit).record(eq(USER), eq("CHAT"), eq("ai.example.test"), eq("llama3"),
            eq(PrivacyTier.LOCAL_ONLY), isNull(), isNull(), anyLong(), eq(false),
            eq("ai-unreachable"));
    }

    @Test
    @DisplayName("the effective timeouts are the capped ones, not the stored ones")
    void appliesTheTimeoutCap() {
        AiSettings settings = settings(true, PrivacyTier.LOCAL_ONLY);
        settings.replaceWith(true, "https://ai.example.test/v1", "llama3", null, true,
            PrivacyTier.LOCAL_ONLY, 60_000, 600_000);
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings));
        when(client.chat(any(), any())).thenReturn(new AiCompletion("ok", "llama3", null, null));

        gateway.forUser(USER).chat(List.of(AiMessage.user("hi")), AiSensitivity.ORDINARY);

        ArgumentCaptor<AiEndpoint> endpoint = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chat(endpoint.capture(), any());
        assertThat(endpoint.getValue().connectTimeout().toMillis())
            .as("the install's 5 s connect limit wins over the member's 60 s")
            .isEqualTo(5_000);
        assertThat(endpoint.getValue().readTimeout().toMillis())
            .as("4 x the install's 30 s read timeout is the AI ceiling")
            .isEqualTo(120_000);
    }

    @Test
    @DisplayName("a streaming call reaches the client with its listener and audits as CHAT_STREAM")
    void streamsThroughTheClient() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.LOCAL_ONLY)));
        when(client.chatStream(any(), any(), any()))
            .thenReturn(new AiCompletion("Spend less", "llama3", null, null));
        AiStreamListener listener = mock(AiStreamListener.class);

        AiCompletion completion = gateway.forUser(USER).chatStream(
            List.of(AiMessage.system("Be brief."), AiMessage.assistant("Sure.")),
            AiSensitivity.SENSITIVE, listener);

        assertThat(completion.content()).isEqualTo("Spend less");
        ArgumentCaptor<AiEndpoint> endpoint = ArgumentCaptor.forClass(AiEndpoint.class);
        verify(client).chatStream(endpoint.capture(), any(), eq(listener));
        assertThat(endpoint.getValue().model()).isEqualTo("llama3");
        verify(audit).record(eq(USER), eq("CHAT_STREAM"), eq("ai.example.test"), eq("llama3"),
            eq(PrivacyTier.LOCAL_ONLY), isNull(), isNull(), anyLong(), eq(true), isNull());
    }

    @Test
    @DisplayName("a vision call is audited against the vision model, not the chat model")
    void visionUsesTheVisionModel() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.LOCAL_ONLY)));
        when(client.vision(any(), anyString(), any(), anyString()))
            .thenReturn(new AiCompletion("{\"total\":12.34}", "llava", 900, 20));

        AiHandle handle = gateway.forUser(USER);
        assertThat(handle.visionModel()).isEqualTo("llava");
        assertThat(handle.chatModel()).isEqualTo("llama3");

        handle.vision("Read this receipt.", new byte[] {1, 2, 3}, "image/jpeg",
            AiSensitivity.SENSITIVE);

        verify(audit).record(eq(USER), eq("VISION"), eq("ai.example.test"), eq("llava"),
            eq(PrivacyTier.LOCAL_ONLY), eq(900), eq(20), anyLong(), eq(true), isNull());
    }

    @Test
    @DisplayName("a receipt image is a SENSITIVE payload and a cloud tier refuses it")
    void visionIsBlockedByACloudTier() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.CLOUD_OK)));

        AiHandle handle = gateway.forUser(USER);

        assertThatThrownBy(() -> handle.vision("Read this receipt.", new byte[] {1}, "image/png",
            AiSensitivity.SENSITIVE))
            .isInstanceOf(AiPolicyBlockedException.class)
            .hasMessageContaining("LOCAL_ONLY");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("a failed model list is audited as a failure, with its slug")
    void auditsAFailedModelList() {
        when(repository.findForActiveUser(USER)).thenReturn(Optional.of(settings(true,
            PrivacyTier.CLOUD_OK)));
        when(client.models(any()))
            .thenThrow(new AiEndpointUnauthorizedException("the endpoint rejected the key"));

        AiHandle handle = gateway.forUser(USER);

        assertThatThrownBy(handle::models)
            .isInstanceOf(AiEndpointUnauthorizedException.class);
        verify(audit).record(eq(USER), eq("MODELS"), eq("ai.example.test"), isNull(),
            eq(PrivacyTier.CLOUD_OK), isNull(), isNull(), anyLong(), eq(false),
            eq("ai-endpoint-unauthorized"));
    }

    private static AiSettings settings(boolean enabled, PrivacyTier tier) {
        AiSettings settings = new AiSettings(USER);
        settings.replaceWith(enabled, "https://ai.example.test/v1", "llama3", "llava", true, tier,
            5_000, 120_000);
        return settings;
    }
}