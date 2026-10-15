package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.ImportSupport;
import finance.trove.app.support.Json;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An OFX statement, uploaded, previewed, committed and then uploaded again
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12; {@code 01-FINDINGS.md} O7/M0).
 *
 * <p>The second upload is the point. The pre-collapse normaliser wrote the literal {@code "ofx"}
 * into the deduplication column on every row, so with the unique index on
 * {@code (account_id, provider_transaction_id)} an OFX account could hold exactly one transaction
 * for ever — and {@code OfxPlugin} parsed {@code ACCTID} and discarded it, so a repeat upload had
 * no way to find the account it had already created and made a second one.
 */
class OfxImportIT extends ImportSupport {

    private Session admin;
    private String acctId;
    private byte[] statement;

    @BeforeEach
    void setUp() {
        admin = loginBootstrapAdmin();
        acctId = "OFX-" + UUID.randomUUID().toString().substring(0, 8);
        statement = ofxFixture("bank-sgml-usascii.ofx", "123456789", acctId);
    }

    @Test
    @DisplayName("An upload resolves its own account from ACCTID and previews without writing")
    void anUploadResolvesItsOwnAccount() {
        JsonNode preview = uploadOfx(admin, statement, null);

        assertThat(preview.path("sourceKind").asString()).isEqualTo("OFX");
        assertThat(preview.path("rowCount").asInt()).isEqualTo(3);
        assertThat(preview.path("newCount").asInt()).isEqualTo(3);
        assertThat(preview.path("mapping").isMissingNode())
            .as("an OFX file has no columns to map, so the member is not offered a mapping")
            .isTrue();

        String accountId = preview.path("accountId").asString();
        JsonNode account = Json.data(get(appUrl("/v1/accounts/" + accountId),
            admin.accessToken()).body());
        assertThat(account.path("sourceKind").asString()).isEqualTo("OFX");
        assertThat(account.path("currency").asString())
            .as("CURDEF is USD in this statement; the pre-collapse normaliser hardcoded USD for "
                + "every file, which is the same answer for the wrong reason — the CAD fixture "
                + "in OfxSourceTest is what tells them apart")
            .isEqualTo("USD");
        assertThat(transactionIds(admin, "accountId=" + accountId)).isEmpty();
    }

    @Test
    @DisplayName("A commit writes every transaction, keyed on FITID rather than on \"ofx\"")
    void aCommitWritesEveryTransaction() {
        JsonNode preview = uploadOfx(admin, statement, null);
        String accountId = preview.path("accountId").asString();

        JsonNode log = commit(admin, preview.path("id").asString(), null);

        assertThat(log.path("transactionsNew").asInt())
            .as("with the plugin's NAME in the dedup column an OFX account could hold ONE row")
            .isEqualTo(3);
        assertThat(listTransactions(admin, "accountId=" + accountId))
            .extracting(row -> row.path("providerTransactionId").asString())
            .containsExactlyInAnyOrder("TXN001", "TXN002", "TXN003");
    }

    @Test
    @DisplayName("§6 M0: the same file uploaded twice reuses the account and adds no transaction")
    void aRepeatUploadReusesTheAccountAndAddsNothing() {
        JsonNode first = uploadOfx(admin, statement, null);
        String accountId = first.path("accountId").asString();
        commit(admin, first.path("id").asString(), null);
        int accountsAfterFirst = accountCount();

        JsonNode second = uploadOfx(admin, statement, null);

        assertThat(second.path("accountId").asString())
            .as("ACCTID is the upsert key; a second account would duplicate every transaction "
                + "on it, past anything the per-account dedup key can catch")
            .isEqualTo(accountId);
        assertThat(second.path("newCount").asInt()).isZero();
        assertThat(second.path("duplicateCount").asInt()).isEqualTo(3);

        JsonNode log = commit(admin, second.path("id").asString(), null);

        assertThat(log.path("transactionsNew").asInt()).isZero();
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(3);
        assertThat(accountCount()).isEqualTo(accountsAfterFirst);
    }

    @Test
    @DisplayName("An OFX file with no ACCTID is refused rather than filed under a shared literal")
    void anOfxWithoutAnAcctIdIsRefused() {
        var response = upload(admin, fixtureBytes("bank-sgml-no-acctid.ofx"), "statement.ofx",
            "application/x-ofx", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .contains("ACCTID");
    }

    @Test
    @DisplayName("A file whose XML carries an external entity is refused, quoting no markup")
    void anXxePayloadIsRefused() {
        var response = upload(admin, fixtureBytes("bank-xml-xxe.ofx"), "statement.ofx",
            "application/x-ofx", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("passwd");
    }

    @Test
    @DisplayName("A windows-1252 statement keeps its accented merchant names through the ledger")
    void aNonUtf8StatementSurvivesTheRoundTrip() {
        byte[] canadian = ofxFixture("bank-sgml-windows1252.ofx", "CA-987654",
            "CA-" + UUID.randomUUID().toString().substring(0, 8));

        JsonNode preview = uploadOfx(admin, canadian, null);
        String accountId = preview.path("accountId").asString();
        commit(admin, preview.path("id").asString(), null);

        assertThat(listTransactions(admin, "accountId=" + accountId))
            .extracting(row -> row.path("merchantName").asString())
            .as("decoding a CHARSET:1252 file as UTF-8 stores replacement characters for ever")
            .contains("CAFÉ DÉPANNEUR");
        assertThat(Json.data(get(appUrl("/v1/accounts/" + accountId),
            admin.accessToken()).body()).path("currency").asString())
            .as("CURDEF is CAD; the source hardcoded USD and could not produce this at all")
            .isEqualTo("CAD");
    }

    @Test
    @DisplayName("An OFX file pointed at a CSV account is refused rather than parsed two ways")
    void anOfxFileAgainstACsvAccountIsRefused() {
        String csvAccount = createAccount(admin, CSV, "CSV account " + UUID.randomUUID(),
            "CHECKING", "CAD");

        var response = upload(admin, statement, "statement.ofx", "application/x-ofx", csvAccount);

        assertThat(response.statusCode())
            .as("the preview would read it as OFX and the commit would hand it to the CSV "
                + "parser, because the commit resolves the source from the ACCOUNT's kind")
            .isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    private int accountCount() {
        return Json.elements(Json.data(
            get(appUrl("/v1/accounts"), admin.accessToken()).body())).size();
    }
}
