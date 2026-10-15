package finance.trove.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the limiter's memory is bounded — which is a security property, not a housekeeping one.
 *
 * <p>The cache this replaced ({@code common.cache.ExpiringCache}) bounds itself by clearing the
 * whole map, which hands a full allowance back to every caller currently being refused. Presenting
 * {@code max-buckets} identities inside one TTL was therefore a cheap, repeatable way of resetting
 * the sign-in limit, and {@link #theLeastRecentlyUsedBucketGoes()} is the assertion that says it
 * no longer is.
 */
class BucketCacheTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse(
        "2026-09-04T12:00:00Z"));

    private static Bucket bucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(2)
                .refillGreedy(2, Duration.ofMinutes(1)).build())
            .build();
    }

    private BucketCache cache(int maxEntries, Duration ttl) {
        return new BucketCache(maxEntries, ttl, now::get);
    }

    @Test
    @DisplayName("The same identity gets the same bucket back, with what it has already spent")
    void theSameKeyKeepsItsBucket() {
        BucketCache cache = cache(10, Duration.ofMinutes(10));

        Bucket first = cache.get("a", BucketCacheTest::bucket);
        first.tryConsume(1);
        Bucket again = cache.get("a", BucketCacheTest::bucket);

        assertThat(again).isSameAs(first);
        assertThat(again.getAvailableTokens())
            .as("a limiter that hands back a fresh bucket per request is not a limiter")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("At the ceiling the LEAST RECENTLY USED bucket goes — the map is never cleared")
    void theLeastRecentlyUsedBucketGoes() {
        BucketCache cache = cache(2, Duration.ofMinutes(10));

        Bucket victim = cache.get("flood-1", BucketCacheTest::bucket);
        Bucket login = cache.get("login", BucketCacheTest::bucket);
        login.tryConsume(2);
        // Touching it makes 'login' the most recently used, so the flood's own bucket is the
        // eldest and is the one the ceiling takes.
        cache.get("login", BucketCacheTest::bucket);
        cache.get("flood-2", BucketCacheTest::bucket);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.evictions()).isEqualTo(1);
        assertThat(cache.get("login", BucketCacheTest::bucket))
            .as("clearing the map instead would restore every partially spent bucket to full "
                + "capacity, the sign-in ones included, so a flood of distinct identities would "
                + "reset the control that is supposed to be stopping it")
            .isSameAs(login);
        assertThat(cache.get("flood-1", BucketCacheTest::bucket))
            .as("and the evicted one is the flood's own, which is the direction the cost should "
                + "fall in")
            .isNotSameAs(victim);
    }

    @Test
    @DisplayName("A bucket in continuous use is never dropped, however long the caller keeps going")
    void theTtlMeasuresIdleTime() {
        BucketCache cache = cache(10, Duration.ofMinutes(10));

        Bucket bucket = cache.get("a", BucketCacheTest::bucket);
        for (int minute = 0; minute < 60; minute++) {
            now.set(now.get().plus(Duration.ofMinutes(9)));
            assertThat(cache.get("a", BucketCacheTest::bucket))
                .as("expiring by absolute age would hand a caller who is being refused a full "
                    + "bucket every bucket-ttl, which is the same hole in a slower form")
                .isSameAs(bucket);
        }
    }

    @Test
    @DisplayName("A bucket nobody has touched for the TTL is replaced")
    void anIdleBucketIsReplaced() {
        BucketCache cache = cache(10, Duration.ofMinutes(10));
        Bucket first = cache.get("a", BucketCacheTest::bucket);

        now.set(now.get().plus(Duration.ofMinutes(11)));

        assertThat(cache.get("a", BucketCacheTest::bucket))
            .as("a caller silent for longer than the window would have refilled anyway, so "
                + "keeping the entry buys nothing but memory")
            .isNotSameAs(first);
    }

    @Test
    @DisplayName("A ceiling or a TTL that cannot work fails at construction, not on first use")
    void badSettingsAreRefused() {
        assertThatThrownBy(() -> new BucketCache(0, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxEntries");
        assertThatThrownBy(() -> new BucketCache(10, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ttl");
    }
}
