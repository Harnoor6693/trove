package finance.trove.app.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup for collapse step 7's import integration tests.
 *
 * <p>Like {@link LedgerSupport}, it seeds nothing: every fixture goes through the real endpoint,
 * so a fixture that stops working is a test that fails rather than one that keeps passing against
 * a shape the API can no longer produce.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class ImportSupport extends LedgerSupport {

    /** The account kind an uploaded CSV is imported onto. */
    public static final String CSV = "CSV";

    /** The account kind an uploaded OFX file resolves to through its own {@code ACCTID}. */
    public static final String OFX = "OFX";

    /** Uploads a file and returns the raw response, so a test can assert a refusal. */
    protected HttpResponse<String> upload(Session session, byte[] content, String filename,
                                          String contentType, String accountId) {
        List<Part> parts = new ArrayList<>();
        parts.add(Part.file("file", filename, contentType, content));
        if (accountId != null) {
            parts.add(Part.field("accountId", accountId));
        }
        return postMultipart(appUrl("/v1/imports"), parts,
            "Authorization", "Bearer " + session.accessToken());
    }

    /** Uploads a CSV and asserts it was accepted, returning the preview. */
    protected JsonNode uploadCsv(Session session, String content, String accountId) {
        HttpResponse<String> response = upload(session, content.getBytes(StandardCharsets.UTF_8),
            "statement.csv", "text/csv", accountId);
        assertThat(response.statusCode())
            .as("uploading a CSV failed: %s", response.body())
            .isEqualTo(201);
        return Json.data(response.body());
    }

    /** Uploads an OFX file and asserts it was accepted, returning the preview. */
    protected JsonNode uploadOfx(Session session, byte[] content, String accountId) {
        HttpResponse<String> response =
            upload(session, content, "statement.ofx", "application/x-ofx", accountId);
        assertThat(response.statusCode())
            .as("uploading an OFX file failed: %s", response.body())
            .isEqualTo(201);
        return Json.data(response.body());
    }

    /** Re-reads a batch's preview. */
    protected JsonNode previewOf(Session session, String batchId) {
        HttpResponse<String> response =
            get(appUrl("/v1/imports/" + batchId + "/preview"), session.accessToken());
        assertThat(response.statusCode())
            .as("reading the preview failed: %s", response.body())
            .isEqualTo(200);
        return Json.data(response.body());
    }

    /**
     * Commits a batch and asserts the run was recorded, returning the sync log.
     *
     * <p>{@code null} sends a zero-length body with a JSON content type — what an axios
     * {@code post(url, null)} puts on the wire, and what the operation's optional request body
     * has to tolerate.
     */
    protected JsonNode commit(Session session, String batchId, String jsonBody) {
        HttpResponse<String> response = postJson(appUrl("/v1/imports/" + batchId + "/commit"),
            jsonBody == null ? "" : jsonBody, "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("committing the import failed: %s", response.body())
            .isEqualTo(200);
        return Json.data(response.body());
    }

    /** Undoes a batch and returns the raw response, so a test can assert a 409. */
    protected HttpResponse<String> undo(Session session, String batchId) {
        return delete(appUrl("/v1/imports/" + batchId), null,
            "Authorization", "Bearer " + session.accessToken());
    }

    /**
     * One checked-in OFX fixture, with its {@code ACCTID} replaced.
     *
     * <p>Every integration test in this suite shares one household, and {@code ACCTID} is what an
     * OFX upload resolves an account by — so two tests uploading the same fixture would share an
     * account and each would see the other's transactions. A unique id per test is what keeps them
     * independent, and it is a genuine substitution in the bytes rather than a mock.
     */
    protected static byte[] ofxFixture(String name, String originalAcctId, String replacement) {
        byte[] content = fixtureBytes(name);
        String text = new String(content, StandardCharsets.ISO_8859_1)
            .replace(originalAcctId, replacement);
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** One checked-in fixture, as raw bytes. */
    protected static byte[] fixtureBytes(String name) {
        try (InputStream in = ImportSupport.class.getResourceAsStream("/ofx/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture /ofx/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
