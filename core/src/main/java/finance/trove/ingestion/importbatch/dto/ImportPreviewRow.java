package finance.trove.ingestion.importbatch.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed row as the member's own preview shows it, before anything is written.
 *
 * <p>Every field but {@code ordinal} and {@code verdict} is nullable, because a row the parser
 * could not read is still worth showing with the reason it was refused — that is how a member
 * learns that four lines of their statement would be skipped, rather than finding out never.
 *
 * <p>{@code reason} is written for a person and quotes no cell content: it says which field could
 * not be read, not what was in it.
 *
 * @param ordinal      the row's position among the file's data rows, counting from 1
 * @param date         the date as read under the mapping's format
 * @param description  the description column, trimmed
 * @param merchantName a cleaner merchant string where the format supplies one; OFX's {@code NAME}
 * @param amount       the absolute value; {@code type} carries the direction
 * @param currency     the row's own currency where the file states one; null means the account's
 * @param type         the direction, or null when the amount could not be read
 * @param verdict      what committing would do with this row
 * @param reason       why the row is {@code INVALID}; null otherwise
 */
public record ImportPreviewRow(int ordinal, LocalDate date, String description,
                               String merchantName, BigDecimal amount, CurrencyCode currency,
                               TransactionType type, ImportRowVerdict verdict, String reason) {
}
