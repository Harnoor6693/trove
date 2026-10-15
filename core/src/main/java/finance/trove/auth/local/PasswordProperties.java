package finance.trove.auth.local;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.password.*} — the argon2id cost parameters and the length policy of
 * readiness gap P2-4.
 *
 * <p>The defaults are P2-4's own numbers: {@code m=65536 KiB (64 MiB), t=3, p=1}, 16-byte salt,
 * 32-byte hash. They are configurable for one honest reason and one dishonest one that this
 * class refuses. The honest one: memory cost has to be tunable per host, and the integration
 * suite would otherwise spend 64 MiB and ~100 ms per login across several hundred logins. The
 * dishonest one — quietly lowering them in production to make a slow box feel faster — is what
 * {@code PasswordPropertiesTest} guards by asserting that the <em>defaults</em> in
 * {@code application.yml} are still P2-4's values.
 *
 * <p>Because the parameters are recorded inside each argon2id PHC string, raising them later does
 * not invalidate anybody's password: {@code LocalAccountService} re-hashes on the owner's next
 * successful login when the encoder reports the stored encoding as out of date.
 *
 * @param minLength     minimum characters; 12 per P2-4, with no composition rules — length is
 *                      what buys entropy, and "one upper, one digit, one symbol" measurably
 *                      pushes people towards {@code Password1!}
 * @param maxLength     maximum characters; 128, so a pathological input cannot be turned into a
 *                      memory-hard denial of service by hashing a megabyte
 * @param saltLength    argon2id salt, bytes
 * @param hashLength    argon2id output, bytes
 * @param parallelism   argon2id lanes ({@code p})
 * @param memoryKib     argon2id memory cost in KiB ({@code m})
 * @param iterations    argon2id time cost ({@code t})
 */
@ConfigurationProperties(prefix = "trove.auth.password")
public record PasswordProperties(
    int minLength,
    int maxLength,
    int saltLength,
    int hashLength,
    int parallelism,
    int memoryKib,
    int iterations) {

    /** P2-4's floor. Configuration may raise it; it may not go below this. */
    public static final int ABSOLUTE_MINIMUM_LENGTH = 12;

    public PasswordProperties {
        minLength = minLength <= 0 ? ABSOLUTE_MINIMUM_LENGTH : minLength;
        maxLength = maxLength <= 0 ? 128 : maxLength;
        saltLength = saltLength <= 0 ? 16 : saltLength;
        hashLength = hashLength <= 0 ? 32 : hashLength;
        parallelism = parallelism <= 0 ? 1 : parallelism;
        memoryKib = memoryKib <= 0 ? 65_536 : memoryKib;
        iterations = iterations <= 0 ? 3 : iterations;

        if (minLength < ABSOLUTE_MINIMUM_LENGTH) {
            throw new IllegalStateException(
                "trove.auth.password.min-length cannot be below " + ABSOLUTE_MINIMUM_LENGTH
                    + " (readiness gap P2-4); got " + minLength);
        }
        if (maxLength < minLength) {
            throw new IllegalStateException(
                "trove.auth.password.max-length (" + maxLength + ") is below min-length ("
                    + minLength + ")");
        }
    }
}
