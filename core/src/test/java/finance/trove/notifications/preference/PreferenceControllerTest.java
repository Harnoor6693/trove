package finance.trove.notifications.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.send.DeliveryChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code emailAvailable} (journey bug F1-B8): true exactly when the server has an EMAIL channel.
 *
 * <p>The channel bean exists only when an operator sets {@code SMTP_ENABLED=true}, which no shipped
 * configuration does, so the integration tests can only see the false half. This is the other one.
 */
class PreferenceControllerTest {

    private final NotificationPreferenceService service = mock(NotificationPreferenceService.class);
    private final TroveUserDetails principal = mock(TroveUserDetails.class);

    private static DeliveryChannel channel(NotificationChannel kind) {
        DeliveryChannel channel = mock(DeliveryChannel.class);
        when(channel.channel()).thenReturn(kind);
        return channel;
    }

    private boolean emailAvailable(List<DeliveryChannel> channels) {
        UUID userId = UUID.randomUUID();
        when(principal.getUserId()).thenReturn(userId);
        when(service.getOrCreate(userId)).thenReturn(new NotificationPreference(userId));
        return new PreferenceController(service, channels).read(principal).data().emailAvailable();
    }

    @Test
    @DisplayName("With an EMAIL channel configured, email is available")
    void availableWithAnEmailChannel() {
        assertThat(emailAvailable(List.of(channel(NotificationChannel.IN_APP),
            channel(NotificationChannel.EMAIL)))).isTrue();
    }

    @Test
    @DisplayName("Without one, email is not offered")
    void unavailableWithoutAnEmailChannel() {
        assertThat(emailAvailable(List.of(channel(NotificationChannel.IN_APP)))).isFalse();
    }
}
