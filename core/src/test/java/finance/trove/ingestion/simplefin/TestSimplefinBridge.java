package finance.trove.ingestion.simplefin;

import finance.trove.app.HttpClientConfig;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import java.time.LocalDate;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * A SimpleFIN bridge the integration suite can drive, standing in for the real one.
 *
 * <p><strong>Why a double is unavoidable here.</strong> A bridge is reached over the network, and
 * {@link OutboundUrlPolicy} refuses every address a test can actually listen on: WireMock binds to
 * loopback, and the policy's single-argument {@code check} refuses loopback, RFC 1918 and
 * link-local outright. Making the SimpleFIN client ask for the private opt-in instead would be a
 * real weakening of the egress rule to make a test pass, so the network half is proved where it
 * can be proved honestly — {@code SimplefinBridgeClientTest} runs the production client against a
 * real socket, a real pinned {@code RestClient} and a real response cap — and the DATABASE half,
 * which is what {@code SimpleFinIT} is for, runs against this.
 *
 * <p>It is {@code @Primary} rather than a {@code @TestConfiguration} bean for the same reason
 * {@code TestBankSource} is a {@code @Component}: the whole integration suite shares ONE Spring
 * context, and a {@code @TestConfiguration} on any one test class would start a second.
 *
 * <p><strong>With nothing configured it delegates to the real client</strong>, so the egress
 * refusal a member actually sees — 400 {@code outbound-url-rejected} for a token that decodes to a
 * private address — is exercised through the production code path and not around it.
 */
@Component
@Primary
public class TestSimplefinBridge extends SimplefinBridgeClient {

    private volatile String accessUrl;
    private volatile List<SimplefinAccount> accounts = List.of();
    private volatile LocalDate lastRequestedFrom;
    private volatile int fetchCount;

    public TestSimplefinBridge(OutboundUrlPolicy egress,
                               HttpClientConfig.PinnedRestClientFactory clients,
                               SimplefinProperties properties, ObjectMapper objectMapper) {
        super(egress, clients, properties, objectMapper);
    }

    /** Forgets every previous test's setup, so the delegating behaviour is the default. */
    public void reset() {
        accessUrl = null;
        accounts = List.of();
        lastRequestedFrom = null;
        fetchCount = 0;
    }

    /** What the next exchange returns, and which accounts the access URL then reaches. */
    public void returning(String stubbedAccessUrl, List<SimplefinAccount> stubbedAccounts) {
        this.accessUrl = stubbedAccessUrl;
        this.accounts = List.copyOf(stubbedAccounts);
    }

    @Override
    public String claimAccessUrl(String claimUrl) {
        if (accessUrl == null) {
            return super.claimAccessUrl(claimUrl);
        }
        return accessUrl;
    }

    @Override
    public SimplefinAccountSet fetchAccounts(String url, LocalDate from) {
        if (accessUrl == null) {
            return super.fetchAccounts(url, from);
        }
        fetchCount++;
        lastRequestedFrom = from;
        return new SimplefinAccountSet(accounts, null);
    }

    /** The window the exchange asked the bridge for. */
    public LocalDate lastRequestedFrom() {
        return lastRequestedFrom;
    }

    /** How many times the bridge has been read since {@link #reset()}. */
    public int fetchCount() {
        return fetchCount;
    }
}
