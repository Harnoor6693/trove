package finance.trove.ingestion.simplefin;

import finance.trove.accounts.AccountService;
import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.time.UserClock;
import finance.trove.ingestion.SyncLog;
import finance.trove.ingestion.SyncOrchestrator;
import finance.trove.ingestion.dto.SyncLogResponse;
import finance.trove.ingestion.simplefin.dto.ExchangeSetupTokenRequest;
import finance.trove.ingestion.simplefin.dto.ExchangeSetupTokenResponse;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a one-time SimpleFIN setup token into connected accounts
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3; {@code docs/recovery/01-FINDINGS.md} G2).
 *
 * <h2>What the pre-collapse service did, and what changes</h2>
 * <ul>
 *   <li>It validated the decoded URL with {@code decodedUrl.startsWith("http")} and then
 *       <strong>stored it encrypted</strong> — a persistent SSRF primitive, because every later
 *       sync re-used the stored URL with no validation at all (finding G2). Both halves are now
 *       {@link SimplefinBridgeClient}'s, which runs the egress policy on the claim URL AND on the
 *       stored access URL, every time, and pins the connection to the address that passed.</li>
 *   <li>It created a NEW {@code connected_account} per bridge account on every exchange. A member
 *       whose token rotated therefore got a second copy of every account and, on its first sync, a
 *       second copy of every transaction on it — past anything the per-account dedup key can
 *       catch. The upsert on {@code (household, SIMPLEFIN, bridge account id)} is the fix (§6 M0,
 *       blocker).</li>
 *   <li>It fell back to the literal {@code "CAD"} when the bridge declared no currency, which is
 *       finding F4's shape. The fallback is the creating member's own {@code users.currency}.</li>
 * </ul>
 *
 * <h2>The two secrets</h2>
 * The setup token and the access URL it is exchanged for are both credentials — the access URL
 * carries HTTP Basic credentials in its userinfo. Neither is logged, returned, or put in an
 * exception message here or anywhere below. {@link ExchangeSetupTokenRequest} redacts its own
 * {@code toString}, this class logs counts and account ids only, and
 * {@link SimplefinBridgeClient} strips a URL, and the credential taken off it, out of any message
 * it passes on.
 *
 * <p><strong>Not {@code @Transactional}, deliberately.</strong> It makes two network calls and then
 * runs a full sync per account; holding a database connection across any of that is what
 * {@code SyncOrchestrator}'s own javadoc rules out. Each account's upsert is its own short
 * transaction inside {@link AccountService}.
 */
@Service
public class ExchangeSetupTokenService {

    /** How far back the first sync of a freshly connected account reaches. */
    static final int INITIAL_SYNC_DAYS = SyncOrchestrator.FIRST_SYNC_LOOKBACK_DAYS;

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeSetupTokenService.class);

    private final SimplefinBridgeClient bridge;
    private final AccountService accounts;
    private final SyncOrchestrator syncOrchestrator;
    private final FieldEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final UserClock clock;

    public ExchangeSetupTokenService(SimplefinBridgeClient bridge, AccountService accounts,
                                     SyncOrchestrator syncOrchestrator,
                                     FieldEncryptionService encryption, ObjectMapper objectMapper,
                                     UserClock clock) {
        this.bridge = bridge;
        this.accounts = accounts;
        this.syncOrchestrator = syncOrchestrator;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Exchanges {@code request}'s token and connects everything behind it.
     *
     * @throws ValidationException         if the kind is not SimpleFIN, or the token is not base64
     * @throws finance.trove.common.net.OutboundUrlPolicyException if the decoded URL is refused —
     *                                     400 {@code outbound-url-rejected}, naming the rule that
     *                                     refused it and never the address or the URL
     * @throws ConflictException           if the token has already been spent, or the bridge shows
     *                                     no accounts to connect
     */
    public ExchangeSetupTokenResponse exchange(UUID householdId, UUID userId,
                                               ExchangeSetupTokenRequest request) {
        if (request.sourceKind() != SourceKind.SIMPLEFIN) {
            throw new ValidationException("Only SIMPLEFIN accounts are connected with a setup "
                + "token. OFX and CSV accounts are created by uploading a file.");
        }
        String claimUrl = decode(request.setupToken());
        String accessUrl = bridge.claimAccessUrl(claimUrl);
        String configEncrypted = encryptedConfig(accessUrl);

        SimplefinAccountSet set = bridge.fetchAccounts(accessUrl,
            clock.today(userId).minusDays(INITIAL_SYNC_DAYS));
        List<SimplefinAccount> bridgeAccounts = set.accountsOrEmpty();
        if (bridgeAccounts.isEmpty()) {
            throw new ConflictException("That SimpleFIN connection shows no accounts. Check the "
                + "bridge and try again.");
        }

        List<UUID> accountIds = new ArrayList<>(bridgeAccounts.size());
        for (SimplefinAccount bridgeAccount : bridgeAccounts) {
            if (bridgeAccount.id() == null || bridgeAccount.id().isBlank()) {
                LOG.warn("A SimpleFIN bridge returned an account with no id; it cannot be "
                    + "connected and is skipped.");
                continue;
            }
            ConnectedAccount account = accounts.upsertProviderAccount(householdId, userId,
                SourceKind.SIMPLEFIN, bridgeAccount.id(), displayName(bridgeAccount),
                bridgeAccount.org() == null ? null : bridgeAccount.org().name(), AccountType.OTHER,
                currencyOf(bridgeAccount), configEncrypted);
            accountIds.add(account.getId());
        }
        if (accountIds.isEmpty()) {
            throw new ConflictException("That SimpleFIN connection shows no account this server "
                + "can identify. Check the bridge and try again.");
        }

        List<SyncLogResponse> logs = new ArrayList<>(accountIds.size());
        for (UUID accountId : accountIds) {
            SyncLog log = syncOrchestrator.sync(householdId, userId, accountId);
            logs.add(SyncLogResponse.of(log));
        }
        LOG.info("Connected {} SimpleFIN account(s) for household {}", accountIds.size(),
            householdId);
        return new ExchangeSetupTokenResponse(accountIds, logs);
    }

    /**
     * Base64-decodes the token into its one-time claim URL.
     *
     * <p>Both alphabets are tried: bridges differ, and a token that is URL-safe base64 is not a
     * malformed token. <strong>Neither the token nor the decoded URL appears in the message</strong>
     * — the token is a credential and the URL is the credential it becomes.
     */
    private static String decode(String setupToken) {
        String token = setupToken.trim();
        byte[] decoded = decodeWith(Base64.getDecoder(), token);
        if (decoded == null) {
            decoded = decodeWith(Base64.getUrlDecoder(), token);
        }
        if (decoded == null) {
            throw new ValidationException("That setup token is not valid base64. Copy it again "
                + "from your bridge.");
        }
        String url = new String(decoded, StandardCharsets.UTF_8).trim();
        if (!url.toLowerCase(Locale.ROOT).startsWith("http")) {
            throw new ValidationException("That setup token does not decode to a web address. "
                + "Copy it again from your bridge.");
        }
        return url;
    }

    private static byte[] decodeWith(Base64.Decoder decoder, String token) {
        try {
            return decoder.decode(token);
        } catch (IllegalArgumentException e) {
            // swallow-ok: "these characters are not this alphabet" is the question being asked,
            // and the caller's next move is the other alphabet. The exception's message quotes the
            // offending character of a credential, so it is never logged or rethrown.
            return null;
        }
    }

    /**
     * The access URL, encrypted into the shape {@code SyncOrchestrator} decrypts it from.
     *
     * <p>Encrypted here and immediately — it is a permanent credential with the bridge's secret
     * embedded in it, and {@code config_encrypted} is the one column allowed to hold it.
     */
    private String encryptedConfig(String accessUrl) {
        return encryption.encrypt(objectMapper.writeValueAsString(
            Map.of(SimplefinSource.CONFIG_ACCESS_URL, accessUrl)));
    }

    private static String displayName(SimplefinAccount account) {
        if (account.name() != null && !account.name().isBlank()) {
            return account.name().trim();
        }
        return "SimpleFIN account";
    }

    /** The bridge's currency if this install supports it, otherwise the member's own (F4). */
    private static CurrencyCode currencyOf(SimplefinAccount account) {
        if (account.currency() == null || account.currency().isBlank()) {
            return null;
        }
        try {
            return CurrencyCode.valueOf(account.currency().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // swallow-ok: a currency this install does not support is not a reason to refuse the
            // connection. The account takes the member's own, and each row is judged on its code.
            return null;
        }
    }
}
