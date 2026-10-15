package finance.trove.app.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The {@code @Scheduled} guards §5.6 asks for, applied to <strong>every</strong> job in the
 * application rather than to one class.
 *
 * <p>{@link PurgeJobsTest} asserts the same three things by reflecting over
 * {@code PurgeJobs.class.getDeclaredMethods()}, which was the whole surface while {@code PurgeJobs}
 * was the only lock-guarded component in the tree. Collapse step 6 adds
 * {@code finance.trove.transactions.TransferPairingJob}, whose javadoc makes the load-bearing
 * claim that its lock name is distinct from every entry of {@code PurgeJobs.LOCK_NAMES} — and no
 * assertion anywhere could see it. A per-class guard cannot catch a collision, because a collision
 * is by definition a fact about two classes.
 *
 * <p>The three failures these rules exist to catch are all silent:
 * <ul>
 *   <li>two jobs sharing a ShedLock name serialise into each other, and the second silently
 *       never runs — ShedLock keys on the name alone and knows nothing of which method took it;</li>
 *   <li>a {@code @Scheduled} that lost its {@code @SchedulerLock} runs on every instance, and
 *       twice across a rolling restart;</li>
 *   <li>a cron with no {@code zone} fires in the container's local zone, so it drifts by an hour
 *       twice a year and, on the spring-forward hour, either fires twice or not at all.</li>
 * </ul>
 * None of them shows up as an error anywhere; the only way to notice is a test that names them.
 *
 * <p>Scanning the compiled output rather than a Spring context is deliberate: it needs no
 * container (the suite shares exactly one, and starting a second is banned), and it sees a job
 * that has been written but not yet wired as a bean — which is precisely the state the four
 * never-called purge methods of collapse step 3 were in.
 */
class ScheduledJobsTest {

    /**
     * Methods that are scheduled but deliberately hold no lock, with the reason.
     *
     * <p>{@code reloadRateLimits} refreshes state in THIS JVM's heap. A lock would mean that with
     * two instances only one ever reloaded and the other enforced stale rules for ever — the lock
     * causing the divergence it normally prevents.
     */
    private static final Set<String> DELIBERATELY_UNLOCKED = Set.of(
        "finance.trove.app.schedule.PurgeJobs.reloadRateLimits");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPath(mainOutputDirectory());

    @Test
    @DisplayName("There is at least one scheduled job, and PurgeJobs is no longer the only owner")
    void theScanSeesMoreThanOneClass() {
        List<String> owners = new ArrayList<>(new java.util.TreeSet<>(
            scheduledMethods().stream().map(method -> method.getOwner().getName()).toList()));

        assertThat(owners)
            .as("if this scan ever came back empty or one-class-wide it would be asserting "
                + "nothing, and the rules below would pass by vacuum")
            .contains("finance.trove.app.schedule.PurgeJobs",
                "finance.trove.transactions.TransferPairingJob");
    }

    @Test
    @DisplayName("No two jobs anywhere in the application share a ShedLock name")
    void everyShedLockNameIsUniqueAcrossTheWholeApplication() {
        List<String> names = new ArrayList<>();
        for (JavaMethod method : scheduledMethods()) {
            method.tryGetAnnotationOfType(SchedulerLock.class)
                .ifPresent(lock -> names.add(lock.name()));
        }

        assertThat(new HashSet<>(names))
            .as("ShedLock keys on the name alone: two jobs sharing one serialise into each other "
                + "and the second silently never runs. The names seen were %s", names)
            .hasSameSizeAs(names);
    }

    @Test
    @DisplayName("Every scheduled job is lock-guarded, or is on the named exemption list")
    void everyScheduledJobIsLockGuarded() {
        List<String> unguarded = new ArrayList<>();
        for (JavaMethod method : scheduledMethods()) {
            String id = method.getOwner().getName() + "." + method.getName();
            if (DELIBERATELY_UNLOCKED.contains(id)) {
                continue;
            }
            if (method.tryGetAnnotationOfType(SchedulerLock.class).isEmpty()) {
                unguarded.add(id);
            }
        }

        assertThat(unguarded)
            .as("a sweep without a lock runs on every instance and, on a rolling restart, twice. "
                + "If one is genuinely meant to, add it to DELIBERATELY_UNLOCKED with its reason")
            .isEmpty();
    }

    @Test
    @DisplayName("Every cron trigger in the application fires on UTC")
    void everyCronFiresOnUtc() {
        List<String> wrongZone = new ArrayList<>();
        for (JavaMethod method : scheduledMethods()) {
            Scheduled scheduled = method.getAnnotationOfType(Scheduled.class);
            if (!scheduled.cron().isEmpty() && !"UTC".equals(scheduled.zone())) {
                wrongZone.add(method.getOwner().getName() + "." + method.getName()
                    + " (zone='" + scheduled.zone() + "')");
            }
        }

        assertThat(wrongZone)
            .as("§5.6: the trigger fires on UTC; what a job COMPUTES resolves per user from "
                + "users.timezone (gap P2-15). A zone-less cron drifts by an hour twice a year "
                + "and misfires on the spring-forward hour")
            .isEmpty();
    }

    private static List<JavaMethod> scheduledMethods() {
        List<JavaMethod> found = new ArrayList<>();
        for (JavaClass type : PRODUCTION_CLASSES) {
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(Scheduled.class)) {
                    found.add(method);
                }
            }
        }
        found.sort(Comparator.comparing(method -> method.getOwner().getName() + "."
            + method.getName()));
        return found;
    }

    /** This module's own compiled output, resolved from the code source, not the classpath. */
    private static Path mainOutputDirectory() {
        try {
            return Paths.get(finance.trove.app.TroveApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot locate the compiled classes directory", e);
        }
    }
}
