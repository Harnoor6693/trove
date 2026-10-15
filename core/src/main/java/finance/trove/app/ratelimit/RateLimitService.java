package finance.trove.app.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The token buckets ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.8).
 *
 * <h2>Local buckets, not Valkey</h2>
 * The pre-collapse gateway kept bucket state in Valkey through
 * {@code LettuceBasedProxyManager}, which is the right answer for several gateway replicas sharing
 * one counter and the wrong answer here: the collapse ships one JVM (owner decision: monolith),
 * Valkey is deleted, and a network round trip on every request to count something that only this
 * process can be doing is pure cost. {@code bucket4j-core}'s local buckets are lock-free and live
 * in this heap.
 *
 * <h2>What a bucket is keyed on</h2>
 * {@code identity | pattern}. The identity is the authenticated user id when there is one and the
 * client address otherwise — which is only reachable because {@code RateLimitFilter} runs
 * <em>after</em> the JWT filter (§5.1 corrects the original draft, which ran it before and left
 * every authenticated request in one flat anonymous bucket, making the per-route seeds in V12
 * unreachable). Keying on the pattern rather than the URI means {@code /api/v1/admin/users/{a}}
 * and {@code .../{b}} share the {@code /api/**} bucket instead of each getting a fresh one, which
 * is what a per-id bucket would silently be: no limit at all.
 *
 * <h2>Bounded</h2>
 * Buckets live in a {@link BucketCache} with an idle TTL and a hard ceiling. Unbounded, a flood
 * from many addresses would exhaust the heap through the limiter itself. At the ceiling the
 * <em>least recently used</em> bucket goes, deliberately: the obvious bound — clear the map — hands
 * a full allowance back to every caller currently being refused, so presenting {@code max-buckets}
 * identities becomes a cheap, repeatable way of resetting the sign-in limit (confirmed review
 * finding 4). {@link BucketCache} carries the argument in full.
 */
@Service
public class RateLimitService {

    private final RateLimitRules rules;
    private final RateLimitProperties properties;
    private final BucketCache buckets;

    public RateLimitService(RateLimitRules rules, RateLimitProperties properties) {
        this.rules = rules;
        this.properties = properties;
        this.buckets = new BucketCache(properties.maxBuckets(), properties.bucketTtl());
    }

    /**
     * Spends one token for {@code identity} on {@code uri}.
     *
     * <p>Never returns null and never throws for an ordinary refusal — a refusal is a value. It
     * <em>can</em> throw if the rule table or the bucket machinery is broken, and that is
     * deliberate: {@link RateLimitFilter} has to be able to tell "you are over your limit" from "I
     * could not tell", because the two answers differ on {@code /v1/auth/**}, where not knowing
     * means refusing (§5.8, gap P2-5).
     *
     * @param identity the authenticated user id, or the client address for an anonymous caller
     * @param uri      the full request URI, servlet context path included
     */
    public RateLimitDecision consume(String identity, String uri) {
        Optional<RateLimitRule> matched = rules.ruleFor(uri);
        if (matched.isEmpty()) {
            return RateLimitDecision.unlimited();
        }
        RateLimitRule rule = matched.get();
        Bucket bucket = buckets.get(key(identity, rule), () -> newBucket(rule));
        return bucket.tryConsume(1)
            ? RateLimitDecision.allowed(rule)
            : RateLimitDecision.refused(rule, properties.retryAfterSeconds());
    }

    /**
     * The key a bucket is stored under: identity, pattern, <strong>and the rule's numbers</strong>.
     *
     * <p>{@code |} separates the parts because neither a UUID nor an IP literal can contain one,
     * so no identity can be spelled to collide with another's bucket.
     *
     * <p>Including the rate and capacity is what makes an admin's edit take effect. A bucket
     * carries the configuration it was built with, so keying on the pattern alone means a limit
     * raised in {@code rate_limit_config} does nothing until every existing bucket has aged out —
     * up to {@code bucket-ttl} later. An operator raising a ceiling because it is hurting a
     * household would watch the reload job report the new rule and see the old refusals continue,
     * which reads as the feature being broken. With the numbers in the key, the edit produces a
     * different bucket at the next reload and the old one simply expires unused.
     *
     * <p>The cost is that any edit resets consumption to full for that rule. That is the right
     * direction for the case that actually happens — an operator loosening a limit — and for the
     * other case the new, lower ceiling is in force from the next request onwards.
     */
    static String key(String identity, RateLimitRule rule) {
        return (identity == null ? "-" : identity) + "|" + rule.pattern()
            + "|" + rule.requestsPerMinute() + ":" + rule.burstCapacity();
    }

    private static Bucket newBucket(RateLimitRule rule) {
        return Bucket.builder()
            .addLimit(Bandwidth.builder()
                .capacity(rule.burstCapacity())
                .refillGreedy(rule.requestsPerMinute(), Duration.ofMinutes(1))
                .build())
            .build();
    }

    /** Live buckets, idle ones included. Test seam. */
    public int liveBuckets() {
        return buckets.size();
    }

    /** How many buckets the ceiling has evicted. Test seam. */
    public long evictedBuckets() {
        return buckets.evictions();
    }
}
