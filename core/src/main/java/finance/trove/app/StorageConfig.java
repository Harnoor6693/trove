package finance.trove.app;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates and validates the data volume laid out at {@code ${TROVE_DATA_DIR}} — {@code receipts/},
 * {@code exports/}, {@code uploads/} — on boot, fails fast if any of them is not writable, and
 * publishes the {@link LocalDiskStorage} bean over it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.12).
 *
 * <p>The directory creation lands before the storage bean needs it because the {@code readiness}
 * health group watches {@code diskSpace} at {@code ${TROVE_DATA_DIR}/receipts} (gap P1-1):
 * Spring's {@code DiskSpaceHealthIndicator} reports {@code DOWN} for a path that does not exist,
 * so a first boot against an empty volume would never pass the container {@code HEALTHCHECK}.
 *
 * <p>Failing fast on a non-writable directory rather than on first upload is deliberate. The
 * usual cause is a volume mounted with the wrong owner, and the difference between finding that
 * at boot and finding it when a member photographs their first receipt is the difference between
 * a log line an operator sees during deployment and a support question a week later.
 */
@Configuration
public class StorageConfig {

    private static final Logger LOG = LoggerFactory.getLogger(StorageConfig.class);

    private final Path root;

    public StorageConfig(@Value("${trove.storage.root}") Path root) {
        this.root = root;
    }

    @PostConstruct
    void createDirectories() throws IOException {
        for (StorageArea area : StorageArea.values()) {
            Path dir = root.resolve(area.directoryName());
            Files.createDirectories(dir);
            if (!Files.isWritable(dir)) {
                throw new IllegalStateException(
                    "Data directory is not writable: " + dir
                        + " — check the TROVE_DATA_DIR volume mount and its ownership");
            }
        }
        LOG.info("Data directory ready at {}", root);
    }

    @Bean
    public LocalDiskStorage localDiskStorage() {
        return new LocalDiskStorage(root);
    }
}
