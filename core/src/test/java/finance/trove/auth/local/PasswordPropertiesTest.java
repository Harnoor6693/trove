package finance.trove.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

/**
 * Guards the argon2id cost parameters against being quietly lowered.
 *
 * <p>The parameters are configurable for one honest reason — the integration suite and a slow
 * host — and there is a dishonest one this test exists to catch: making production feel faster by
 * dropping the memory cost, which is the single number that makes an offline attack on a stolen
 * database expensive. So it reads {@code application.yml} itself, rather than trusting the record's
 * defaults, and asserts P2-4's numbers are what a production boot uses.
 */
class PasswordPropertiesTest {

    @Test
    @DisplayName("application.yml still ships readiness gap P2-4's argon2id parameters")
    @SuppressWarnings("unchecked")
    void productionDefaultsAreP24s() throws Exception {
        Map<String, Object> yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            yaml = new Yaml().load(in);
        }
        Map<String, Object> password = (Map<String, Object>) nested(yaml, "trove", "auth",
            "password");

        assertThat(password)
            .as("m=64 MiB is what makes each guess expensive on a GPU as well as a CPU; halving "
                + "it halves the cost of an offline attack on the whole database")
            .containsEntry("memory-kib", 65_536)
            .containsEntry("iterations", 3)
            .containsEntry("parallelism", 1)
            .containsEntry("salt-length", 16)
            .containsEntry("hash-length", 32)
            .containsEntry("min-length", 12)
            .containsEntry("max-length", 128);
    }

    @Test
    @DisplayName("Configuration cannot push the minimum below P2-4's floor")
    void refusesToWeakenTheLengthFloor() {
        assertThatThrownBy(() -> new PasswordProperties(8, 128, 16, 32, 1, 65_536, 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be below 12");
    }

    @Test
    @DisplayName("A maximum below the minimum is a configuration error, not a silent swap")
    void refusesAnInvertedRange() {
        assertThatThrownBy(() -> new PasswordProperties(20, 10, 16, 32, 1, 65_536, 3))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("below min-length");
    }

    @Test
    @DisplayName("Zeroes bind to the documented defaults rather than to zero")
    void absentValuesTakeDefaults() {
        PasswordProperties properties = new PasswordProperties(0, 0, 0, 0, 0, 0, 0);
        assertThat(properties.minLength()).isEqualTo(12);
        assertThat(properties.memoryKib()).isEqualTo(65_536);
        assertThat(properties.iterations()).isEqualTo(3);
    }

    @Test
    @DisplayName("The encoder writes its parameters into the PHC string, so a re-tune re-hashes")
    void phcStringCarriesTheParameters() {
        PasswordProperties properties = new PasswordProperties(12, 128, 16, 32, 1, 1024, 1);
        PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder(properties);

        String hash = encoder.encode("a-perfectly-fine-passphrase");
        assertThat(hash)
            .as("the cost travels with each hash; without it, raising the parameters later would "
                + "mean invalidating every password instead of re-hashing on next login")
            .startsWith("$argon2id$v=19$")
            .contains("m=1024")
            .contains("t=1")
            .contains("p=1");
        assertThat(encoder.matches("a-perfectly-fine-passphrase", hash)).isTrue();
        assertThat(encoder.matches("something else entirely", hash)).isFalse();
    }

    @Test
    @DisplayName("A hash made at lower parameters is reported as needing an upgrade")
    void oldHashesAreFlaggedForRehash() {
        Argon2PasswordEncoder weak = new Argon2PasswordEncoder(16, 32, 1, 1024, 1);
        Argon2PasswordEncoder strong = new Argon2PasswordEncoder(16, 32, 1, 4096, 2);

        String oldHash = weak.encode("a-perfectly-fine-passphrase");
        assertThat(strong.upgradeEncoding(oldHash))
            .as("LocalAccountService re-hashes on the owner's next successful login when this "
                + "reports true; the alternative is every long-standing account staying weak")
            .isTrue();
        assertThat(strong.upgradeEncoding(strong.encode("a-perfectly-fine-passphrase"))).isFalse();
    }

    @Test
    @DisplayName("The record binds from relaxed property names")
    void bindsFromConfiguration() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "trove.auth.password.min-length", "16",
            "trove.auth.password.memory-kib", "8192")));
        PasswordProperties bound = new Binder(
            ConfigurationPropertySources.get(environment))
            .bind("trove.auth.password", PasswordProperties.class).get();

        assertThat(bound.minLength()).isEqualTo(16);
        assertThat(bound.memoryKib()).isEqualTo(8192);
        assertThat(bound.iterations()).as("unset values still default").isEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }
}
