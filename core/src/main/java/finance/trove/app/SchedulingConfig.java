package finance.trove.app;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The monolith's only {@code @EnableScheduling} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.6).
 *
 * <p>Four {@code SchedulingConfig} classes existed across the pre-collapse services and every one
 * of them scheduled jobs that a second replica would have run a second time. This is the one that
 * ships, and the {@code @EnableSchedulerLock} on it is why: every {@code @Scheduled} method in the
 * application carries {@code @SchedulerLock} with a distinct name, and the lock is taken in
 * Postgres (V13 {@code shedlock}), so the jobs stay correct if the deployment ever grows a second
 * instance — and, more immediately, so a rolling restart cannot run a purge twice.
 *
 * <p>{@code defaultLockAtMostFor = "PT10M"} is the deadline on a holder that dies without
 * releasing: a process killed mid-job would otherwise wedge its schedule for ever. Ten minutes is
 * comfortably longer than any job here and short enough that a crash costs one cycle.
 *
 * <p>The {@code usingDbTime()} configuration matters more than it looks. Without it ShedLock
 * compares the lock's expiry against the <em>application's</em> clock; with several instances or a
 * container whose clock has drifted, two of them disagree about whether a lock has expired and
 * both run the job. Reading the time from the database makes one clock authoritative.
 *
 * <p>Every trigger fires on UTC ({@code zone = "UTC"} on each {@code @Scheduled}). That is the
 * container clock and deliberately not the user's: what a job <em>computes</em> — a member's
 * "today", "this month" — is resolved per recipient from {@code users.timezone} through
 * {@code UserClock} (gap P2-15). The two are separate decisions and conflating them is what made
 * every pre-collapse digest arrive at the wrong local hour.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }
}
