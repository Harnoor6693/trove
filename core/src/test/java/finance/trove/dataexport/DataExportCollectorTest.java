package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.CoachRole;
import finance.trove.ai.chat.dto.CoachMessageResponse;
import finance.trove.ai.insight.InsightService;
import finance.trove.ai.insight.dto.InsightResponse;
import finance.trove.budgets.envelope.Envelope;
import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.goal.SavingsGoal;
import finance.trove.budgets.goal.SavingsGoalRepository;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.budgets.period.BudgetType;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MatchType;
import finance.trove.categorisation.MerchantCategoryMap;
import finance.trove.categorisation.MerchantCategoryMapRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.plugin.SourceKind;
import finance.trove.networth.Asset;
import finance.trove.networth.AssetRepository;
import finance.trove.networth.AssetType;
import finance.trove.networth.Liability;
import finance.trove.networth.LiabilityRepository;
import finance.trove.networth.LiabilityType;
import finance.trove.networth.NetWorthSnapshot;
import finance.trove.networth.NetWorthSnapshotRepository;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.inapp.InAppNotification;
import finance.trove.notifications.inapp.InAppNotificationRepository;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceRepository;
import finance.trove.notifications.webhook.WebhookSubscription;
import finance.trove.notifications.webhook.WebhookSubscriptionRepository;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.subscriptions.alert.AlertType;
import finance.trove.subscriptions.alert.SubscriptionAlert;
import finance.trove.subscriptions.alert.SubscriptionAlertRepository;
import finance.trove.subscriptions.detection.BillingCycle;
import finance.trove.subscriptions.subscription.DetectedSubscription;
import finance.trove.subscriptions.subscription.SubscriptionRepository;
import finance.trove.subscriptions.subscription.SubscriptionSignal;
import finance.trove.taxexport.TaxLineMapping;
import finance.trove.taxexport.TaxLineMappingRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every dataset of the archive, with exactly one row in it.
 *
 * <p>{@code DataExportIT} drives the real thing end to end, but an integration test can only
 * exercise the datasets it has taken the trouble to populate, and the ones it leaves empty — a
 * household with no envelopes, no goals, no snapshots — are precisely the mappers that would go
 * unnoticed if they were wrong. This class gives every dataset a row and asserts what comes out.
 *
 * <p><strong>Rows are built through the JPA no-argument constructor and reflection</strong>, not
 * through the domain constructors. That is deliberate for a pure mapping function: the input this
 * class is about is "a row as the database hands it back", which is exactly what the JPA
 * constructor plus field population produces, and going through the domain constructors would
 * couple every one of these unrelated aggregates' construction rules to a test about JSON shapes.
 */
class DataExportCollectorTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final ConnectedAccountRepository accounts = mock(ConnectedAccountRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final MerchantCategoryMapRepository merchantRules =
        mock(MerchantCategoryMapRepository.class);
    private final BudgetPeriodRepository budgetPeriods = mock(BudgetPeriodRepository.class);
    private final EnvelopeRepository envelopes = mock(EnvelopeRepository.class);
    private final SavingsGoalRepository goals = mock(SavingsGoalRepository.class);
    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final NetWorthSnapshotRepository snapshots = mock(NetWorthSnapshotRepository.class);
    private final AssetRepository assets = mock(AssetRepository.class);
    private final LiabilityRepository liabilities = mock(LiabilityRepository.class);
    private final ReceiptRepository receipts = mock(ReceiptRepository.class);
    private final CoachConversationService coach = mock(CoachConversationService.class);
    private final NotificationPreferenceRepository preferences =
        mock(NotificationPreferenceRepository.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final TaxLineMappingRepository taxLines = mock(TaxLineMappingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final WebhookSubscriptionRepository webhooks =
        mock(WebhookSubscriptionRepository.class);
    private final InAppNotificationRepository inbox = mock(InAppNotificationRepository.class);
    private final InsightService insights = mock(InsightService.class);
    private final SubscriptionAlertRepository subscriptionAlerts =
        mock(SubscriptionAlertRepository.class);

    private final DataExportCollector collector = new DataExportCollector(accounts, categories,
        merchantRules, budgetPeriods, envelopes, goals, subscriptions, snapshots, assets,
        liabilities, receipts, coach, preferences, taxLines, users, webhooks, inbox, insights,
        subscriptionAlerts);

    /** The paged reads answer "nothing" unless a test says otherwise; a mock's null is no page. */
    @BeforeEach
    void emptyPagedReads() {
        when(inbox.findAllByUserId(eq(USER), any(Pageable.class))).thenReturn(Page.empty());
        when(insights.page(eq(USER), anyInt(), anyInt()))
            .thenReturn(new PagedResult<>(List.of(), 0L, 0, DataExportCollector.COACH_PAGE_SIZE));
        when(coach.page(eq(USER), any(), anyInt(), anyInt()))
            .thenReturn(new PagedResult<>(List.of(), 0L, 0, DataExportCollector.COACH_PAGE_SIZE));
    }

    /** A row in the state the database hands it back: no-argument constructor, fields set. */
    private static <T> T row(Class<T> type, Object... fields) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            T instance = constructor.newInstance();
            for (int i = 0; i < fields.length; i += 2) {
                ReflectionTestUtils.setField(instance, (String) fields[i], fields[i + 1]);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not build a " + type.getSimpleName(), e);
        }
    }

    private JsonNode datasets() {
        when(accounts.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(ConnectedAccount.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "sourceKind", SourceKind.MANUAL, "displayName", "Chequing",
                "institution", "A Bank", "accountType", AccountType.CHECKING,
                "currency", CurrencyCode.CAD, "createdAt", NOW, "updatedAt", NOW)));
        when(categories.findAllVisible(HOUSEHOLD)).thenReturn(List.of(
            row(Category.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "slug", "groceries", "name", "Groceries", "createdAt", NOW, "updatedAt", NOW)));
        when(merchantRules.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(MerchantCategoryMap.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "normalisedMerchant", "bakery", "matchType", MatchType.EXACT,
                "categoryId", UUID.randomUUID(), "confidence", new BigDecimal("0.90"),
                "source", MappingSource.USER, "priority", 10, "createdAt", NOW,
                "updatedAt", NOW)));
        when(budgetPeriods.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(BudgetPeriod.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "month", LocalDate.of(2026, 9, 1),
                "totalIncome", new BigDecimal("4000"), "currency", CurrencyCode.CAD,
                "budgetType", BudgetType.ZERO_BASED, "createdAt", NOW, "updatedAt", NOW)));
        when(envelopes.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(Envelope.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "periodId", UUID.randomUUID(), "categoryId", UUID.randomUUID(),
                "allocated", new BigDecimal("400"), "spent", new BigDecimal("340"),
                "rolloverAmount", new BigDecimal("0"), "currency", CurrencyCode.CAD,
                "createdAt", NOW, "updatedAt", NOW)));
        when(goals.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(SavingsGoal.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "name", "Roof", "targetAmount", new BigDecimal("9000"),
                "currentAmount", new BigDecimal("1200"), "deadline", LocalDate.of(2027, 6, 1),
                "currency", CurrencyCode.CAD, "achieved", false, "createdAt", NOW,
                "updatedAt", NOW)));
        when(subscriptions.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(DetectedSubscription.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "merchantName", "A STREAMING SERVICE",
                "normalizedAmount", new BigDecimal("16.49"),
                "previousAmount", new BigDecimal("12.99"), "currency", CurrencyCode.CAD,
                "billingCycle", BillingCycle.MONTHLY, "nextChargeDate", LocalDate.of(2026, 10, 1),
                "signal", SubscriptionSignal.GREEN, "priceChanged", true,
                "firstSeenDate", LocalDate.of(2025, 1, 1),
                "lastSeenDate", LocalDate.of(2026, 9, 1), "cancelled", false,
                "createdAt", NOW, "updatedAt", NOW)));
        when(snapshots.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(NetWorthSnapshot.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "snapshotDate", LocalDate.of(2026, 9, 1),
                "totalAssets", new BigDecimal("120000"),
                "totalLiabilities", new BigDecimal("40000"),
                "netWorth", new BigDecimal("80000"), "currency", CurrencyCode.CAD,
                "createdAt", NOW)));
        when(assets.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(Asset.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD, "userId", USER,
                "name", "A car", "assetType", AssetType.VEHICLE,
                "value", new BigDecimal("18000"), "currency", CurrencyCode.CAD,
                "createdAt", NOW, "updatedAt", NOW)));
        when(liabilities.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(Liability.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "name", "A mortgage", "liabilityType", LiabilityType.MORTGAGE,
                "balance", new BigDecimal("240000"), "interestRate", new BigDecimal("4.75"),
                "currency", CurrencyCode.CAD, "createdAt", NOW, "updatedAt", NOW)));
        Receipt receipt = new Receipt(HOUSEHOLD, USER, ReceiptSource.UPLOAD, 4096L);
        ReflectionTestUtils.setField(receipt, "id", UUID.randomUUID());
        receipt.setExtractedCurrency(CurrencyCode.CAD);
        receipt.setExtractedAmount(new BigDecimal("12.34"));
        when(receipts.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(receipt));

        UUID conversation = UUID.randomUUID();
        when(coach.page(eq(USER), eq(null), anyInt(), anyInt())).thenReturn(new PagedResult<>(
            List.of(
                new CoachMessageResponse(UUID.randomUUID(), conversation, CoachRole.USER,
                    "How much did I spend on groceries?", null, NOW),
                new CoachMessageResponse(UUID.randomUUID(), conversation, CoachRole.ASSISTANT,
                    "Rather a lot.", "a-model", NOW)),
            2L, 0, DataExportCollector.COACH_PAGE_SIZE));
        when(preferences.findByUserId(USER))
            .thenReturn(Optional.of(NotificationPreference.defaultsFor(USER)));
        when(taxLines.findAllByUserId(USER)).thenReturn(List.of(
            row(TaxLineMapping.class, "id", UUID.randomUUID(), "jurisdiction", Jurisdiction.CA,
                "categoryId", UUID.randomUUID(), "userId", USER, "lineCode", "8810",
                "lineLabel", "Office expenses", "deductible", true, "createdAt", NOW,
                "updatedAt", NOW)));
        when(users.findByIdAndHouseholdId(USER, HOUSEHOLD)).thenReturn(Optional.of(
            row(User.class, "id", USER, "householdId", HOUSEHOLD, "role", UserRole.ADMIN,
                "email", "member@example.test", "displayName", "A Member",
                "currency", CurrencyCode.CAD, "country", Jurisdiction.CA,
                "timezone", "America/Halifax", "locale", "en-CA", "region", "NS",
                "onboardingCompleted", true, "active", true, "createdAt", NOW,
                "updatedAt", NOW)));
        when(webhooks.findAllByUserIdOrderByCreatedAtAsc(USER)).thenReturn(List.of(
            row(WebhookSubscription.class, "id", UUID.randomUUID(), "userId", USER,
                "targetUrl", "https://hooks.example.test/trove",
                "secretEncrypted", "v1:TOPSECRET", "enabled", true,
                "eventTypes", List.of("budget.threshold_crossed"), "createdAt", NOW,
                "updatedAt", NOW)));
        when(inbox.findAllByUserId(eq(USER), any(Pageable.class))).thenAnswer(call ->
            new PageImpl<>(List.of(notification(0, false)), call.getArgument(1), 1L));
        when(insights.page(eq(USER), anyInt(), anyInt())).thenReturn(new PagedResult<>(
            List.of(new InsightResponse(UUID.randomUUID(), LocalDate.of(2026, 8, 31),
                "Spend fell 8%", true, NOW, NOW)),
            1L, 0, DataExportCollector.COACH_PAGE_SIZE));
        when(subscriptionAlerts.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(SubscriptionAlert.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "userId", USER, "subscriptionId", UUID.randomUUID(),
                "alertType", AlertType.PRICE_INCREASE,
                "alertMessage", "A STREAMING SERVICE went up", "read", false,
                "createdAt", NOW)));

        return objectMapper.valueToTree(collector.collect(USER, HOUSEHOLD));
    }

    private static InAppNotification notification(int index, boolean dismissed) {
        return row(InAppNotification.class, "id", UUID.randomUUID(), "userId", USER,
            "type", NotificationType.BUDGET_THRESHOLD, "title", "n" + index, "body", "b" + index,
            "read", dismissed, "dismissed", dismissed, "createdAt", NOW,
            "readAt", dismissed ? NOW : null, "dismissedAt", dismissed ? NOW : null);
    }

    @Test
    @DisplayName("Every dataset gap row 16 names is present and carries its row")
    void everyDatasetIsCollected() {
        JsonNode datasets = datasets();

        for (String entry : List.of("accounts.json", "categories.json", "merchant-rules.json",
            "budget-periods.json", "envelopes.json", "savings-goals.json", "subscriptions.json",
            "net-worth-snapshots.json", "assets.json", "liabilities.json", "receipts.json",
            "coach-conversations.json", "tax-line-mappings.json", "webhooks.json",
            "notifications.json", "coach-insights.json", "subscription-alerts.json")) {
            assertThat(datasets.path(entry).isArray()).as("%s is a list", entry).isTrue();
            assertThat(datasets.path(entry).size())
                .as("%s must carry the row it was given", entry)
                .isEqualTo(1);
        }
        assertThat(datasets.path("notification-preferences.json").path("inAppEnabled").isBoolean())
            .as("a member's own settings are an object, not a list")
            .isTrue();
        JsonNode profile = datasets.path("profile.json");
        assertThat(profile.isObject()).as("the member's own profile is an object").isTrue();
        assertThat(profile.path("email").asString()).isEqualTo("member@example.test");
        assertThat(profile.path("displayName").asString()).isEqualTo("A Member");
        assertThat(profile.path("currency").asString()).isEqualTo("CAD");
        assertThat(profile.path("country").asString()).isEqualTo("CA");
        assertThat(profile.path("timezone").asString()).isEqualTo("America/Halifax");
        assertThat(profile.path("locale").asString()).isEqualTo("en-CA");
        JsonNode alert = datasets.path("subscription-alerts.json").get(0);
        assertThat(alert.path("alertType").asString()).isEqualTo("PRICE_INCREASE");
        assertThat(alert.path("attributedTo").asString()).isEqualTo(USER.toString());
    }

    @Test
    @DisplayName("The profile is the member's own row, named field by field, with no credential")
    void profileCarriesNoCredential() {
        JsonNode profile = datasets().path("profile.json");

        assertThat(Set.copyOf(profile.propertyNames()))
            .as("every field is named on purpose: no deletion stamp, and nothing a credential "
                + "table holds can reach this map")
            .containsExactlyInAnyOrder("id", "householdId", "role", "email", "displayName",
                "currency", "country", "timezone", "locale", "region", "onboardingCompleted",
                "active", "createdAt", "updatedAt");
    }

    @Test
    @DisplayName("Another member's profile is never read: the lookup is the caller's own id")
    void anotherMembersProfileIsAnEmptyObject() {
        // No stub for this member's own row: a household-wide read would still find a mate.
        when(users.findAllByHouseholdId(HOUSEHOLD)).thenReturn(List.of(
            row(User.class, "id", UUID.randomUUID(), "householdId", HOUSEHOLD,
                "email", "mate@example.test")));

        JsonNode profile = objectMapper.valueToTree(collector.collect(USER, HOUSEHOLD))
            .path("profile.json");

        assertThat(profile.isObject()).isTrue();
        assertThat(profile.isEmpty())
            .as("a household mate's users row is not this member's data")
            .isTrue();
    }

    @Test
    @DisplayName("A webhook's signing secret never reaches the archive, in any form")
    void webhookSecretIsNeverExported() {
        JsonNode datasets = datasets();

        String whole = objectMapper.writeValueAsString(datasets);
        assertThat(whole)
            .as("the secret is write-only (V34): no response carries it, and an archive is a "
                + "response the member emails to somebody")
            .doesNotContain("TOPSECRET")
            .doesNotContain("secretEncrypted")
            .doesNotContain("v1:")
            .doesNotContain("hasSecret");
        JsonNode webhook = datasets.path("webhooks.json").get(0);
        assertThat(webhook.path("targetUrl").asString())
            .isEqualTo("https://hooks.example.test/trove");
        assertThat(webhook.path("enabled").asBoolean()).isTrue();
        assertThat(webhook.path("eventTypes").get(0).asString())
            .isEqualTo("budget.threshold_crossed");
    }

    @Test
    @DisplayName("Coach insights carry the content InsightService decrypted, and nothing else")
    void insightsAreDecryptedOnlyByTheService() {
        JsonNode insight = datasets().path("coach-insights.json").get(0);

        assertThat(insight.path("content").asString())
            .as("InsightService.page is the one place a stored note is decrypted; this class "
                + "holds no key and never reads contentEncrypted")
            .isEqualTo("Spend fell 8%");
        assertThat(insight.path("weekStart").asString()).isEqualTo("2026-08-31");
        assertThat(insight.has("contentEncrypted")).isFalse();
    }

    @Test
    @DisplayName("An inbox and an insight history longer than one page are exported whole")
    void aLongInboxAndInsightHistoryIsNotCut() {
        int size = DataExportCollector.COACH_PAGE_SIZE;
        List<InAppNotification> first = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            first.add(notification(i, i % 2 == 0));
        }
        Sort newestFirst = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        when(inbox.findAllByUserId(USER, PageRequest.of(0, size, newestFirst)))
            .thenReturn(new PageImpl<>(first, PageRequest.of(0, size, newestFirst), size + 1L));
        when(inbox.findAllByUserId(USER, PageRequest.of(1, size, newestFirst)))
            .thenReturn(new PageImpl<>(List.of(notification(size, true)),
                PageRequest.of(1, size, newestFirst), size + 1L));
        when(insights.page(USER, 0, size)).thenReturn(new PagedResult<>(
            insightRows(size), size + 1L, 0, size));
        when(insights.page(USER, 1, size)).thenReturn(new PagedResult<>(
            insightRows(1), size + 1L, 1, size));

        JsonNode datasets = objectMapper.valueToTree(collector.collect(USER, HOUSEHOLD));

        JsonNode notifications = datasets.path("notifications.json");
        assertThat(notifications.size())
            .as("every page of the inbox is read, not only the first")
            .isEqualTo(size + 1);
        long dismissed = 0;
        for (JsonNode notification : notifications) {
            if (notification.path("dismissed").asBoolean()) {
                dismissed++;
            }
        }
        assertThat(dismissed)
            .as("a dismissed notification is hidden from the inbox, not deleted: it is still "
                + "the member's data")
            .isEqualTo(size / 2 + 1);
        assertThat(datasets.path("coach-insights.json").size()).isEqualTo(size + 1);
    }

    private static List<InsightResponse> insightRows(int count) {
        List<InsightResponse> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new InsightResponse(UUID.randomUUID(), LocalDate.of(2026, 1, 5).plusWeeks(i),
                "note " + i, false, NOW, null));
        }
        return rows;
    }

    @Test
    @DisplayName("Every amount in every dataset is a decimal string with its own currency")
    void everyAmountCarriesItsCurrency() {
        JsonNode datasets = datasets();

        assertMoney(datasets.path("budget-periods.json").get(0).path("totalIncome"), "4000.0000");
        assertMoney(datasets.path("envelopes.json").get(0).path("allocated"), "400.0000");
        assertMoney(datasets.path("savings-goals.json").get(0).path("targetAmount"), "9000.0000");
        assertMoney(datasets.path("subscriptions.json").get(0).path("normalisedAmount"),
            "16.4900");
        assertMoney(datasets.path("net-worth-snapshots.json").get(0).path("netWorth"),
            "80000.0000");
        assertMoney(datasets.path("assets.json").get(0).path("value"), "18000.0000");
        assertMoney(datasets.path("liabilities.json").get(0).path("balance"), "240000.0000");

        assertThat(datasets.path("liabilities.json").get(0).path("interestRatePercent").asString())
            .as("a rate is a percentage, not money: pairing it with a currency would invite a "
                + "reader to add it to something")
            .isEqualTo("4.75");
    }

    private static void assertMoney(JsonNode money, String expected) {
        assertThat(money.path("amount").isString())
            .as("a JSON number is an IEEE double in most readers, and NUMERIC(19,4) does not "
                + "survive that round trip: %s", money)
            .isTrue();
        assertThat(money.path("amount").asString()).isEqualTo(expected);
        assertThat(money.path("currency").asString())
            .as("no total is blended across currencies, so every figure names its own: %s", money)
            .isEqualTo(CurrencyCode.CAD.name());
    }

    @Test
    @DisplayName("Coach turns are grouped by conversation and carry their decrypted content")
    void coachTurnsAreGroupedAndDecrypted() {
        JsonNode conversations = datasets().path("coach-conversations.json");

        assertThat(conversations.size()).isEqualTo(1);
        JsonNode messages = conversations.get(0).path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).path("content").asString())
            .as("the decryption is CoachConversationService's, which is the one place a stored "
                + "turn is decrypted and only ever for the member who owns it — this class holds "
                + "no key")
            .isEqualTo("How much did I spend on groceries?");
        assertThat(messages.get(0).path("role").asString()).isEqualTo(CoachRole.USER.name());
    }

    @Test
    @DisplayName("A coach history longer than one page is exported whole (B2-15)")
    void aLongCoachHistoryIsNotCut() {
        int size = DataExportCollector.COACH_PAGE_SIZE;
        UUID session = UUID.randomUUID();
        when(preferences.findByUserId(USER)).thenReturn(Optional.empty());
        when(coach.page(eq(USER), any(), eq(0), eq(size))).thenReturn(new PagedResult<>(
            coachMessages(session, size), size + 1L, 0, size));
        when(coach.page(eq(USER), any(), eq(1), eq(size))).thenReturn(new PagedResult<>(
            coachMessages(session, 1), size + 1L, 1, size));

        JsonNode conversations = objectMapper.valueToTree(collector.collect(USER, HOUSEHOLD))
            .path("coach-conversations.json");

        assertThat(conversations.get(0).path("messages").size())
            .as("a member leaving with their data must not lose the turns past the first page")
            .isEqualTo(size + 1);
    }

    private static List<CoachMessageResponse> coachMessages(UUID session, int count) {
        List<CoachMessageResponse> messages = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            messages.add(new CoachMessageResponse(UUID.randomUUID(), session, CoachRole.USER,
                "q" + i, null, NOW));
        }
        return messages;
    }

    @Test
    @DisplayName("A member who never changed a notification setting exports an empty object")
    void anAbsentPreferenceRowIsAnEmptyObject() {
        when(preferences.findByUserId(USER)).thenReturn(Optional.empty());
        when(coach.page(any(), any(), anyInt(), anyInt()))
            .thenReturn(new PagedResult<>(List.of(), 0L, 0, 1));

        Map<String, Object> datasets = collector.collect(USER, HOUSEHOLD);

        assertThat(objectMapper.valueToTree(datasets)
            .path("notification-preferences.json").isEmpty())
            .as("an absent row is 'they never changed the defaults', which is different from a "
                + "row full of nulls")
            .isTrue();
    }
}
