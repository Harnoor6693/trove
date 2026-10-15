package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The producer-side contract: a subject that fits V33, and a dedup key that fits V32. */
class NotificationContentTest {

    @Test
    @DisplayName("a subject longer than the title column is truncated rather than rejected")
    void aLongSubjectIsTruncated() {
        // V33's title column is VARCHAR(300). Truncating is right where refusing is not: the
        // subject is built from a merchant name this server received from somebody else's feed,
        // so an over-long one is data rather than a bug, and losing the whole notification over
        // it would be worse than losing the tail of a sentence.
        String subject = "x".repeat(400);

        assertThat(NotificationContent.of(subject, "body", null).subject())
            .hasSize(NotificationContent.MAX_TITLE_LENGTH);
    }

    @Test
    @DisplayName("a dedup key longer than its column is a producer bug and fails loudly")
    void anOverLongDedupKeyIsRefused() {
        // The opposite call: silently truncating this one would make two different notifications
        // share a key and the second would never be sent. A key is built from ids, so an
        // over-long one is a programming error rather than data.
        assertThatThrownBy(() -> NotificationContent.of("subject", "body", "k".repeat(201)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unique index");
    }

    @Test
    @DisplayName("a blank or missing subject is refused")
    void aSubjectIsRequired() {
        assertThatThrownBy(() -> NotificationContent.of("   ", "body", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationContent.of(null, "body", null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NotificationContent.of("subject", null, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the template model is defensively copied and never null")
    void theTemplateModelIsSafeToHandOut() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("weekStart", "2026-08-31");
        NotificationContent content = new NotificationContent("s", "b", null, null,
            "weekly-digest", mutable);

        mutable.put("injected", "later");

        assertThat(content.templateModel()).containsOnlyKeys("weekStart");
        assertThat(NotificationContent.of("s", "b", null).templateModel()).isEmpty();
    }

    @Test
    @DisplayName("a null dedup key is legal and means 'not deduplicated'")
    void aNullKeyIsLegal() {
        assertThat(NotificationContent.of("s", "b", null).dedupKey()).isNull();
    }
}
