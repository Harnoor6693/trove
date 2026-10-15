package finance.trove.common.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A small, bounded, time-limited map.
 *
 * <p>Two places in the monolith need "remember this for a short while, in one JVM, and never grow
 * without bound": the per-user timezone lookup that {@code UserClock} makes on every date
 * boundary, and the refresh-rotation grace window that lets two browser tabs racing a 401 both
 * succeed ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5).
 *
 * <p>It is written rather than pulled in because the alternative is a cache library for two call
 * sites, and because the second call site holds a refresh token for thirty seconds — a value that
 * must never be persisted, never be evicted to disk, and never leave this process. A dependency
 * with its own eviction and statistics machinery is a larger promise than that.
 *
 * <p>Bounding is deliberately crude: at {@code maxEntries} the whole map is swept of expired
 * entries, and if that frees nothing the map is cleared. Clearing is safe for both users — a lost
 * timezone is re-read, a lost grace entry degrades to the ordinary rotation path.
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class ExpiringCache<K, V> {

    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final Duration ttl;

    public ExpiringCache(int maxEntries, Duration ttl) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttl = ttl;
    }

    /** The cached value, or {@code loader}'s answer, which is then cached. */
    public V get(K key, Function<K, V> loader) {
        Entry<V> existing = entries.get(key);
        if (existing != null && !existing.isExpired(Instant.now())) {
            return existing.value();
        }
        V loaded = loader.apply(key);
        put(key, loaded);
        return loaded;
    }

    /** The cached value, or {@code null} — no loader, for callers where absence is meaningful. */
    public V peek(K key) {
        Entry<V> existing = entries.get(key);
        return existing == null || existing.isExpired(Instant.now()) ? null : existing.value();
    }

    public void put(K key, V value) {
        if (entries.size() >= maxEntries) {
            sweep();
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(value, Instant.now().plus(ttl)));
    }

    public void evict(K key) {
        entries.remove(key);
    }

    /** Entries currently held, expired ones included. Test seam. */
    public int size() {
        return entries.size();
    }

    private void sweep() {
        Instant now = Instant.now();
        Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now)) {
                it.remove();
            }
        }
    }

    private record Entry<V>(V value, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
