package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * {@link SourceRegistry} — the renamed {@code PluginRegistry}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2).
 */
class SourceRegistryTest {

    @Test
    @DisplayName("An unregistered kind is empty, not an exception thrown from inside a sync")
    void anUnregisteredKindIsEmpty() {
        SourceRegistry registry = new SourceRegistry(List.of(source(SourceKind.SIMPLEFIN)));

        assertThat(registry.find(SourceKind.SIMPLEFIN)).isPresent();
        assertThat(registry.find(SourceKind.CSV))
            .as("the orchestrator turns this into a 409 with a sentence a member can act on, "
                + "rather than a 500 from deep inside a sync")
            .isEmpty();
        assertThat(registry.registeredKinds()).containsExactly(SourceKind.SIMPLEFIN);
    }

    @Test
    @DisplayName("An empty registry is legal — no source ships on the main classpath yet")
    void anEmptyRegistryIsLegal() {
        SourceRegistry registry = new SourceRegistry(List.of());

        assertThat(registry.registeredKinds()).isEmpty();
        for (SourceKind kind : SourceKind.values()) {
            assertThat(registry.find(kind)).isEmpty();
        }
    }

    @Test
    @DisplayName("Two sources claiming one kind fail the context refresh, naming both")
    void twoSourcesForOneKindIsAStartupFailure() {
        assertThatThrownBy(() -> new SourceRegistry(
            List.of(source(SourceKind.OFX), source(SourceKind.OFX))))
            .as("silently choosing one would make which source ran depend on classpath order")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OFX");
    }

    @Test
    @DisplayName("A source with an explicit @Order deliberately overrides one without")
    void anOrderedSourceOverridesAnUnorderedOne() {
        BankSource production = source(SourceKind.SIMPLEFIN);
        BankSource override = new OverridingSource();

        assertThat(new SourceRegistry(List.of(production, override)).find(SourceKind.SIMPLEFIN))
            .as("the override wins whichever order the beans arrive in, because the registry "
                + "sorts by precedence before it indexes")
            .contains(override);
        assertThat(new SourceRegistry(List.of(override, production)).find(SourceKind.SIMPLEFIN))
            .contains(override);
    }

    @Test
    @DisplayName("Two sources at the SAME precedence still fail, override rule or not")
    void twoOrderedSourcesAtTheSamePrecedenceStillFail() {
        assertThatThrownBy(() -> new SourceRegistry(
            List.of(new OverridingSource(), new OverridingSource())))
            .as("an accidental duplicate must still be a context-refresh failure; only a "
                + "DIFFERENCE in precedence is a bean saying it stands in for another")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SIMPLEFIN");
    }

    /**
     * Stands in for {@code TestBankSource}, which claims {@code SIMPLEFIN} on the test classpath
     * while {@code SimplefinSource} claims it on the main one.
     */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    private static final class OverridingSource implements BankSource {

        @Override
        public SourceKind sourceKind() {
            return SourceKind.SIMPLEFIN;
        }

        @Override
        public List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                                          Map<String, String> config) {
            return List.of();
        }
    }

    private static BankSource source(SourceKind kind) {
        return new BankSource() {
            @Override
            public SourceKind sourceKind() {
                return kind;
            }

            @Override
            public List<RawTransaction> fetch(String externalAccountId, LocalDate from,
                                              LocalDate to, Map<String, String> config) {
                return List.of();
            }
        };
    }
}
