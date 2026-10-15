package finance.trove.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The boot-time fail-fast of §9 risk 16.
 *
 * <p>Each case here is a way a real install has shipped with a signing key that is not a secret,
 * and the assertion is that the application refuses to start rather than refusing the first login.
 */
class JwtPropertiesTest {

    private static final String GOOD = "a-long-enough-secret-for-hs256-signing!!";

    private static JwtProperties with(String secret) {
        return new JwtProperties(secret, null, null, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("A shipped placeholder secret is refused outright")
    void rejectsTheShippedPlaceholder() {
        assertThatThrownBy(() -> with(JwtProperties.SHIPPED_PLACEHOLDER))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("placeholder")
            .hasMessageContaining("forge");
    }

    @Test
    @DisplayName("An unsubstituted ${...} placeholder names the missing variable")
    void rejectsAnUnresolvedPlaceholder() {
        assertThatThrownBy(() -> with("${TROVE_JWT_SECRET}"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unsubstituted");
    }

    @ParameterizedTest(name = "a {0}-character secret is refused")
    @ValueSource(strings = {"short", "0123456789012345678901234567890"})
    @DisplayName("Anything shorter than the HMAC-SHA-256 block is refused")
    void rejectsShortSecrets(String secret) {
        assertThatThrownBy(() -> with(secret))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least " + JwtProperties.MINIMUM_SECRET_LENGTH);
    }

    @Test
    @DisplayName("An absent secret is refused with the command that generates one")
    void rejectsAnAbsentSecret() {
        assertThatThrownBy(() -> with(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openssl rand");
        assertThatThrownBy(() -> with("   "))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Exactly 32 bytes is accepted — the boundary is inclusive")
    void acceptsExactlyTheMinimum() {
        String exactly32 = "0".repeat(JwtProperties.MINIMUM_SECRET_LENGTH);
        assertThat(with(exactly32).secret()).hasSize(JwtProperties.MINIMUM_SECRET_LENGTH);
    }

    @Test
    @DisplayName("Defaults are the §1.5 lifetimes: 15 min, 24 h, 60 s, 5 min")
    void defaultsMatchThePlan() {
        JwtProperties properties = with(GOOD);
        assertThat(properties.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.refreshTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.totpChallengeTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.stepUpWindow()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.keyId()).isEqualTo("k1");
        assertThat(properties.issuer()).isEqualTo("trove-core");
    }
}
