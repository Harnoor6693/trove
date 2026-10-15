package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The outbound-URL policy as a member experiences it on the settings form (gap P2-10, §1.6, §6).
 *
 * <p>{@code OutboundUrlPolicyTest} is the decision table — every range, both address families, the
 * rebinding case. This class asserts the two things a unit test on the validator cannot: that the
 * AI settings route actually runs it, and that a refusal is a 400 the form can render rather than
 * a 500.
 *
 * <p><strong>What this suite can and cannot cover.</strong> The install-wide half of the two-key
 * opt-in is {@code TROVE_EGRESS_ALLOW_PRIVATE}, and it is false for the whole integration suite —
 * {@code BaseIT} sets no such property and one context is shared by every IT, so a class cannot
 * flip it without forking a second Spring context. So the "refused" half of every private-target
 * case is proved here, end to end, and the "allowed when BOTH keys are set" half is proved by
 * {@code OutboundUrlPolicyTest#bothKeysAreRequired}, which constructs the policy with the install
 * flag on. Between them every cell of the table is covered; neither alone covers it.
 *
 * <p>The one cell that must NOT depend on that split is the metadata address, and it does not:
 * {@code OutboundUrlPolicyTest#theOptInNeverReachesLinkLocal} runs it with both keys ON. Without
 * that test the assertion below would read as "refused, opt-in or not" while only ever exercising
 * the flag-off half — which is how {@code 169.254.169.254} would stay reachable on precisely the
 * installs the LAN-Ollama opt-in was written for.
 */
class AiPolicyIT extends AuthFlowSupport {

    @Test
    @DisplayName("a public https endpoint is accepted")
    void publicHttpsIsAccepted() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "policy-https");

        HttpResponse<String> response = save(member, "https://203.0.113.10/v1", false);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("endpointUrl").asString())
            .isEqualTo("https://203.0.113.10/v1");
    }

    @Test
    @DisplayName("plain http to a public host is refused even with the member's own opt-in")
    void publicHttpIsRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "policy-http");

        HttpResponse<String> response = save(member, "http://203.0.113.10/v1", true);

        assertRefused(response);
        assertThat(response.body())
            .as("the message names the rule so the form can say something better than 'invalid'")
            .contains("TROVE_EGRESS_ALLOW_PRIVATE");
    }

    @Test
    @DisplayName("a LAN endpoint is refused while the install does not allow private targets")
    void privateEndpointNeedsBothKeys() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "policy-lan");

        assertRefused(save(member, "http://127.0.0.1:11434/v1", true));
        assertRefused(save(member, "https://127.0.0.1:11434/v1", true));
        assertRefused(save(member, "https://192.168.1.50:11434/v1", true));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_settings WHERE user_id = ?", Long.class, member.userId()))
            .as("a refused URL must not reach the database")
            .isZero();
    }

    @Test
    @DisplayName("the cloud metadata address is refused, opt-in or not")
    void metadataAddressIsRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "policy-metadata");

        assertRefused(save(member, "https://169.254.169.254/v1", true));
        assertRefused(save(member, "https://169.254.1.1/v1", true));
    }

    @Test
    @DisplayName("credentials before the host are refused — they read public and connect private")
    void userinfoIsRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "policy-userinfo");

        HttpResponse<String> response =
            save(member, "https://ai.example.test@127.0.0.1/v1", true);

        assertRefused(response);
    }

    private void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("outbound-url-rejected");
    }

    private HttpResponse<String> save(Session member, String endpointUrl,
                                      boolean allowPrivateEndpoint) {
        return putJson(appUrl("/v1/ai/settings"),
            Json.object("enabled", true, "endpointUrl", endpointUrl, "model", "llama3",
                "allowPrivateEndpoint", allowPrivateEndpoint, "privacyTier", "LOCAL_ONLY"),
            "Authorization", "Bearer " + member.accessToken());
    }
}
