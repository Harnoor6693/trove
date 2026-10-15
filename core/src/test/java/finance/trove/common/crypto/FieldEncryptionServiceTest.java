package finance.trove.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The service that keeps bank credentials, AI API keys and TOTP secrets out of the database in
 * plaintext ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, gap P5-27).
 */
class FieldEncryptionServiceTest {

    private static final String HEX_KEY =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private final FieldEncryptionService service = new FieldEncryptionService(HEX_KEY);

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void encryptsAndDecrypts() {
            String plaintext = "{\"accessUrl\":\"https://bridge.example/simplefin/abc\"}";

            String ciphertext = service.encrypt(plaintext);

            assertThat(ciphertext).doesNotContain("bridge.example").doesNotContain("accessUrl");
            assertThat(service.decrypt(ciphertext)).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("ciphertext carries the v1: version prefix so a key can be rotated later")
        void ciphertextIsVersioned() {
            String ciphertext = service.encrypt("secret");

            assertThat(ciphertext).startsWith(FieldEncryptionService.CURRENT_VERSION + ":");
            assertThat(service.isEncrypted(ciphertext)).isTrue();
            assertThat(service.versionOf(ciphertext))
                .isEqualTo(FieldEncryptionService.CURRENT_VERSION);
        }

        @Test
        @DisplayName("the same plaintext twice gives different ciphertext — the IV is per call")
        void ivIsFreshPerCall() {
            assertThat(service.encrypt("same")).isNotEqualTo(service.encrypt("same"));
        }

        @Test
        void handlesEmptyAndUnicodePlaintext() {
            assertThat(service.decrypt(service.encrypt(""))).isEmpty();
            assertThat(service.decrypt(service.encrypt("naïve — 日本語 🔐")))
                .isEqualTo("naïve — 日本語 🔐");
        }

        @Test
        void refusesNullPlaintextAndEmptyCiphertext() {
            assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.decrypt(""))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("tamper and key detection")
    class Tampering {

        @Test
        @DisplayName("a single flipped bit in the stored column is caught by the GCM tag")
        void detectsTampering() {
            String ciphertext = service.encrypt("bank-credential");
            byte[] raw = Base64.getDecoder().decode(ciphertext.substring(3));
            raw[raw.length - 1] ^= 0x01;
            String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("authentication");
        }

        @Test
        @DisplayName("a modified IV is caught too, not just a modified payload")
        void detectsIvTampering() {
            String ciphertext = service.encrypt("bank-credential");
            byte[] raw = Base64.getDecoder().decode(ciphertext.substring(3));
            raw[0] ^= 0x01;

            assertThatThrownBy(() -> service.decrypt("v1:" + Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(EncryptionException.class);
        }

        @Test
        @DisplayName("ciphertext written under a different key does not decrypt to garbage")
        void detectsAWrongKey() {
            String ciphertext = service.encrypt("bank-credential");
            FieldEncryptionService other = new FieldEncryptionService(
                "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100");

            assertThatThrownBy(() -> other.decrypt(ciphertext))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("authentication");
        }

        @Test
        void rejectsUnversionedTruncatedAndUnknownVersionCiphertext() {
            assertThatThrownBy(() -> service.decrypt("bm90LXZlcnNpb25lZA=="))
                .isInstanceOf(EncryptionException.class).hasMessageContaining("no version prefix");
            assertThatThrownBy(() -> service.decrypt("v9:" + Base64.getEncoder()
                .encodeToString("x".repeat(40).getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(EncryptionException.class).hasMessageContaining("Unsupported");
            assertThatThrownBy(() -> service.decrypt("v1:c2hvcnQ="))
                .isInstanceOf(EncryptionException.class).hasMessageContaining("truncated");
            assertThatThrownBy(() -> service.decrypt("v1:!!!not base64!!!"))
                .isInstanceOf(EncryptionException.class).hasMessageContaining("base64");
        }

        @Test
        void versionOfRecognisesOnlyRealPrefixes() {
            assertThat(service.versionOf(null)).isNull();
            assertThat(service.versionOf("plaintext")).isNull();
            assertThat(service.versionOf("vx:abc")).isNull();
            assertThat(service.versionOf("v12345:abc")).isNull();
            assertThat(service.versionOf("v2:abc")).isEqualTo("v2");
            assertThat(service.isEncrypted("plaintext")).isFalse();
        }
    }

    @Nested
    @DisplayName("key validation at startup")
    class KeyValidation {

        @Test
        @DisplayName("both documented key forms are accepted and interoperate")
        void acceptsHexAndBase64OfTheSameKey() {
            byte[] key = HexFormat.of().parseHex(HEX_KEY);
            FieldEncryptionService fromBase64 =
                new FieldEncryptionService(Base64.getEncoder().encodeToString(key));

            assertThat(fromBase64.decrypt(service.encrypt("interop"))).isEqualTo("interop");
        }

        @ParameterizedTest(name = "a key of [{0}] fails the boot")
        @NullAndEmptySource
        @ValueSource(strings = {
            "   ",
            "${TROVE_ENCRYPTION_KEY}",
            "too-short",
            "0011223344556677889900112233445566778899001122334455667788990011223344",
            "zzzz0203040506",
        })
        void rejectsEveryBadKeyWithAnActionableMessage(String key) {
            assertThatThrownBy(() -> new FieldEncryptionService(key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TROVE_ENCRYPTION_KEY");
        }

        @ParameterizedTest(name = "the publicly known key [{0}] fails a non-dev boot")
        @MethodSource("finance.trove.common.crypto.FieldEncryptionServiceTest#publiclyKnownKeys")
        void refusesAPubliclyKnownKey(String key) {
            assertThatThrownBy(() -> new FieldEncryptionService(key))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TROVE_ENCRYPTION_KEY")
                .hasMessageContaining("repeated");
        }

        @Test
        @DisplayName("only the dev profile accepts application-dev.yml's all-zero default")
        void onlyTheDevProfileAcceptsTheZeroDefault() {
            CryptoProperties zero = new CryptoProperties("0".repeat(64));

            for (String profile : new String[] {"prod", "test", "default"}) {
                MockEnvironment environment = new MockEnvironment();
                environment.setActiveProfiles(profile);
                assertThatThrownBy(() -> new FieldEncryptionService(zero, environment))
                    .as("profile %s", profile)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("repeated");
            }
            MockEnvironment dev = new MockEnvironment();
            dev.setActiveProfiles("dev");
            FieldEncryptionService devService = new FieldEncryptionService(zero, dev);
            assertThat(devService.decrypt(devService.encrypt("dev"))).isEqualTo("dev");
        }

        @Test
        @DisplayName("valid base64 of the wrong length is named as such, not as 'invalid base64'")
        void namesTheLengthProblem() {
            String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> new FieldEncryptionService(sixteenBytes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 bytes")
                .hasMessageContaining("32");
        }

        @Test
        void anAbsentPropertyRecordFailsTheSameWay() {
            MockEnvironment environment = new MockEnvironment();
            assertThatThrownBy(() -> new FieldEncryptionService(null, environment))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                () -> new FieldEncryptionService(new CryptoProperties(null), environment))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("generateKey produces a key this service accepts")
        void generatedKeysAreUsable() {
            String generated = FieldEncryptionService.generateKey();
            FieldEncryptionService fresh = new FieldEncryptionService(generated);

            assertThat(fresh.decrypt(fresh.encrypt("round trip"))).isEqualTo("round trip");
            assertThat(generated).isNotEqualTo(FieldEncryptionService.generateKey());
        }
    }

    /**
     * One byte repeated 32 times, in both accepted forms: the all-zero default of
     * {@code application-dev.yml} and the other keys a person types when asked for "any" key.
     */
    static Stream<String> publiclyKnownKeys() {
        return Stream.of(
            "0".repeat(64),
            "f".repeat(64),
            "41".repeat(32),
            Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    @DisplayName("shutdown zeroes the key, and the service is unusable afterwards")
    void destroyZeroesTheKey() {
        FieldEncryptionService disposable = new FieldEncryptionService(HEX_KEY);
        String ciphertext = disposable.encrypt("value");

        disposable.destroy();

        assertThatThrownBy(() -> disposable.decrypt(ciphertext))
            .isInstanceOf(EncryptionException.class);
    }
}
