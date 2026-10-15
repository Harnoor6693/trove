package finance.trove.users;

import finance.trove.common.cache.ExpiringCache;
import finance.trove.common.time.DefaultUserTimeZoneProvider;
import finance.trove.common.time.TimeProperties;
import finance.trove.common.time.UserTimeZoneProvider;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The real {@link UserTimeZoneProvider} promised by collapse step 2: it reads
 * {@code users.timezone} (gap P2-15).
 *
 * <p>{@code UserClock} resolves every "today" and "this month" boundary through this bean, and it
 * does so on hot paths — a dashboard request, a budget aggregate, the weekly digest looping over
 * every member. Hitting Postgres for a 20-character string on each of those would be a query per
 * date comparison, so the answer is cached for a minute. A user who changes their timezone sees
 * the new boundary within that minute, which is the correct trade for a value that changes once
 * or twice in an account's lifetime; {@link #evict} makes the change immediate for the request
 * that made it.
 *
 * <p>{@code @Primary} overrides {@link DefaultUserTimeZoneProvider}, which stays as the fallback
 * for a {@code null} user (instance-wide maintenance work) and for a user whose row is gone.
 */
@Component
@Primary
public class UserTableTimeZoneProvider implements UserTimeZoneProvider {

    private static final Duration TTL = Duration.ofMinutes(1);
    private static final int MAX_ENTRIES = 1_000;

    private final UserRepository users;
    private final ZoneId fallback;
    private final ExpiringCache<UUID, ZoneId> cache =
        new ExpiringCache<>(MAX_ENTRIES, TTL);

    public UserTableTimeZoneProvider(UserRepository users, TimeProperties timeProperties) {
        this.users = users;
        this.fallback = ZoneId.of(timeProperties.defaultTimezone());
    }

    @Override
    public ZoneId zoneOf(UUID userId) {
        if (userId == null) {
            return fallback;
        }
        return cache.get(userId, this::load);
    }

    /** Drops the cached zone for one user, so a timezone change takes effect immediately. */
    @Override
    public void evict(UUID userId) {
        cache.evict(userId);
    }

    private ZoneId load(UUID userId) {
        return users.findTimezoneById(userId)
            .map(this::parse)
            .orElse(fallback);
    }

    private ZoneId parse(String zoneId) {
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            // The column is CHECKed only for length, and a zone id can also be invalidated by a
            // tzdata update that drops a deprecated name. Degrading to the install default keeps
            // "today" defined rather than failing the request.
            return fallback;
        }
    }
}
