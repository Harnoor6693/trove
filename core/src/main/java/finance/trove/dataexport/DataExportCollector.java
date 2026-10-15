package finance.trove.dataexport;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.dto.CoachMessageResponse;
import finance.trove.budgets.envelope.Envelope;
import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.goal.SavingsGoal;
import finance.trove.budgets.goal.SavingsGoalRepository;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.categorisation.MerchantCategoryMap;
import finance.trove.categorisation.MerchantCategoryMapRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.PagedResult;
import finance.trove.networth.Asset;
import finance.trove.networth.AssetRepository;
import finance.trove.networth.Liability;
import finance.trove.networth.LiabilityRepository;
import finance.trove.networth.NetWorthSnapshot;
import finance.trove.networth.NetWorthSnapshotRepository;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceRepository;
import finance.trove.receipts.receipt.LineItem;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.subscriptions.subscription.DetectedSubscription;
import finance.trove.subscriptions.subscription.SubscriptionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads everything that goes into one archive and turns it into plain, serialisable maps
 * (collapse step 18; gap row 16).
 *
 * <h2>Maps, not entities</h2>
 * Nothing here hands a JPA entity to Jackson. Serialising an entity exports whatever fields it
 * happens to have, which is how {@code ConnectedAccount.configEncrypted} — a member's encrypted
 * bank credentials — and {@code CoachMessage.contentEncrypted} would end up in a file the member
 * emails to an accountant. Every field in this class is named on purpose, and a field added to an
 * entity later does not silently join the export.
 *
 * <h2>What is exported, and what is deliberately not</h2>
 * <ul>
 *   <li><strong>Bank connections</strong> without {@code configEncrypted}. The archive says which
 *       institutions the household connected, never how to connect to them.</li>
 *   <li><strong>Receipts as METADATA ONLY</strong> — ids, status, source, merchant, amounts, the
 *       tax split, the matched transaction, the uploader, timestamps, and a
 *       <em>server-generated display name</em> per image. Never image bytes: an archive that
 *       inlined every photograph a household ever took is a memory and a disk problem the bounded
 *       export executor cannot carry. Never a storage key and never a URL either, and the display
 *       name is built from the receipt's own id rather than derived from the key, so it cannot be
 *       turned back into a path. The images themselves stay with the household and are downloaded
 *       from the receipts API.</li>
 *   <li><strong>Coach conversations</strong> through {@link CoachConversationService#page}, which
 *       is the one place a stored turn is decrypted and only ever for the member who owns it. This
 *       class holds no encryption key.</li>
 *   <li><strong>No computed figure of any kind.</strong> Every amount is the member's own stored
 *       value at the ledger's four-decimal scale, as a decimal STRING, carrying its own currency
 *       on its own row. No conversion, no rate, no blended total (owner decision 2, §9 risk 15) —
 *       a household holding two currencies gets two rows, never one wrong number.</li>
 * </ul>
 *
 * <h2>One read-only transaction</h2>
 * {@code Receipt} owns its images and line items as EAGER collections, and the coach page decrypts
 * inside its own read. Collecting inside one read-only transaction is what makes the whole archive
 * a consistent picture of one moment rather than thirteen pictures of thirteen different ones.
 */
@Component
public class DataExportCollector {

    /** The ledger's own scale, from {@code NUMERIC(19,4)}. */
    static final int LEDGER_SCALE = 4;

    /**
     * How many coach turns one archive carries.
     *
     * <p>Bounded because the coach history is the one dataset with no natural ceiling — a member
     * can ask questions for years — and this runs on a two-thread executor with the whole
     * archive held in memory. Five thousand turns is far past any real conversation history and
     * far short of a heap problem. A member with more than this gets their most recent turns and
     * the manifest's entry count says how many.
     */
    static final int MAX_COACH_MESSAGES = 5000;

    private final ConnectedAccountRepository accounts;
    private final CategoryRepository categories;
    private final MerchantCategoryMapRepository merchantRules;
    private final BudgetPeriodRepository budgetPeriods;
    private final EnvelopeRepository envelopes;
    private final SavingsGoalRepository goals;
    private final SubscriptionRepository subscriptions;
    private final NetWorthSnapshotRepository snapshots;
    private final AssetRepository assets;
    private final LiabilityRepository liabilities;
    private final ReceiptRepository receipts;
    private final CoachConversationService coach;
    private final NotificationPreferenceRepository notificationPreferences;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DataExportCollector(ConnectedAccountRepository accounts, CategoryRepository categories,
                               MerchantCategoryMapRepository merchantRules,
                               BudgetPeriodRepository budgetPeriods, EnvelopeRepository envelopes,
                               SavingsGoalRepository goals, SubscriptionRepository subscriptions,
                               NetWorthSnapshotRepository snapshots, AssetRepository assets,
                               LiabilityRepository liabilities, ReceiptRepository receipts,
                               CoachConversationService coach,
                               NotificationPreferenceRepository notificationPreferences) {
        this.accounts = accounts;
        this.categories = categories;
        this.merchantRules = merchantRules;
        this.budgetPeriods = budgetPeriods;
        this.envelopes = envelopes;
        this.goals = goals;
        this.subscriptions = subscriptions;
        this.snapshots = snapshots;
        this.assets = assets;
        this.liabilities = liabilities;
        this.receipts = receipts;
        this.coach = coach;
        this.notificationPreferences = notificationPreferences;
    }

    /**
     * Every JSON dataset of one archive, keyed by the entry name it is written under.
     *
     * <p>A {@code LinkedHashMap}, so the archive's entry order is stable across runs and two
     * archives of the same account differ only where the data differs.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> collect(UUID userId, UUID householdId) {
        Map<String, Object> datasets = new LinkedHashMap<>();
        datasets.put("accounts.json", map(accounts.findAllByHouseholdId(householdId),
            DataExportCollector::account));
        datasets.put("categories.json", map(categories.findAllVisible(householdId),
            DataExportCollector::category));
        datasets.put("merchant-rules.json", map(merchantRules.findAllByHouseholdId(householdId),
            DataExportCollector::merchantRule));
        datasets.put("budget-periods.json", map(budgetPeriods.findAllByHouseholdId(householdId),
            DataExportCollector::budgetPeriod));
        datasets.put("envelopes.json", map(envelopes.findAllByHouseholdId(householdId),
            DataExportCollector::envelope));
        datasets.put("savings-goals.json", map(goals.findAllByHouseholdId(householdId),
            DataExportCollector::savingsGoal));
        datasets.put("subscriptions.json", map(subscriptions.findAllByHouseholdId(householdId),
            DataExportCollector::subscription));
        datasets.put("net-worth-snapshots.json", map(snapshots.findAllByHouseholdId(householdId),
            DataExportCollector::snapshot));
        datasets.put("assets.json", map(assets.findAllByHouseholdId(householdId),
            DataExportCollector::asset));
        datasets.put("liabilities.json", map(liabilities.findAllByHouseholdId(householdId),
            DataExportCollector::liability));
        datasets.put("receipts.json", map(receipts.findAllByHouseholdId(householdId),
            DataExportCollector::receipt));
        datasets.put("coach-conversations.json", coachConversations(userId));
        datasets.put("notification-preferences.json", notificationPreferences(userId));
        return datasets;
    }

    private static <T> List<Map<String, Object>> map(List<T> rows,
                                                     Function<T, Map<String, Object>> shape) {
        List<Map<String, Object>> mapped = new ArrayList<>(rows.size());
        for (T row : rows) {
            mapped.add(shape.apply(row));
        }
        return mapped;
    }

    /** A bank connection, without the encrypted configuration that would let anyone use it. */
    static Map<String, Object> account(ConnectedAccount row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("addedBy", id(row.getUserId()));
        out.put("sourceKind", name(row.getSourceKind()));
        out.put("externalAccountId", row.getExternalAccountId());
        out.put("displayName", row.getDisplayName());
        out.put("institution", row.getInstitution());
        out.put("accountType", name(row.getAccountType()));
        out.put("currency", name(row.getCurrency()));
        out.put("lastSyncedAt", text(row.getLastSyncedAt()));
        out.put("syncStatus", name(row.getSyncStatus()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    static Map<String, Object> category(Category row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("slug", row.getSlug());
        out.put("name", row.getName());
        out.put("icon", row.getIcon());
        out.put("parentId", id(row.getParentId()));
        out.put("system", row.isSystem());
        out.put("createdBy", id(row.getCreatedBy()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> merchantRule(MerchantCategoryMap row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("normalisedMerchant", row.getNormalisedMerchant());
        out.put("matchType", name(row.getMatchType()));
        out.put("categoryId", id(row.getCategoryId()));
        out.put("confidence", decimal(row.getConfidence()));
        out.put("source", name(row.getSource()));
        out.put("priority", row.getPriority());
        out.put("createdBy", id(row.getCreatedBy()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> budgetPeriod(BudgetPeriod row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("createdBy", id(row.getUserId()));
        out.put("month", text(row.getMonth()));
        out.put("totalIncome", money(row.getTotalIncome(), row.getCurrency()));
        out.put("budgetType", name(row.getBudgetType()));
        out.put("rolledOverAt", text(row.getRolledOverAt()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> envelope(Envelope row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("periodId", id(row.getPeriodId()));
        out.put("categoryId", id(row.getCategoryId()));
        out.put("allocated", money(row.getAllocated(), row.getCurrency()));
        out.put("spent", money(row.getSpent(), row.getCurrency()));
        out.put("rolloverAmount", money(row.getRolloverAmount(), row.getCurrency()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> savingsGoal(SavingsGoal row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("createdBy", id(row.getUserId()));
        out.put("name", row.getName());
        out.put("targetAmount", money(row.getTargetAmount(), row.getCurrency()));
        out.put("currentAmount", money(row.getCurrentAmount(), row.getCurrency()));
        out.put("deadline", text(row.getDeadline()));
        out.put("achieved", row.isAchieved());
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> subscription(DetectedSubscription row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("attributedTo", id(row.getUserId()));
        out.put("merchantName", row.getMerchantName());
        out.put("categoryId", id(row.getCategoryId()));
        out.put("normalisedAmount", money(row.getNormalizedAmount(), row.getCurrency()));
        out.put("previousAmount", money(row.getPreviousAmount(), row.getCurrency()));
        out.put("billingCycle", name(row.getBillingCycle()));
        out.put("nextChargeDate", text(row.getNextChargeDate()));
        out.put("signal", name(row.getSignal()));
        out.put("priceChanged", row.isPriceChanged());
        out.put("firstSeenDate", text(row.getFirstSeenDate()));
        out.put("lastSeenDate", text(row.getLastSeenDate()));
        out.put("cancelled", row.isCancelled());
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> snapshot(NetWorthSnapshot row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("takenBy", id(row.getUserId()));
        out.put("snapshotDate", text(row.getSnapshotDate()));
        out.put("totalAssets", money(row.getTotalAssets(), row.getCurrency()));
        out.put("totalLiabilities", money(row.getTotalLiabilities(), row.getCurrency()));
        out.put("netWorth", money(row.getNetWorth(), row.getCurrency()));
        out.put("createdAt", text(row.getCreatedAt()));
        return out;
    }

    private static Map<String, Object> asset(Asset row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("createdBy", id(row.getUserId()));
        out.put("name", row.getName());
        out.put("assetType", name(row.getAssetType()));
        out.put("value", money(row.getValue(), row.getCurrency()));
        out.put("accountId", id(row.getAccountId()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    private static Map<String, Object> liability(Liability row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("createdBy", id(row.getUserId()));
        out.put("name", row.getName());
        out.put("liabilityType", name(row.getLiabilityType()));
        out.put("balance", money(row.getBalance(), row.getCurrency()));
        // A rate is a percentage, not money: it carries no currency and is written as it is
        // stored. Pairing it with one would invite a reader to add it to something.
        out.put("interestRatePercent", decimal(row.getInterestRate()));
        out.put("accountId", id(row.getAccountId()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    /**
     * One receipt, as metadata.
     *
     * <p>{@code uploadedBy} is null for a receipt whose uploader has left the household: V43 makes
     * {@code receipt.user_id} nullable with ON DELETE SET NULL, because the receipt is a shared
     * household ledger row and the shared ledger wins (owner decision 17). The archive says so
     * rather than pretending the row has no history.
     *
     * <p>No {@code ocrText}: it is the raw text of an image, it can run to kilobytes per receipt,
     * and it is a transcription rather than a fact the member entered.
     */
    static Map<String, Object> receipt(Receipt row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(row.getId()));
        out.put("uploadedBy", id(row.getUserId()));
        out.put("status", name(row.getStatus()));
        out.put("source", name(row.getSource()));
        out.put("merchant", row.getExtractedMerchant());
        out.put("amount", money(row.getExtractedAmount(), row.getExtractedCurrency()));
        out.put("date", text(row.getExtractedDate()));
        Map<String, Object> tax = new LinkedHashMap<>();
        tax.put("gst", money(row.getGstAmount(), row.getExtractedCurrency()));
        tax.put("pst", money(row.getPstAmount(), row.getExtractedCurrency()));
        tax.put("hst", money(row.getHstAmount(), row.getExtractedCurrency()));
        out.put("taxSplit", tax);
        out.put("confidence", decimal(row.getConfidence()));
        out.put("matchedTransactionId", id(row.getMatchedTransactionId()));
        out.put("reviewRequired", row.isReviewRequired());
        out.put("uploadSizeBytes", row.getUploadSizeBytes());
        out.put("processingStartedAt", text(row.getProcessingStartedAt()));
        out.put("processingCompletedAt", text(row.getProcessingCompletedAt()));
        out.put("createdAt", text(row.getCreatedAt()));
        out.put("updatedAt", text(row.getUpdatedAt()));
        List<Map<String, Object>> images = new ArrayList<>();
        for (ReceiptImage image : row.getImages()) {
            images.add(image(row.getId(), image));
        }
        out.put("images", images);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (LineItem item : row.getLineItems()) {
            lines.add(lineItem(item, row.getExtractedCurrency()));
        }
        out.put("lineItems", lines);
        return out;
    }

    /**
     * One stored image, named but not addressed.
     *
     * <p>{@code fileName} is BUILT HERE from the receipt's own id, the image kind and the page
     * number — it is not the stored key and not derived from it. The key's shape is
     * {@code receipts/<householdId>/<uuid>.<ext>} and the archive already carries the household
     * id, so a name derived from the key would hand out a resolvable storage path in everything
     * but name. This one is a label a member can match against a download; it addresses nothing.
     */
    static Map<String, Object> image(UUID receiptId, ReceiptImage image) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(image.getId()));
        out.put("kind", name(image.getKind()));
        out.put("pageNo", image.getPageNo());
        out.put("mimeType", image.getMimeType());
        out.put("sizeBytes", image.getSizeBytes());
        out.put("fileName", "receipt-" + receiptId + "-"
            + image.getKind().name().toLowerCase(java.util.Locale.ROOT) + "-p"
            + image.getPageNo());
        out.put("createdAt", text(image.getCreatedAt()));
        return out;
    }

    private static Map<String, Object> lineItem(LineItem item, CurrencyCode currency) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(item.getId()));
        out.put("description", item.getDescription());
        out.put("quantity", decimal(item.getQuantity()));
        out.put("unitPrice", money(item.getUnitPrice(), currency));
        out.put("totalPrice", money(item.getTotalPrice(), currency));
        out.put("taxable", item.isTaxable());
        out.put("createdAt", text(item.getCreatedAt()));
        return out;
    }

    /**
     * The member's coach history, grouped by conversation, newest turn first within the page.
     *
     * <p>Read through {@link CoachConversationService#page}, which owns the decryption and the
     * ownership check. This class never touches a ciphertext and holds no key.
     */
    private List<Map<String, Object>> coachConversations(UUID userId) {
        PagedResult<CoachMessageResponse> page = coach.page(userId, null, 0, MAX_COACH_MESSAGES);
        Map<UUID, List<Map<String, Object>>> byConversation = new LinkedHashMap<>();
        for (CoachMessageResponse message : page.content()) {
            byConversation
                .computeIfAbsent(message.sessionId(), key -> new ArrayList<>())
                .add(coachMessage(message));
        }
        List<Map<String, Object>> conversations = new ArrayList<>(byConversation.size());
        for (Map.Entry<UUID, List<Map<String, Object>>> entry : byConversation.entrySet()) {
            Map<String, Object> conversation = new LinkedHashMap<>();
            conversation.put("id", id(entry.getKey()));
            conversation.put("messages", entry.getValue());
            conversations.add(conversation);
        }
        return conversations;
    }

    private static Map<String, Object> coachMessage(CoachMessageResponse message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id(message.id()));
        out.put("role", name(message.role()));
        out.put("content", message.content());
        out.put("model", message.model());
        out.put("createdAt", text(message.createdAt()));
        return out;
    }

    /** The member's own notification settings; an empty map when they never changed the defaults. */
    private Map<String, Object> notificationPreferences(UUID userId) {
        return notificationPreferences.findByUserId(userId)
            .map(DataExportCollector::preference)
            .orElseGet(LinkedHashMap::new);
    }

    static Map<String, Object> preference(NotificationPreference row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inAppEnabled", row.isInAppEnabled());
        out.put("emailEnabled", row.isEmailEnabled());
        out.put("emailAddress", row.getEmailAddress());
        out.put("weeklyDigestEnabled", row.isWeeklyDigestEnabled());
        out.put("subscriptionAlertEnabled", row.isSubscriptionAlertEnabled());
        out.put("syncFailureAlertEnabled", row.isSyncFailureAlertEnabled());
        out.put("budgetAlertThresholdPercent", (int) row.getBudgetAlertThresholdPercent());
        out.put("updatedAt", text(row.getUpdatedAt()));
        return out;
    }

    // ------------------------------------------------------------------ value shapes

    /**
     * An amount and the currency it is in, never one without the other.
     *
     * <p>A decimal STRING rather than a JSON number: a JSON number is an IEEE double in most
     * readers, and a ledger stored at {@code NUMERIC(19,4)} does not survive that round trip
     * intact. Null when there is no amount, so a reader can tell "nothing recorded" from "zero".
     */
    static Map<String, Object> money(BigDecimal amount, CurrencyCode currency) {
        if (amount == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", amount.setScale(LEDGER_SCALE, RoundingMode.HALF_UP).toPlainString());
        out.put("currency", currency == null ? null : currency.name());
        return out;
    }

    /** A plain decimal that is not money — a confidence, a quantity, a rate. */
    static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
