package finance.trove.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The hourly sweep, against real rows and a real volume
 * (§5 items 6 and 13; gap row 30; collapse step 14 scope item 6).
 *
 * <p>Both halves are exercised. Re-enqueuing a stuck receipt is what keeps a member from polling a
 * status that never moves after a restart or an {@code AbortPolicy} rejection; deleting a file
 * nothing names is the other half of gap row 30, which the re-encode and the quota do not cover.
 *
 * <p>Neither half can be proved without a disk and a database, which is why this is an IT and not
 * a unit test with a mocked storage: the orphan half is a directory walk compared against a
 * database read, and mocking either would be asserting on the mock.
 */
class ReceiptOrphanSweepIT extends AuthFlowSupport {

    @Autowired
    private ReceiptOrphanSweep sweep;

    @Autowired
    private LocalDiskStorage storage;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "receipt-sweep");
    }

    private UUID upload() {
        HttpResponse<String> response = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(500, 400))),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(response.statusCode())
            .as("upload failed: %s", response.body())
            .isEqualTo(202);
        return UUID.fromString(Json.data(response.body()).path("id").asString());
    }

    @Test
    @DisplayName("a receipt stuck in a non-terminal status past the bound is re-enqueued")
    void aStuckReceiptIsRequeued() {
        UUID id = upload();
        // The AI-off path lands on COMPLETE. Putting the row back into UPLOADED with an old
        // updated_at is exactly what an AbortPolicy rejection or a restart mid-pipeline leaves.
        jdbcTemplate.update("UPDATE receipt SET status = 'UPLOADED', "
            + "updated_at = now() - interval '4 hours' WHERE id = ?", id);

        assertThat(sweep.requeueStuckReceipts()).isPositive();

        // The re-run walks the whole chain again. Without a vision model it ends COMPLETE with
        // reviewRequired, which is the terminal state a member can act on — the point being that
        // the row does not sit in UPLOADED for ever.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM receipt WHERE id = ?", String.class, id))
            .isIn("COMPLETE", "FAILED");
    }

    @Test
    @DisplayName("a receipt that is still inside the bound is left alone")
    void aRecentReceiptIsLeftAlone() {
        UUID id = upload();
        jdbcTemplate.update("UPDATE receipt SET status = 'PARSING', updated_at = now() "
            + "WHERE id = ?", id);

        sweep.requeueStuckReceipts();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM receipt WHERE id = ?", String.class, id))
            .as("a receipt whose pipeline may still be running must not be restarted underneath it")
            .isEqualTo("PARSING");
    }

    @Test
    @DisplayName("a stored file that no row names is deleted, once it is past the grace window")
    void anOrphanedFileIsDeleted() {
        String key = storage.put(StorageArea.RECEIPTS, member.householdId(),
            ReceiptImageFixtures.jpeg(100, 100), "jpg");
        assertThat(storage.exists(key)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt_image WHERE file_key = ?", Integer.class, key))
            .as("this file is deliberately named by no row at all")
            .isZero();

        // Inside the grace window it must survive: the upload writes both files BEFORE its
        // transaction commits, so a sweep without this would delete the receipt a member is
        // uploading right now.
        sweep.deleteOrphanedFiles();
        assertThat(storage.exists(key))
            .as("a freshly written file may belong to an upload whose transaction has not "
                + "committed yet")
            .isTrue();

        age(storage.pathOf(key));
        sweep.deleteOrphanedFiles();

        assertThat(storage.exists(key))
            .as("a file nothing names is unreachable from every route and still consumes the "
                + "household's quota — gap row 30, one receipt at a time")
            .isFalse();
    }

    @Test
    @DisplayName("a file a row still names is never deleted, however old it is")
    void aNamedFileSurvives() {
        UUID id = upload();
        List<String> keys = jdbcTemplate.queryForList(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ?", String.class, id);
        assertThat(keys).hasSize(2);
        keys.forEach(key -> age(storage.pathOf(key)));

        sweep.deleteOrphanedFiles();

        keys.forEach(key -> assertThat(storage.exists(key))
            .as("%s is named by a receipt_image row and must survive every sweep", key)
            .isTrue());
    }

    @Test
    @DisplayName("the sweep is safe to run twice and reports zero the second time")
    void theSweepIsIdempotent() {
        String key = storage.put(StorageArea.RECEIPTS, member.householdId(),
            ReceiptImageFixtures.jpeg(100, 100), "jpg");
        age(storage.pathOf(key));

        assertThat(sweep.deleteOrphanedFiles()).isPositive();
        assertThat(sweep.deleteOrphanedFiles()).isZero();
    }

    /** Backdates a file past {@link ReceiptOrphanSweep#ORPHAN_GRACE}. */
    private static void age(Path file) {
        try {
            Files.setLastModifiedTime(file,
                FileTime.from(Instant.now().minus(ReceiptOrphanSweep.ORPHAN_GRACE)
                    .minusSeconds(60)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
