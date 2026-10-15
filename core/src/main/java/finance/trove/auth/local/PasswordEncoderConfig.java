package finance.trove.auth.local;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The install's one {@link PasswordEncoder}: argon2id with the parameters of readiness gap P2-4
 * ({@code m=64 MiB, t=3, p=1}, 16-byte salt, 32-byte hash).
 *
 * <p>argon2id rather than bcrypt or PBKDF2 because it is <em>memory</em>-hard: bcrypt's cost is
 * CPU time, which a GPU or an ASIC buys cheaply, while 64 MiB per guess is expensive on every
 * architecture. That is the property that matters when the threat is an offline attack on a
 * stolen database of a public-internet finance server.
 *
 * <p>The parameters are constructor arguments rather than
 * {@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()} so that P2-4's numbers are the
 * numbers, visibly, and so that a slow host can raise or lower memory without a code change. Each
 * hash records the parameters it was made with, so a later change re-hashes on the owner's next
 * successful login instead of invalidating every password (see {@code LocalAccountService}).
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(PasswordProperties properties) {
        return new Argon2PasswordEncoder(
            properties.saltLength(),
            properties.hashLength(),
            properties.parallelism(),
            properties.memoryKib(),
            properties.iterations());
    }
}
