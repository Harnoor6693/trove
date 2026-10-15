package finance.trove.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalDiskStorageTest {

    @TempDir
    Path root;

    private LocalDiskStorage storage;
    private final UUID household = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        for (StorageArea area : StorageArea.values()) {
            Files.createDirectories(root.resolve(area.directoryName()));
        }
        storage = new LocalDiskStorage(root);
    }

    @Nested
    @DisplayName("keys are generated server-side")
    class KeyGeneration {

        @Test
        @DisplayName("a key is <area>/<householdId>/<uuid>.<ext>, never a client filename")
        void keyShape() {
            String key = storage.put(StorageArea.RECEIPTS, household, bytes("jpeg"), "jpg");

            assertThat(key).startsWith("receipts/" + household + "/").endsWith(".jpg");
            String name = key.substring(key.lastIndexOf('/') + 1, key.length() - 4);
            assertThat(UUID.fromString(name)).isNotNull();
        }

        @Test
        @DisplayName("two puts of identical content never collide")
        void keysAreUnique() {
            String first = storage.put(StorageArea.EXPORTS, household, bytes("x"), "csv");
            String second = storage.put(StorageArea.EXPORTS, household, bytes("x"), "csv");

            assertThat(first).isNotEqualTo(second);
            assertThat(storage.exists(first)).isTrue();
            assertThat(storage.exists(second)).isTrue();
        }

        @ParameterizedTest(name = "extension hint [{0}] is sanitised away")
        @ValueSource(strings = {
            "../../etc/passwd", "jp g", "jpg;rm -rf", "exe.", "a/b", "toolongextension", "JPG?x",
        })
        @DisplayName("a hostile extension hint yields no extension at all")
        void hostileExtensionHintsAreDropped(String hint) {
            assertThat(LocalDiskStorage.sanitiseExtension(hint)).isEmpty();
        }

        @Test
        void acceptableExtensionHintsAreNormalised() {
            assertThat(LocalDiskStorage.sanitiseExtension("JPG")).isEqualTo(".jpg");
            assertThat(LocalDiskStorage.sanitiseExtension(".Pdf")).isEqualTo(".pdf");
            assertThat(LocalDiskStorage.sanitiseExtension("ofx2")).isEqualTo(".ofx2");
            assertThat(LocalDiskStorage.sanitiseExtension(null)).isEmpty();
            assertThat(LocalDiskStorage.sanitiseExtension("")).isEmpty();
            assertThat(LocalDiskStorage.sanitiseExtension(".")).isEmpty();
        }

        @Test
        void keyWithoutAnExtensionIsStillUsable() {
            String key = storage.put(StorageArea.UPLOADS, household, bytes("data"), null);

            assertThat(key).doesNotContain(".");
            assertThat(new String(storage.readAllBytes(key), StandardCharsets.UTF_8))
                .isEqualTo("data");
        }
    }

    @Nested
    @DisplayName("path traversal")
    class Traversal {

        @ParameterizedTest(name = "key [{0}] is refused")
        @NullAndEmptySource
        @ValueSource(strings = {
            "   ",
            "../../../etc/passwd",
            "receipts/../../etc/passwd",
            "receipts/./x",
            "/etc/passwd",
            "receipts\\..\\..\\windows",
            "secrets/abc/def",
            "receipts/abc",
            "receipts/abc/def/ghi",
            "receipts//abc",
        })
        @DisplayName("every key that could escape the data directory throws before touching disk")
        void refusesTraversalKeys(String key) {
            assertThatThrownBy(() -> storage.pathOf(key)).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> storage.open(key)).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(StorageException.class);
        }

        @Test
        @DisplayName("a NUL byte in a key cannot reach the filesystem layer")
        void refusesNulBytes() {
            String key = "receipts/" + household + "/file\0.jpg";

            assertThatThrownBy(() -> storage.pathOf(key)).isInstanceOf(StorageException.class);
        }

        @Test
        void aValidKeyResolvesUnderTheRoot() {
            String key = storage.put(StorageArea.RECEIPTS, household, bytes("x"), "jpg");

            assertThat(storage.pathOf(key)).startsWith(root).exists();
            assertThat(storage.root()).isEqualTo(root.toAbsolutePath().normalize());
        }
    }

    @Nested
    @DisplayName("read, write and delete")
    class Lifecycle {

        @Test
        void storesFromAStream() throws IOException {
            byte[] content = "ofx payload".getBytes(StandardCharsets.UTF_8);
            String key;
            try (InputStream in = new ByteArrayInputStream(content)) {
                key = storage.put(StorageArea.UPLOADS, household, in, "ofx");
            }

            try (InputStream out = storage.open(key)) {
                assertThat(out.readAllBytes()).isEqualTo(content);
            }
            assertThat(storage.size(key)).isEqualTo(content.length);
        }

        @Test
        @DisplayName("no .tmp file is left behind after a successful write")
        void leavesNoTemporaryFile() throws IOException {
            storage.put(StorageArea.RECEIPTS, household, bytes("x"), "jpg");

            try (var walk = Files.walk(root)) {
                assertThat(walk.filter(Files::isRegularFile))
                    .noneSatisfy(p -> assertThat(p.getFileName().toString()).endsWith(".tmp"));
            }
        }

        @Test
        void deleteIsIdempotent() {
            String key = storage.put(StorageArea.RECEIPTS, household, bytes("x"), "jpg");

            assertThat(storage.delete(key)).isTrue();
            assertThat(storage.delete(key)).isFalse();
            assertThat(storage.exists(key)).isFalse();
        }

        @Test
        void readingAMissingObjectSaysWhichKey() {
            String missing = "receipts/" + household + "/" + UUID.randomUUID() + ".jpg";

            assertThatThrownBy(() -> storage.open(missing))
                .isInstanceOf(StorageException.class).hasMessageContaining(missing);
            assertThatThrownBy(() -> storage.size(missing))
                .isInstanceOf(StorageException.class).hasMessageContaining(missing);
            assertThatThrownBy(() -> storage.readAllBytes(missing))
                .isInstanceOf(StorageException.class);
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> storage.put(null, household, bytes("x"), "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.put(StorageArea.RECEIPTS, null, bytes("x"), "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                storage.put(StorageArea.RECEIPTS, household, (byte[]) null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                storage.put(StorageArea.RECEIPTS, household, (InputStream) null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("stored files are 0600 where the filesystem supports POSIX permissions")
        void filesAreOwnerOnly() throws IOException {
            String key = storage.put(StorageArea.RECEIPTS, household, bytes("x"), "jpg");
            Path path = storage.pathOf(key);

            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                assertThat(permissions).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            }
        }
    }

    @Nested
    @DisplayName("per-household usage accounting (gap P2-11)")
    class Usage {

        @Test
        void sumsAcrossAreasAndIgnoresOtherHouseholds() {
            UUID other = UUID.randomUUID();
            storage.put(StorageArea.RECEIPTS, household, new byte[100], "jpg");
            storage.put(StorageArea.RECEIPTS, household, new byte[50], "jpg");
            storage.put(StorageArea.EXPORTS, household, new byte[25], "csv");
            storage.put(StorageArea.RECEIPTS, other, new byte[9999], "jpg");

            assertThat(storage.usage(StorageArea.RECEIPTS, household)).isEqualTo(150);
            assertThat(storage.usage(StorageArea.EXPORTS, household)).isEqualTo(25);
            assertThat(storage.usage(StorageArea.UPLOADS, household)).isZero();
            assertThat(storage.usage(household)).isEqualTo(175);
            assertThat(storage.usage(other)).isEqualTo(9999);
        }

        @Test
        void aHouseholdThatHasStoredNothingUsesNothing() {
            assertThat(storage.usage(UUID.randomUUID())).isZero();
        }

        @Test
        void usageDropsWhenAnObjectIsDeleted() {
            String key = storage.put(StorageArea.RECEIPTS, household, new byte[100], "jpg");
            storage.delete(key);

            assertThat(storage.usage(household)).isZero();
        }

        @Test
        void rejectsNullArguments() {
            assertThatThrownBy(() -> storage.usage(StorageArea.RECEIPTS, null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.usage(null, household))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a broken volume produces a StorageException, not a raw IOException")
    class VolumeFailures {

        @Test
        @DisplayName("a household path occupied by a file fails the write with the area named")
        void aWriteOntoAnOccupiedPathFails() throws IOException {
            Files.writeString(root.resolve("receipts").resolve(household.toString()), "occupied");

            assertThatThrownBy(() -> storage.put(StorageArea.RECEIPTS, household, bytes("x"), "jpg"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("receipts")
                .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a key that resolves to a directory fails the read rather than returning bytes")
        void readingADirectoryFails() throws IOException {
            String key = "receipts/" + household + "/" + UUID.randomUUID();
            Path directory = Files.createDirectories(root.resolve(key));
            Files.writeString(directory.resolve("child"), "x");

            assertThatThrownBy(() -> storage.readAllBytes(key))
                .isInstanceOf(StorageException.class).hasMessageContaining("Could not read");
        }

        @Test
        @DisplayName("a delete that the filesystem refuses is a StorageException, not a raw IOException")
        void anUndeletablePathFails() throws IOException {
            String key = "receipts/" + household + "/" + UUID.randomUUID();
            Path directory = Files.createDirectories(root.resolve(key));
            Files.writeString(directory.resolve("child"), "x");

            assertThatThrownBy(() -> storage.delete(key))
                .isInstanceOf(StorageException.class).hasMessageContaining("Could not delete");
        }
    }

    @Test
    void storageAreaNamesMapBothWays() {
        for (StorageArea area : StorageArea.values()) {
            assertThat(StorageArea.fromDirectoryName(area.directoryName())).isEqualTo(area);
        }
        assertThat(StorageArea.fromDirectoryName("etc")).isNull();
        assertThat(StorageArea.RECEIPTS.directoryName()).isEqualTo("receipts");
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
