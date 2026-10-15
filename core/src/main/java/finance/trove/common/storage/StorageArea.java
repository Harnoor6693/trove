package finance.trove.common.storage;

import java.util.Locale;

/**
 * The three sub-directories of the {@code trove_data} volume
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.1/§5.12). {@code StorageConfig} creates them on
 * boot; {@link LocalDiskStorage} refuses to address anything outside them.
 */
public enum StorageArea {

    /** Receipt originals and thumbnails. The readiness probe watches free space here. */
    RECEIPTS,

    /** Generated tax-time exports and full data exports; served once, then swept. */
    EXPORTS,

    /** Raw CSV/OFX/QFX uploads, retained only while an import batch can still be undone. */
    UPLOADS;

    private final String directoryName = name().toLowerCase(Locale.ROOT);

    /** The on-disk directory name, and the first segment of every key in this area. */
    public String directoryName() {
        return directoryName;
    }

    /** Resolves the area a storage key belongs to, or {@code null} if the prefix is unknown. */
    public static StorageArea fromDirectoryName(String name) {
        for (StorageArea area : values()) {
            if (area.directoryName.equals(name)) {
                return area;
            }
        }
        return null;
    }
}
