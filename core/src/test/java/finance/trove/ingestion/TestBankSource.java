package finance.trove.ingestion;

import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The only {@link BankSource} this application has in collapse step 6, and it lives under
 * {@code src/test} on purpose.
 *
 * <p>The real SimpleFIN, OFX and CSV sources are step 7's. Nothing on the MAIN classpath
 * implements the interface, so in production {@link SourceRegistry} is empty and every
 * {@code POST /v1/accounts/{id}/sync} on a non-MANUAL account answers 409 {@code conflict} — which
 * is the honest answer, because there is genuinely nothing to fetch from. This class exists so the
 * orchestrator's claim, its batching, its partial-failure path and its failure path can all be
 * driven end to end before a real source exists.
 *
 * <p>It is a {@code @Component} rather than a {@code @TestConfiguration} bean because every
 * integration test in this suite shares ONE Spring context ({@code BaseIT}), and a
 * {@code @TestConfiguration} on any one of them would start a second one. The test classes
 * directory is on the classpath during a Failsafe run and {@code TroveApplication} scans
 * {@code finance.trove}, so a component here is simply part of the context every IT already boots.
 *
 * <p>It claims {@link SourceKind#SIMPLEFIN} and only that, and from collapse step 7 it
 * <strong>deliberately stands in for the real {@code SimplefinSource}</strong> inside the
 * integration context — which is what the {@code @Order} says. {@code SourceRegistry} lets a bean
 * with an explicit precedence override one without; two beans at the SAME precedence still fail
 * the context refresh, so an accidental duplicate is still caught.
 *
 * <p>There was no other way to arrange it. All three of {@code SIMPLEFIN}, {@code OFX} and
 * {@code CSV} are claimed by production beans after step 7; {@code MANUAL} cannot be borrowed,
 * because "no source is registered for a manual account" is the 409 {@code AccessControlIT}'s
 * step-6 sync probe asserts on; and a profile cannot separate the two, because the whole
 * integration suite shares ONE Spring context and {@code SimpleFinIT} needs the real bean in it.
 * {@code SimpleFinIT} therefore drives {@code SimplefinSource} directly rather than through the
 * registry, and its network behaviour is covered by unit tests against a WireMock bridge.
 *
 * <p><strong>It is a singleton shared by the whole suite</strong>, so every test that uses it calls
 * {@link #reset()} first and leaves it released. {@link #blockUntilReleased()} is what makes the
 * two-thread claim race deterministic: the winner parks inside {@code fetch} until the test lets
 * it go, which is the only way to be sure the loser's attempt happens while the claim is genuinely
 * held.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TestBankSource implements BankSource {

    private final List<RawTransaction> rows = new ArrayList<>();
    private final AtomicInteger fetchCount = new AtomicInteger();

    private volatile RuntimeException failure;
    private volatile CountDownLatch fetchStarted;
    private volatile CountDownLatch release;
    private volatile LocalDate lastRequestedFrom;
    private volatile LocalDate lastRequestedTo;

    @Override
    public SourceKind sourceKind() {
        return SourceKind.SIMPLEFIN;
    }

    @Override
    public List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                                      Map<String, String> config) {
        fetchCount.incrementAndGet();
        lastRequestedFrom = from;
        lastRequestedTo = to;

        CountDownLatch started = fetchStarted;
        if (started != null) {
            started.countDown();
        }
        CountDownLatch gate = release;
        if (gate != null) {
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The test never released TestBankSource");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while parked in fetch", e);
            }
        }

        RuntimeException configured = failure;
        if (configured != null) {
            throw configured;
        }
        synchronized (rows) {
            return List.copyOf(rows);
        }
    }

    /** Forgets every previous test's setup. Called from a {@code @BeforeEach}. */
    public void reset() {
        synchronized (rows) {
            rows.clear();
        }
        failure = null;
        fetchStarted = null;
        release = null;
        fetchCount.set(0);
        lastRequestedFrom = null;
        lastRequestedTo = null;
    }

    /** What the next fetch returns. */
    public void returning(List<RawTransaction> next) {
        synchronized (rows) {
            rows.clear();
            rows.addAll(next);
        }
        failure = null;
    }

    /** Makes the next fetch fail the way an expired credential does. */
    public void failingWith(String message) {
        failure = new BankSourceConnectionException(message);
    }

    /**
     * Parks the next fetch until {@link #releaseFetch()}.
     *
     * @return a latch that counts down the moment a fetch has actually begun, so a test can be
     *         sure the claim is held before it asserts what a second caller is told
     */
    public CountDownLatch parkNextFetch() {
        CountDownLatch started = new CountDownLatch(1);
        fetchStarted = started;
        release = new CountDownLatch(1);
        return started;
    }

    /** Lets a parked fetch continue. */
    public void releaseFetch() {
        CountDownLatch gate = release;
        if (gate != null) {
            gate.countDown();
        }
    }

    /** How many times a sync has actually reached this source since {@link #reset()}. */
    public int fetchCount() {
        return fetchCount.get();
    }

    /** The window the orchestrator asked for, so the lookback rules can be asserted. */
    public LocalDate lastRequestedFrom() {
        return lastRequestedFrom;
    }

    /** @see #lastRequestedFrom() */
    public LocalDate lastRequestedTo() {
        return lastRequestedTo;
    }
}
