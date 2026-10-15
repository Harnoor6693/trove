package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The type that makes finding D3 unwritable.
 *
 * <p>D3 was a delivery log that said {@code SENT} for everything, for ever, on every install:
 * the dispatcher set the status before it attempted anything and only downgraded inside a
 * {@code catch} that no channel could ever reach. Every assertion below is about the shape of the
 * answer rather than about any one channel — a result that cannot describe a failure is what made
 * the log decoration, and a result that can describe one but leaves out the reason is the same
 * defect written the other way round.
 */
class DeliveryResultTest {

    @Test
    @DisplayName("a FAILED result without a reason cannot be constructed")
    void failedWithoutAReasonIsRefused() {
        assertThatThrownBy(() -> new DeliveryResult(DeliveryResult.Status.FAILED, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("D3");

        assertThatThrownBy(() -> new DeliveryResult(DeliveryResult.Status.FAILED, "   "))
            .as("a blank reason is no reason; V32's CHECK would admit it and a human could not "
                + "act on it")
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> DeliveryResult.failed((String) null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a SENT result carrying an error cannot be constructed either")
    void sentWithAnErrorIsRefused() {
        assertThatThrownBy(() -> new DeliveryResult(DeliveryResult.Status.SENT, "but also broken"))
            .as("SENT-with-an-error is the row a half-finished fix produces; the type refuses it "
                + "so that nothing has to notice at review time")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a result with no status at all cannot be constructed")
    void statusIsRequired() {
        assertThatThrownBy(() -> new DeliveryResult(null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sent() carries no error and answers isSent")
    void sentIsTheHappyPath() {
        DeliveryResult result = DeliveryResult.sent();

        assertThat(result.isSent()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.status()).isEqualTo(DeliveryResult.Status.SENT);
    }

    @Test
    @DisplayName("failed(reason) keeps the reason verbatim")
    void failedKeepsTheReason() {
        DeliveryResult result = DeliveryResult.failed("550 mailbox unavailable");

        assertThat(result.isSent()).isFalse();
        assertThat(result.error()).isEqualTo("550 mailbox unavailable");
    }

    @Test
    @DisplayName("failed(throwable) falls back to the class name when the message is null")
    void failedFromAThrowableAlwaysHasAReason() {
        // The case that would otherwise reintroduce the defect: a NullPointerException with no
        // message would produce a FAILED row with a null error, which is precisely the row D3
        // produced. The reason is the exception's type instead.
        assertThat(DeliveryResult.failed(new NullPointerException()).error())
            .isEqualTo("NullPointerException");
        assertThat(DeliveryResult.failed(new IllegalStateException("  ")).error())
            .isEqualTo("IllegalStateException");
        assertThat(DeliveryResult.failed(new IllegalStateException("smtp is down")).error())
            .isEqualTo("smtp is down");
        assertThat(DeliveryResult.failed((Throwable) null).error()).isEqualTo("unknown failure");
    }
}
