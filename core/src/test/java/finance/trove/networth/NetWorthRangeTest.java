package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link NetWorthRange#fromWireValue(String)} — the mapping {@link NetWorthRangeConverter} uses. */
class NetWorthRangeTest {

    @Test
    @DisplayName("Every documented wire value maps to its constant")
    void everyWireValueMaps() {
        assertThat(NetWorthRange.fromWireValue("3M")).isEqualTo(NetWorthRange.THREE_MONTHS);
        assertThat(NetWorthRange.fromWireValue("6M")).isEqualTo(NetWorthRange.SIX_MONTHS);
        assertThat(NetWorthRange.fromWireValue("1Y")).isEqualTo(NetWorthRange.ONE_YEAR);
        assertThat(NetWorthRange.fromWireValue("ALL")).isEqualTo(NetWorthRange.ALL);
    }

    @Test
    @DisplayName("Anything else is refused rather than coerced — the signal the 400 rides on")
    void anythingElseIsRefused() {
        assertThatThrownBy(() -> NetWorthRange.fromWireValue("2Y"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NetWorthRange.fromWireValue("all"))
            .as("the four literals are case-sensitive, matching the source's @Pattern")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The converter delegates to fromWireValue")
    void converterDelegates() {
        assertThat(new NetWorthRangeConverter().convert("1Y")).isEqualTo(NetWorthRange.ONE_YEAR);
        assertThatThrownBy(() -> new NetWorthRangeConverter().convert("bogus"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
