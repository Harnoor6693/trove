package finance.trove.app.ratelimit;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bounded home of the live token buckets.
 *
 * <h2>Why this is not {@code ExpiringCache}</h2>
 * {@code common.cache.ExpiringCache} bounds itself by clearing the whole map when a sweep of
 * expired entries frees nothing, which is safe for the two callers it was written for — a lost
 * timezone is re-read and a lost refresh-rotation grace entry degrades to the ordinary path.
 * It is not safe here. Clearing the map returns <em>every</em> partially spent bucket to full
 * capacity, the sign-in buckets included, so a caller able to present {@code max-buckets} distinct
 * identities inside one TTL — a botnet, or a single client on an IPv6 deployment where every
 * request can carry a different source address — resets the control that is supposed to be
 * stopping it, and can do so again whenever it likes. That is confirmed review finding 4.
 *
 * <p>So this cache evicts the <strong>least recently used</strong> entry instead: a flood costs
 * the flooders their own buckets first, because theirs are the ones nobody has touched since,
 * and a legitimate caller under an active limit keeps the bucket it has been spending.
 *
 * <h2>The TTL is idle time, not age</h2>
 * An entry's deadline moves forward on every access, so a bucket in continuous use is never
 * dropped. Expiring by absolute age would hand a caller who is being refused a full bucket every
 * {@code bucket-ttl}, which is the same hole in a slower form.
 *
 * <h2>Locking</h2>
 * One monitor around a {@link LinkedHashMap} in access order. A concurrent map cannot express
 * LRU eviction without a second structure, and the critical section here is a hash lookup and a
 * pointer move — orders of magnitude below the argon2id verification, the JWT parse and the
 * {@code refresh_token} read that the same request thread is about to do.
 */
final class BucketCache {

    private static final Logger LOG = LoggerFactory.getLogger(BucketCache.class);

    private final Duration ttl;
    private final Map<String, Entry> entries;
    private final Supplier<Instant> clock;

    private long evictions;

    BucketCache(int maxEntries, Duration ttl) {
        this(maxEntries, ttl, Instant::now);
    }

    /** The clock is a seam: the TTL behaviour is asserted without a test that sleeps. */
    BucketCache(int maxEntries, Duration ttl, Supplier<Instant> clock) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, got " + maxEntries);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive, got " + ttl);
        }
        this.ttl = ttl;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                if (size() <= maxEntries) {
                    return false;
                }
                evictions++;
                if (evictions == 1) {
                    LOG.warn("The rate limiter is at its ceiling of {} buckets and is now evicting "
                        + "the least recently used one per new identity. Raise "
                        + "trove.rate-limit.max-buckets if this is a real workload rather than a "
                        + "flood.", maxEntries);
                }
                return true;
            }
        };
    }

    /** The caller's bucket, created by {@code loader} when there is none or it has gone idle. */
    Bucket get(String key, Supplier<Bucket> loader) {
        Instant now = clock.get();
        synchronized (entries) {
            Entry existing = entries.get(key);
            if (existing != null && now.isBefore(existing.expiresAt())) {
                entries.put(key, new Entry(existing.bucket(), now.plus(ttl)));
                return existing.bucket();
            }
            Bucket fresh = loader.get();
            entries.put(key, new Entry(fresh, now.plus(ttl)));
            return fresh;
        }
    }

    /** Buckets currently held, idle-but-not-yet-replaced ones included. Test seam. */
    int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** How many buckets have been evicted for the ceiling. Test seam, and an operator's signal. */
    long evictions() {
        synchronized (entries) {
            return evictions;
        }
    }

    private record Entry(Bucket bucket, Instant expiresAt) {
    }
}
