package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.BaseIT;
import finance.trove.budgets.BudgetTestSupport;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.receipts.ReceiptImageFixtures;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code account-purge}: what is actually erased when the grace runs out, and what the household
 * keeps (collapse step 18; owner decision 17, gap row 16).
 *
 * <p>The two halves this asserts are the ones every implementation of this gets wrong. The
 * database says what belongs to a member — the cascades from {@code users(id)} — and the shared
 * ledger survives with its author nulled. But <strong>no cascade reaches a filesystem</strong>:
 * deleting an export run's row removes nothing from the exports area, and a complete copy of the
 * departed member's financial life would sit on the volume with no row pointing at it and no sweep
 * able to find it, because every sweep works from rows. Receipt images are the opposite case: they
 * stay, because the receipts stay with the household.
 */
class AccountPurgeIT extends BudgetTestSupport {

    @Autowired
    private AccountPurgeJob purgeJob;

    @Autowired
    private LocalDiskStorage storage;

    private void backdateDeletion(UUID userId, String interval) {
        jdbcTemplate.update("UPDATE users SET deleted_at = now() - interval '" + interval + "' "
            + "WHERE id = ?", userId);
    }

    private long rowsOf(String table, UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
    }

    private String uploadReceipt(Session member) {
        HttpResponse<String> uploaded = postMultipart(appUrl("/v1/receipts/upload"),
            List.of(BaseIT.Part.file("file", "receipt.jpg", "image/jpeg",
                ReceiptImageFixtures.jpeg(600, 400))),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(uploaded.statusCode()).as("%s", uploaded.body()).isEqualTo(202);
        return Json.data(uploaded.body()).path("id").asString();
    }

    private String exportArchiveKeyOf(Session member) {
        HttpResponse<String> created = postJson(appUrl("/v1/me/export"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(202);
        String runId = Json.data(created.body()).path("id").asString();
        for (int attempt = 0; attempt < 200; attempt++) {
            String key = jdbcTemplate.queryForObject(
                "SELECT file_key FROM data_export_run WHERE id = ?::uuid", String.class, runId);
            if (key != null) {
                return key;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the archive", e);
            }
        }
        throw new AssertionError("export run " + runId + " never wrote an archive");
    }

    @Test
    @DisplayName("One pass erases the member's own rows and their export archive from the volume")
    void thePurgeErasesWhatIsTheirsAndTheFilesNoCascadeReaches() {
        Session admin = freshHousehold("purge-admin");
        Session member = inviteAndRegister(admin, "purge-member");
        String archiveKey = exportArchiveKeyOf(member);
        assertThat(storage.exists(archiveKey)).isTrue();

        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);
        backdateDeletion(member.userId(), "8 days");

        assertThat(purgeJob.purge(Instant.now())).isPositive();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE id = ?", Long.class, member.userId()))
            .as("past the restore window the row goes for good")
            .isZero();
        assertThat(rowsOf("local_credential", member.userId()))
            .as("credentials cascade from users(id), V4")
            .isZero();
        assertThat(rowsOf("refresh_token", member.userId())).isZero();
        assertThat(rowsOf("data_export_run", member.userId())).isZero();
        assertThat(storage.exists(archiveKey))
            .as("NO CASCADE REACHES A FILESYSTEM. Deleting the row removes nothing from the "
                + "exports area, so the purge has to read the key and delete the file first — "
                + "otherwise a complete copy of the departed member's financial life sits on the "
                + "volume with nothing pointing at it and no sweep able to find it")
            .isFalse();
    }

    @Test
    @DisplayName("The household's ledger survives with its author nulled, and receipts keep their "
        + "images")
    void theHouseholdKeepsItsLedgerAndItsReceiptImages() {
        Session admin = freshHousehold("purge-ledger-admin");
        Session member = inviteAndRegister(admin, "purge-ledger-member");
        String accountId = createManualAccount(member, "Chequing");
        String transactionId = createManualTransaction(member, accountId, "25.00",
            todayForCaller(member), "DEBIT", "Bakery").path("id").asString();
        String receiptId = uploadReceipt(member);
        String imageKey = jdbcTemplate.queryForObject(
            "SELECT file_key FROM receipt_image WHERE receipt_id = ?::uuid LIMIT 1",
            String.class, receiptId);
        assertThat(storage.exists(imageKey)).isTrue();

        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);
        backdateDeletion(member.userId(), "8 days");
        assertThat(purgeJob.purge(Instant.now())).isPositive();

        // Owner decision 17 in schema form: the shared ledger wins and the author is nulled.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT user_id FROM transaction WHERE id = ?::uuid", UUID.class, transactionId))
            .as("the household's transaction survives its author, redacted")
            .isNull();
        assertThat(Json.data(get(appUrl("/v1/transactions/" + transactionId),
            admin.accessToken()).body()).path("id").asString())
            .as("and the member who remains can still read it")
            .isEqualTo(transactionId);

        // V43 is the whole reason this assertion can be made. V37 declared receipt.user_id
        // NOT NULL ... ON DELETE CASCADE, under which erasing ONE member silently deleted the
        // HOUSEHOLD'S receipts and left their images on the volume with nothing pointing at them.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM receipt WHERE id = ?::uuid", Long.class, receiptId))
            .as("a receipt is a shared household ledger row: a household mate can see, download "
                + "and delete one somebody else photographed, so it outlives its uploader")
            .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT user_id FROM receipt WHERE id = ?::uuid", UUID.class, receiptId))
            .as("with the uploader nulled, exactly as every other household-scoped table does it")
            .isNull();
        assertThat(storage.exists(imageKey))
            .as("gap row 16's 'deletes receipt files' was written against the OLD per-user "
                + "receipt table and is superseded by V37's household scoping — the images stay "
                + "because the receipts stay")
            .isTrue();
    }

    @Test
    @DisplayName("A member still inside the grace is not erased, nor is a merely disabled one")
    void thePurgeTouchesNeitherTheGraceNorADisabledAccount() {
        Session admin = freshHousehold("purge-grace-admin");
        Session inGrace = inviteAndRegister(admin, "purge-grace-member");
        Session merelyDisabled = inviteAndRegister(admin, "purge-disabled-member");

        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + inGrace.accessToken()).statusCode()).isEqualTo(200);
        // Deactivated with no deletion stamp — an administrator suspending somebody rather than
        // removing them. This is the state deleted_at exists to distinguish.
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?",
            merelyDisabled.userId());

        purgeJob.purge(Instant.now());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE id = ?", Long.class, inGrace.userId()))
            .as("inside the window an administrator can still bring them back")
            .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE id = ?", Long.class, merelyDisabled.userId()))
            .as("deleted_at is the ONLY column the purge selects on: a member somebody merely "
                + "deactivated must never be erased, however long they stay disabled")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Security-event history survives the erasure with a null user id")
    void anIncidentReviewIsNotErasableByDeletingAnAccount() {
        Session admin = freshHousehold("purge-events-admin");
        Session member = inviteAndRegister(admin, "purge-events-member");
        UUID memberId = member.userId();
        List<UUID> eventIds = jdbcTemplate.queryForList(
            "SELECT id FROM security_event WHERE user_id = ? OR actor_id = ?", UUID.class,
            memberId, memberId);
        assertThat(eventIds)
            .as("registering already writes events for this member, or this test asserts nothing")
            .isNotEmpty();

        delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        backdateDeletion(memberId, "8 days");
        purgeJob.purge(Instant.now());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE id = ANY (?)", Long.class,
            (Object) eventIds.toArray(UUID[]::new)))
            .as("the ROWS survive: an incident review must not be erasable by deleting an account")
            .isEqualTo((long) eventIds.size());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? OR actor_id = ?", Long.class,
            memberId, memberId))
            .as("and the ids they carried are redacted by V9's ON DELETE SET NULL")
            .isZero();
    }
}
