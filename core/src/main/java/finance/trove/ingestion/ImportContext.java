package finance.trove.ingestion;

import java.util.UUID;

/**
 * What a {@link FileImportSource} needs to know about the destination while it reads a file.
 *
 * <p>One field, and it is nullable, because the two formats learn the account at different
 * moments. A CSV file says nothing at all about which account its rows belong to, so the member
 * names it on the upload and it is resolved before the file is read — and the id is then part of
 * every row's deduplication hash, which is what makes re-importing the same file idempotent. An
 * OFX file names its own account in {@code ACCTID}, so it is read first and the account is
 * resolved from what the file said.
 *
 * @param accountId the {@code connected_account} the rows will be written to, or {@code null} when
 *                  the file itself is what decides
 */
public record ImportContext(UUID accountId) {
}
