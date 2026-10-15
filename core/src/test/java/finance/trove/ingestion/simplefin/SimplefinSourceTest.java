package finance.trove.ingestion.simplefin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import finance.trove.ingestion.simplefin.dto.SimplefinTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SimplefinSource} against a stubbed bridge — the account filter and the window filter,
 * which finding M10.4 says were never exercised: every pre-collapse
 * {@code fetchTransactions} test passed {@code accountId = null}, the one input that decides which
 * bridge account's rows reach which Trove account.
 */
class SimplefinSourceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private final StubBridge bridge = new StubBridge();
    private final SimplefinSource source = new SimplefinSource(bridge, new SimplefinNormalizer());

    @Test
    @DisplayName("only the named bridge account's rows are returned")
    void filtersToTheNamedAccount() {
        bridge.answer = new SimplefinAccountSet(List.of(
            account("mine", transaction("a", "2026-09-05")),
            account("theirs", transaction("b", "2026-09-06"))), null);

        List<RawTransaction> rows = source.fetch("mine", FROM, TO, config("https://bridge/x"));

        assertThat(rows).extracting(RawTransaction::providerTransactionId).containsExactly("a");
    }

    @Test
    @DisplayName("rows outside the window are dropped; a dateless row is kept to be counted")
    void filtersTheWindowButKeepsADatelessRow() {
        bridge.answer = new SimplefinAccountSet(List.of(account("mine",
            transaction("before", "2026-08-30"),
            transaction("inside", "2026-09-05"),
            transaction("after", "2026-10-02"),
            new SimplefinTransaction("dateless", BigDecimal.ONE, "X", null, null))), null);

        List<RawTransaction> rows = source.fetch("mine", FROM, TO, config("https://bridge/x"));

        assertThat(rows).extracting(RawTransaction::providerTransactionId)
            .as("a dateless row is refused downstream WITH a reason rather than vanishing from "
                + "transactions_fetched")
            .containsExactly("inside", "dateless");
    }

    @Test
    @DisplayName("the window is what is handed to the bridge, not applied only afterwards")
    void theWindowReachesTheBridge() {
        bridge.answer = new SimplefinAccountSet(List.of(), null);

        source.fetch("mine", FROM, TO, config("https://bridge/x"));

        assertThat(bridge.requestedFrom).isEqualTo(FROM);
    }

    @Test
    @DisplayName("an account with no stored connection, or no bridge id, is a failure with a reason")
    void missingConfigurationIsAFailureWithAReason() {
        assertThatThrownBy(() -> source.fetch("mine", FROM, TO, Map.of()))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("no SimpleFIN connection");
        assertThatThrownBy(() -> source.fetch(null, FROM, TO, config("https://bridge/x")))
            .as("M10.4: every pre-collapse test passed a null account id, so this path was never "
                + "reached at all")
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("not linked to a SimpleFIN account");
    }

    @Test
    @DisplayName("a bridge account with no transactions at all is not a failure")
    void anAccountWithNoTransactionsIsEmpty() {
        bridge.answer = new SimplefinAccountSet(
            List.of(new SimplefinAccount("mine", "Chequing", "CAD", null, null)), null);

        assertThat(source.fetch("mine", FROM, TO, config("https://bridge/x"))).isEmpty();
    }

    @Test
    @DisplayName("the source serves SIMPLEFIN accounts")
    void servesSimplefin() {
        assertThat(source.sourceKind()).isEqualTo(SourceKind.SIMPLEFIN);
    }

    private static Map<String, String> config(String accessUrl) {
        return Map.of(SimplefinSource.CONFIG_ACCESS_URL, accessUrl);
    }

    private static SimplefinAccount account(String id, SimplefinTransaction... transactions) {
        return new SimplefinAccount(id, "Chequing", "CAD", null, List.of(transactions));
    }

    private static SimplefinTransaction transaction(String id, String date) {
        Instant at = LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        return new SimplefinTransaction(id, new BigDecimal("-1.00"), "A PURCHASE", at, at);
    }

    /** A bridge that answers from memory, so the source's own filtering is what is measured. */
    private static final class StubBridge extends SimplefinBridgeClient {

        private SimplefinAccountSet answer = new SimplefinAccountSet(List.of(), null);
        private LocalDate requestedFrom;

        StubBridge() {
            super(null, null, SimplefinProperties.defaults(), null);
        }

        @Override
        public SimplefinAccountSet fetchAccounts(String accessUrl, LocalDate from) {
            this.requestedFrom = from;
            return answer;
        }
    }
}
