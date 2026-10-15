package finance.trove.common.plugin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The contract every transaction source implements — the SPI collapse step 7 fills in
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.12, §6 M0).
 *
 * <p>It replaces {@code BankSourcePlugin}, and the differences are the point:
 * <ul>
 *   <li><strong>Keyed on a {@link SourceKind}, not a free-text id.</strong> The old
 *       {@code getId()} string was matched against {@code connected_account.source_plugin},
 *       another free-text column; a mismatch between the two produced an account that could
 *       never be synced and an error nobody could act on.</li>
 *   <li><strong>No {@code getDisplayName}/{@code getDescription}.</strong> Presentation strings
 *       for a fixed, closed set of four kinds belong in the client, not in a server interface
 *       that would have to be re-deployed to fix a typo.</li>
 *   <li><strong>No {@code testConnection}.</strong> It existed so a caller could check
 *       credentials before a full sync; the sync itself already reports a connection failure as
 *       a first-class outcome, and a second code path that talks to the bank is a second place
 *       for the egress policy to be forgotten.</li>
 * </ul>
 *
 * <p><strong>Nothing on the main classpath implements this in collapse step 6.</strong> The
 * SimpleFIN, OFX and CSV sources are step 7's; a test-only in-memory source under
 * {@code core/src/test} is what exercises {@code SyncOrchestrator} until then. A
 * {@code POST /v1/accounts/{id}/sync} for a kind with no registered source is 409
 * {@code conflict}, not a 500.
 *
 * <p>Implementations are Spring beans; {@code SourceRegistry} injects them as a
 * {@code List<BankSource>} and indexes them by {@link #sourceKind()}.
 */
public interface BankSource {

    /** Which kind of account this source serves. One implementation per kind. */
    SourceKind sourceKind();

    /**
     * Fetches every transaction the source has for one account over a date window.
     *
     * <p>The window is inclusive at both ends because that is what every bank API and file format
     * means by "from" and "to". It is a window and not a cursor deliberately: sources re-report
     * rows they have already given us (a pending transaction that posts is the same transaction
     * twice), and deduplication — not the window — is what stops a duplicate.
     *
     * <p>Called <strong>outside any transaction</strong>. An implementation may block on the
     * network for as long as its own timeout allows without holding a database connection.
     *
     * @param externalAccountId the provider's own id for the account, or {@code null} for a
     *                          source whose configuration already names it
     * @param from              inclusive start of the window
     * @param to                inclusive end of the window
     * @param config            the account's decrypted configuration; empty for a source that
     *                          needs none. Never logged, never returned
     * @return the rows the source reported; never {@code null}, possibly empty
     * @throws BankSourceConnectionException if the source could not be reached or refused us
     */
    List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                               Map<String, String> config);
}
