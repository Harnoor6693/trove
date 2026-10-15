package finance.trove.ingestion;

import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.SourceKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.stereotype.Component;

/**
 * Indexes the registered {@link BankSource} beans by the kind of account they serve — the renamed
 * {@code PluginRegistry} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2, §2.12).
 *
 * <p>Two changes from what it replaces. It is keyed on a {@link SourceKind} rather than on a
 * free-text plugin id, so a mismatch between an account's column and a bean's name is not
 * possible. And a missing source is an {@link Optional}, not an exception thrown from deep inside
 * a sync: {@code SyncOrchestrator} turns it into 409 {@code conflict} with a sentence a member can
 * act on, rather than a 500.
 *
 * <p><strong>Collapse step 7 fills it in.</strong> {@code SimplefinSource}, {@code OfxSource} and
 * {@code CsvSource} are the three production beans; {@link SourceKind#MANUAL} stays deliberately
 * unregistered, so {@code POST /v1/accounts/{id}/sync} on a manual account still answers 409 —
 * there is genuinely nothing to fetch from, and that is what the contract documents.
 *
 * <p>Two beans claiming one kind fails the context refresh. That is deliberate: silently choosing
 * one of them would make which source ran depend on classpath order. The one exception is an
 * explicit {@code @Order}, which is a bean saying it deliberately stands in for another — see
 * {@code precedenceOf}. Collapse step 7 needs exactly one such override and no more.
 */
@Component
public class SourceRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(SourceRegistry.class);

    private final Map<SourceKind, BankSource> byKind = new EnumMap<>(SourceKind.class);

    public SourceRegistry(List<BankSource> sources) {
        List<BankSource> ordered = new ArrayList<>(sources);
        AnnotationAwareOrderComparator.sort(ordered);
        for (BankSource source : ordered) {
            BankSource winner = byKind.putIfAbsent(source.sourceKind(), source);
            if (winner == null) {
                continue;
            }
            if (precedenceOf(winner) == precedenceOf(source)) {
                throw new IllegalStateException("Two BankSource beans claim " + source.sourceKind()
                    + ": " + winner.getClass().getName() + " and " + source.getClass().getName()
                    + ". Exactly one source serves each kind. If one of them is deliberately "
                    + "replacing the other, say so with @Order — an accidental duplicate must "
                    + "still fail here.");
            }
            LOG.info("{} takes precedence over {} for {} accounts", winner.getClass().getName(),
                source.getClass().getName(), source.sourceKind());
        }
    }

    /**
     * Where a bean sits in the override order — Spring's own {@code @Order} value.
     *
     * <p>Two beans at the SAME precedence claiming one kind is still a context-refresh failure, and
     * that is the case this rule exists for: two production sources for one kind would make which
     * one ran depend on classpath order. An explicit {@code @Order} is a different statement — "I
     * am deliberately standing in for the other one" — and the only thing in the tree that makes
     * it is {@code TestBankSource}, the in-memory fake the step-6 integration tests drive. It
     * claims {@code SIMPLEFIN}, which {@code SimplefinSource} now also claims, and there is no
     * fourth {@link SourceKind} to move it to: {@code OFX} and {@code CSV} are taken as well, and
     * {@code MANUAL} must stay unregistered because "no source is registered for a manual account"
     * is an outcome {@code AccessControlIT} asserts on. A profile cannot separate them either —
     * the whole integration suite shares one context, and the SimpleFIN integration test needs the
     * real bean in it.
     */
    private static int precedenceOf(BankSource source) {
        if (source instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        Integer order = OrderUtils.getOrder(source.getClass());
        return order == null ? Ordered.LOWEST_PRECEDENCE : order;
    }

    /** The source for this kind of account, or empty when none is registered. */
    public Optional<BankSource> find(SourceKind kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    /** Which kinds this install can actually sync. Empty until collapse step 7. */
    public List<SourceKind> registeredKinds() {
        return List.copyOf(byKind.keySet());
    }
}
