package finance.trove.notifications.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import finance.trove.notifications.preference.dto.NotificationPreferencesResponse;
import finance.trove.notifications.preference.dto.NotificationPreferencesUpdateRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Defaults on first access, and a PUT that really is partial. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationPreferenceServiceTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private NotificationPreferenceRepository repository;

    private final Map<UUID, NotificationPreference> stored = new HashMap<>();
    private NotificationPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new NotificationPreferenceService(repository);
        when(repository.findByUserId(any()))
            .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(repository.save(any(NotificationPreference.class))).thenAnswer(invocation -> {
            NotificationPreference row = invocation.getArgument(0);
            stored.put(row.getUserId(), row);
            return row;
        });
        // The real statement is INSERT ... ON CONFLICT DO NOTHING with V31's own column defaults;
        // NotificationPreference.defaultsFor is the in-memory statement of the same values, and
        // NotificationPreferenceIT#firstReadCreatesTheDefaults asserts a real first-access row
        // against them so the two cannot drift.
        when(repository.insertDefaults(any())).thenAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            return stored.putIfAbsent(userId, NotificationPreference.defaultsFor(userId)) == null
                ? 1 : 0;
        });
    }

    @Test
    @DisplayName("a member with no row gets one, with exactly V31's defaults")
    void firstAccessCreatesTheShippedDefaults() {
        NotificationPreference created = service.getOrCreate(USER);

        assertThat(created.getUserId()).isEqualTo(USER);
        assertThat(created.isInAppEnabled()).isTrue();
        assertThat(created.isEmailEnabled())
            .as("off by default: an install with no SMTP cannot deliver, and opting members in "
                + "to a channel that drops everything is finding D3")
            .isFalse();
        assertThat(created.getEmailAddress()).isNull();
        assertThat(created.isWeeklyDigestEnabled()).isTrue();
        assertThat(created.isSubscriptionAlertEnabled()).isTrue();
        assertThat(created.isSyncFailureAlertEnabled()).isTrue();
        assertThat(created.getBudgetAlertThresholdPercent()).isEqualTo((short) 90);
        assertThat(stored).as("the row is PERSISTED, because the digest enumerates its "
            + "recipients from this table").containsKey(USER);
    }

    @Test
    @DisplayName("the row is created once, not on every read")
    void theRowIsCreatedOnce() {
        NotificationPreference first = service.getOrCreate(USER);
        NotificationPreference second = service.getOrCreate(USER);

        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("an omitted field is left exactly as it was")
    void anOmittedFieldIsUnchanged() {
        NotificationPreference existing = service.getOrCreate(USER);
        existing.setWeeklyDigestEnabled(false);
        existing.setBudgetAlertThresholdPercent((short) 75);

        NotificationPreferencesUpdateRequest request =
            new NotificationPreferencesUpdateRequest();
        request.setSubscriptionAlertEnabled(false);

        NotificationPreference updated = service.update(USER, request);

        assertThat(updated.isSubscriptionAlertEnabled()).isFalse();
        assertThat(updated.isWeeklyDigestEnabled()).isFalse();
        assertThat(updated.getBudgetAlertThresholdPercent()).isEqualTo((short) 75);
    }

    @Test
    @DisplayName("an explicit null clears the email address; an omission does not")
    void anExplicitNullClearsTheAddress() {
        // The one field where "absent" and "explicitly null" differ, and the reason the request
        // is a bean rather than a record: a record's constructor receives null either way.
        NotificationPreference existing = service.getOrCreate(USER);
        existing.setEmailAddress("member@trove.test");

        NotificationPreferencesUpdateRequest omitted =
            new NotificationPreferencesUpdateRequest();
        omitted.setInAppEnabled(false);
        assertThat(service.update(USER, omitted).getEmailAddress())
            .isEqualTo("member@trove.test");

        NotificationPreferencesUpdateRequest cleared =
            new NotificationPreferencesUpdateRequest();
        cleared.setEmailAddress(null);
        assertThat(cleared.isEmailAddressPresent()).isTrue();
        assertThat(service.update(USER, cleared).getEmailAddress()).isNull();
    }

    @Test
    @DisplayName("a blank address is stored as null rather than as whitespace")
    void aBlankAddressIsNormalisedToNull() {
        NotificationPreferencesUpdateRequest request =
            new NotificationPreferencesUpdateRequest();
        request.setEmailAddress("   ");

        assertThat(service.update(USER, request).getEmailAddress()).isNull();
    }

    @Test
    @DisplayName("a threshold outside V31's CHECK range is refused before it reaches the database")
    void anImpossibleThresholdIsRefused() {
        NotificationPreference preference = NotificationPreference.defaultsFor(USER);

        assertThatThrownBy(() -> preference.setBudgetAlertThresholdPercent((short) 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preference.setBudgetAlertThresholdPercent((short) 201))
            .isInstanceOf(IllegalArgumentException.class);

        preference.setBudgetAlertThresholdPercent((short) 200);
        assertThat(preference.getBudgetAlertThresholdPercent()).isEqualTo((short) 200);
    }

    @Test
    @DisplayName("the threshold converts to an exact two-place fraction")
    void theThresholdIsAnExactFraction() {
        NotificationPreference preference = NotificationPreference.defaultsFor(USER);

        assertThat(preference.thresholdFraction())
            .isEqualByComparingTo(new java.math.BigDecimal("0.90"));
        preference.setBudgetAlertThresholdPercent((short) 1);
        assertThat(preference.thresholdFraction())
            .isEqualByComparingTo(new java.math.BigDecimal("0.01"));
        preference.setBudgetAlertThresholdPercent((short) 200);
        assertThat(preference.thresholdFraction())
            .isEqualByComparingTo(new java.math.BigDecimal("2.00"));
    }

    @Test
    @DisplayName("the response carries no push field of any kind")
    void theResponseHasNoPushField() {
        // Asserted structurally rather than by reading JSON: the record's components ARE the
        // wire contract, and the spec's schema is closed against exactly this list.
        assertThat(NotificationPreferencesResponse.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactlyInAnyOrder("inAppEnabled", "emailEnabled", "emailAddress",
                "weeklyDigestEnabled", "subscriptionAlertEnabled", "syncFailureAlertEnabled",
                "budgetAlertThresholdPercent", "updatedAt")
            .doesNotContain("pushEnabled", "apnsToken", "fcmToken", "taxReminderEnabled",
                "pushTokens", "devices");
    }
}
