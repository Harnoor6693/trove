package finance.trove.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The object store: a directory on the {@code trove_data} volume, replacing MinIO
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.1/§5.12).
 *
 * <p><strong>Keys are generated here, never supplied by a caller.</strong> A key is
 * {@code <area>/<householdId>/<uuid>[.ext]} — a v4 UUID, plus at most a short lowercase
 * alphanumeric extension derived from the caller's <em>hint</em> and re-validated, never the
 * uploaded filename. That closes the whole class of traversal, overwrite and
 * {@code Content-Disposition} bugs at the source: there is no caller-controlled component in a
 * path, so {@code ../../etc/passwd} and {@code receipt.jpg.php} cannot be expressed. Every key
 * is nonetheless re-checked on the way back in ({@link #pathOf}) — resolve, normalise, and
 * require the result to still be under the root — because a key round-trips through the database
 * between being written and being read.
 *
 * <p><strong>Writes are atomic.</strong> Content goes to a temporary file in the destination
 * directory and is then {@code ATOMIC_MOVE}d into place, so a crash or a full disk mid-write
 * leaves no half-written receipt that the pipeline would later read as a truncated JPEG.
 *
 * <p><strong>Usage is accounted per household</strong> by putting the household id in the path
 * (gap P2-11's per-user quota, applied at the household the ledger is shared by). {@link #usage}
 * walks that subtree; it is called on upload, which is a human-paced operation, so a walk is
 * cheaper and harder to get wrong than a counter that has to survive partial failures.
 *
 * <p>Files are created {@code 0600} where the filesystem supports POSIX permissions (gap P2-11).
 */
public class LocalDiskStorage {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDiskStorage.class);

    /** Longest extension kept from a caller's hint; anything longer is dropped entirely. */
    static final int MAX_EXTENSION_LENGTH = 10;

    private static final Set<PosixFilePermission> OWNER_ONLY =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    public LocalDiskStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The data volume root; {@code StorageConfig} has already created the area directories. */
    public Path root() {
        return root;
    }

    /**
     * Stores {@code content} and returns its key.
     *
     * @param area          which of the three areas this belongs in
     * @param householdId   the owning household; becomes the second path segment
     * @param content       the bytes to store
     * @param extensionHint a desired extension such as {@code "jpg"} or {@code ".pdf"}; sanitised
     *                      to lowercase alphanumerics and dropped if it does not survive
     * @return the storage key, e.g. {@code receipts/1f0…/9c2….jpg}
     */
    public String put(StorageArea area, UUID householdId, byte[] content, String extensionHint) {
        requireArgs(area, householdId);
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return write(area, householdId, extensionHint,
            (target, temp) -> Files.write(temp, content));
    }

    /**
     * Stores the whole of {@code content} and returns its key. The stream is consumed but not
     * closed — the caller owns it, because for a multipart upload it is the container's.
     */
    public String put(StorageArea area, UUID householdId, InputStream content,
                      String extensionHint) {
        requireArgs(area, householdId);
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        return write(area, householdId, extensionHint,
            (target, temp) -> Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING));
    }

    /** Opens a stored object for reading. The caller closes the stream. */
    public InputStream open(String key) {
        Path path = pathOf(key);
        try {
            return Files.newInputStream(path);
        } catch (NoSuchFileException e) {
            throw new StorageException("No stored object for key " + key, e);
        } catch (IOException e) {
            throw new StorageException("Could not open " + key, e);
        }
    }

    /** Reads a whole stored object. Only for objects known to be small (thumbnails, manifests). */
    public byte[] readAllBytes(String key) {
        try (InputStream in = open(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new StorageException("Could not read " + key, e);
        }
    }

    /**
     * Deletes a stored object.
     *
     * @return {@code true} if it existed and was deleted, {@code false} if it was already gone
     */
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(pathOf(key));
        } catch (IOException e) {
            throw new StorageException("Could not delete " + key, e);
        }
    }

    public boolean exists(String key) {
        return Files.isRegularFile(pathOf(key));
    }

    /** Size in bytes of one stored object. */
    public long size(String key) {
        try {
            return Files.size(pathOf(key));
        } catch (NoSuchFileException e) {
            throw new StorageException("No stored object for key " + key, e);
        } catch (IOException e) {
            throw new StorageException("Could not stat " + key, e);
        }
    }

    /** Total bytes this household occupies across all three areas (gap P2-11's quota input). */
    public long usage(UUID householdId) {
        long total = 0L;
        for (StorageArea area : StorageArea.values()) {
            total += usage(area, householdId);
        }
        return total;
    }

    /** Total bytes this household occupies in one area. */
    public long usage(StorageArea area, UUID householdId) {
        requireArgs(area, householdId);
        Path directory = root.resolve(area.directoryName()).resolve(householdId.toString());
        if (!Files.isDirectory(directory)) {
            return 0L;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).mapToLong(LocalDiskStorage::sizeOf).sum();
        } catch (IOException e) {
            throw new StorageException("Could not measure usage under " + directory, e);
        }
    }

    /**
     * Resolves a key to an absolute path, refusing anything that escapes the root.
     *
     * <p>This is the traversal check. It runs on every read and every delete, not only on write,
     * because a key is persisted in a database column between the two and an injection into that
     * column would otherwise be a file-read primitive.
     */
    public Path pathOf(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("Storage key must not be blank");
        }
        if (key.indexOf('\0') >= 0 || key.indexOf('\\') >= 0 || key.startsWith("/")) {
            throw new StorageException("Illegal storage key: " + key);
        }
        String[] segments = key.split("/", -1);
        if (segments.length != 3 || StorageArea.fromDirectoryName(segments[0]) == null) {
            throw new StorageException("Illegal storage key: " + key);
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new StorageException("Illegal storage key: " + key);
            }
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Storage key escapes the data directory: " + key);
        }
        return resolved;
    }

    /**
     * Reduces a caller's extension hint to something safe: lowercase letters and digits only, at
     * most {@link #MAX_EXTENSION_LENGTH} characters. Anything else yields no extension at all —
     * the object is still perfectly readable, since the content type is stored in the database
     * row, not inferred from the path.
     */
    static String sanitiseExtension(String hint) {
        if (hint == null) {
            return "";
        }
        String candidate = hint.startsWith(".") ? hint.substring(1) : hint;
        if (candidate.isEmpty() || candidate.length() > MAX_EXTENSION_LENGTH) {
            return "";
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) {
                return "";
            }
        }
        return "." + lower;
    }

    private String write(StorageArea area, UUID householdId, String extensionHint,
                         ContentWriter writer) {
        String key = area.directoryName() + "/" + householdId + "/" + UUID.randomUUID()
            + sanitiseExtension(extensionHint);
        Path target = pathOf(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = target.resolveSibling(target.getFileName() + ".tmp");
            writer.writeTo(target, temp);
            restrictPermissions(temp);
            moveIntoPlace(temp, target);
            temp = null;
            return key;
        } catch (IOException e) {
            throw new StorageException("Could not store an object in " + area.directoryName(), e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // swallow-ok: some bind-mounted volumes cannot rename atomically; a plain move is
            // still a single filesystem operation on the same device and the target name is a
            // fresh UUID, so no reader can observe a partially written file under it.
            LOG.debug("Atomic move unavailable at {}, falling back to a plain move", target, e);
            Files.move(temp, target);
        } catch (FileAlreadyExistsException e) {
            throw new StorageException("Storage key collision at " + target, e);
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException e) {
            // swallow-ok: a non-POSIX filesystem (a Windows dev box, some container drivers)
            // cannot express 0600. The container runs as a single non-root user and the volume
            // is not shared, so the fallback is the directory's own permissions.
            LOG.debug("Could not restrict permissions on {}", file, e);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // swallow-ok: this runs on the failure path of a write that already threw; the
            // orphan is a zero-length .tmp file the hourly sweep of §5.6 removes.
            LOG.warn("Could not remove the temporary file {}", path, e);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requireArgs(StorageArea area, UUID householdId) {
        if (area == null) {
            throw new IllegalArgumentException("area must not be null");
        }
        if (householdId == null) {
            throw new IllegalArgumentException("householdId must not be null");
        }
    }

    /** Writes the content of one object into {@code temp}, which is then moved to {@code target}. */
    @FunctionalInterface
    private interface ContentWriter {
        void writeTo(Path target, Path temp) throws IOException;
    }
}
