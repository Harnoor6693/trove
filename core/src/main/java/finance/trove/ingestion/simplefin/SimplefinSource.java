package finance.trove.ingestion.simplefin;

import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import finance.trove.ingestion.simplefin.dto.SimplefinTransaction;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fetches transactions from a SimpleFIN bridge ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6
 * "SimpleFIN (optional)"; {@code docs/recovery/inventory/plugins.md} §3).
 *
 * <p>It replaces {@code SimplefinPlugin}, and three of that class's behaviours do not survive:
 * <ul>
 *   <li><strong>The stored access URL is re-validated on every call.</strong> The pre-collapse
 *       plugin took {@code credentials.get("accessUrl")} and handed it straight to a bare
 *       {@code RestClient.builder()}. A URL that was written into the database once is still a URL
 *       an attacker influenced, and the name behind it can start resolving somewhere else, so
 *       {@link SimplefinBridgeClient} puts it through the egress policy and pins the connection
 *       every time (finding G2, gap P2-10).</li>
 *   <li><strong>The window is sent to the bridge.</strong> The plugin fetched
 *       {@code {accessUrl}/accounts} with no date parameter and filtered the institution's whole
 *       history in memory (plugins.md §3).</li>
 *   <li><strong>{@code externalAccountId} is used.</strong> The plugin's own tests passed
 *       {@code accountId = null} on every {@code fetchTransactions} call — finding M10.4 — so the
 *       one input that decides which bridge account's rows reach which Trove account was never
 *       exercised. Here a null id means "this connection has not been resolved to a bridge
 *       account", which is a failure rather than "give me everything".</li>
 * </ul>
 */
@Component
public class SimplefinSource implements BankSource {

    /** The key the account's encrypted configuration holds its access URL under. */
    public static final String CONFIG_ACCESS_URL = "accessUrl";

    private final SimplefinBridgeClient bridge;
    private final SimplefinNormalizer normalizer;

    public SimplefinSource(SimplefinBridgeClient bridge, SimplefinNormalizer normalizer) {
        this.bridge = bridge;
        this.normalizer = normalizer;
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.SIMPLEFIN;
    }

    @Override
    public List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                                      Map<String, String> config) {
        String accessUrl = config.get(CONFIG_ACCESS_URL);
        if (accessUrl == null || accessUrl.isBlank()) {
            throw new BankSourceConnectionException("This account has no SimpleFIN connection. "
                + "Exchange a setup token to connect it.");
        }
        if (externalAccountId == null || externalAccountId.isBlank()) {
            throw new BankSourceConnectionException("This account is not linked to a SimpleFIN "
                + "account. Exchange a setup token again to relink it.");
        }

        ZoneId zone = zoneOf(config.get(MEMBER_ZONE));
        SimplefinAccountSet set = bridge.fetchAccounts(accessUrl, from);
        List<RawTransaction> rows = new ArrayList<>();
        for (SimplefinAccount account : set.accountsOrEmpty()) {
            if (!externalAccountId.equals(account.id()) || account.transactions() == null) {
                continue;
            }
            for (SimplefinTransaction transaction : account.transactions()) {
                RawTransaction row = normalizer.normalize(account, transaction, zone);
                if (row.date() == null) {
                    // Kept rather than dropped: the pipeline refuses it with "the source reported
                    // no date", which is counted and summarised into the run's log. Dropping it
                    // here would make transactions_fetched a count of the rows that happened to be
                    // well formed, and a member would never learn the bridge sent a broken one.
                    rows.add(row);
                } else if (!row.date().isBefore(from) && !row.date().isAfter(to)) {
                    rows.add(row);
                }
            }
        }
        return List.copyOf(rows);
    }

    /** The member's zone from {@code config}, or UTC when the caller sent none (B1-22). */
    private static ZoneId zoneOf(String id) {
        if (id == null) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            // swallow-ok: UserClock already resolved this id; UTC is what dating was before.
            return ZoneOffset.UTC;
        }
    }
}
