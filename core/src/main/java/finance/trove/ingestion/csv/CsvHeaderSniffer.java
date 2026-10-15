package finance.trove.ingestion.csv;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.CsvColumnMapping;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Works out how to read one CSV export: its encoding, its delimiter, and which column holds which
 * field ({@code docs/recovery/inventory/plugins.md} §8.2;
 * {@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <h2>The synonym table</h2>
 * §8.2's table, matched case-insensitively against a normalised header (punctuation collapsed to
 * spaces), first match wins. Every pattern runs through {@link BoundedRegex}, so a crafted header
 * cannot pin the request thread — it simply fails to be recognised and the member maps that column
 * by hand.
 *
 * <h2>What is deliberately not guessed</h2>
 * A <strong>balance</strong> column is recognised precisely so that it is never mistaken for an
 * amount, and its values are then thrown away: a running balance is not a transaction.
 * A <strong>currency</strong> column is optional, and when there is none the row's currency is the
 * account's — never a literal, which is finding F4's whole shape.
 *
 * <h2>The header must be mappable</h2>
 * A file with no recognisable date column, or with neither an amount column nor a
 * debit/credit pair, is refused rather than imported half-read. §8.2 says as much: sniffing alone
 * will not cover every bank, so the honest answer is to tell the member the header could not be
 * mapped rather than to import a column of running balances as though it were spending.
 */
@Component
public class CsvHeaderSniffer {

    /** The separators worth trying, in the order a tie is broken. */
    static final char[] DELIMITERS = {',', ';', '\t', '|'};

    private static final Pattern DATE = compile("(transaction|posted|posting|trans|value|entry)?"
        + "\\s*date(\\s+.*)?");

    private static final Pattern DESCRIPTION =
        compile("(description|memo|payee|merchant|details|narrative|particulars|name)(\\s+.*)?");

    private static final Pattern AMOUNT =
        compile("(transaction\\s+)?amount(\\s+.*)?|value|net\\s+amount");

    private static final Pattern DEBIT = compile("(debit|withdrawal|withdrawals|money\\s+out|"
        + "paid\\s+out|charges?)(\\s+.*)?");

    private static final Pattern CREDIT = compile("(credit|deposit|deposits|money\\s+in|"
        + "paid\\s+in)(\\s+.*)?");

    private static final Pattern CURRENCY = compile("(currency|ccy|iso\\s*currency(\\s+code)?)");

    private static final Pattern BALANCE = compile("((running|closing|account)\\s+)?balance"
        + "(\\s+.*)?");

    /** The UTF-8 byte-order mark, which Excel writes and which must not become part of a header. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * Decodes {@code content}, consuming a UTF-8 byte-order mark if there is one.
     *
     * <p>The BOM matters: left in place it becomes the first character of the first header name,
     * so {@code Date} is read as {@code ﻿Date}, matches nothing, and every Excel-exported
     * file in the world is reported as having an unmappable header.
     *
     * @return the decoded text and the charset name that produced it
     */
    public Decoded decode(byte[] content) {
        byte[] bytes = content;
        if (startsWithBom(content)) {
            bytes = new byte[content.length - UTF8_BOM.length];
            System.arraycopy(content, UTF8_BOM.length, bytes, 0, bytes.length);
        }
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Decoded(utf8, StandardCharsets.UTF_8.name());
        }
        // Not valid UTF-8. Windows-1252 is what a Canadian or American bank's Excel export is when
        // it is not UTF-8, and every byte is a valid character in it, so this cannot fail.
        Charset fallback = Charset.forName("windows-1252");
        return new Decoded(new String(bytes, fallback), fallback.name());
    }

    /** Whichever separator appears most often outside quotes on the first non-empty line. */
    public char detectDelimiter(String text) {
        String line = firstNonEmptyLine(text);
        char best = DELIMITERS[0];
        int bestCount = 0;
        for (char candidate : DELIMITERS) {
            int count = countOutsideQuotes(line, candidate);
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Maps a header row onto the fields the ledger needs.
     *
     * @param header  the header cells, in file order
     * @param charset the charset name {@link #decode} settled on
     * @param delimiter the separator {@link #detectDelimiter} settled on
     * @return the mapping, with {@code dateFormat} still {@code null} — the format is decided from
     *         the DATA, not from the header
     * @throws BankSourceConnectionException if the header cannot be mapped at all
     */
    public CsvColumnMapping map(List<String> header, String charset, char delimiter) {
        String date = firstMatch(header, DATE);
        String description = firstMatch(header, DESCRIPTION);
        String balance = firstMatch(header, BALANCE);
        String amount = firstMatchExcluding(header, AMOUNT, balance);
        String debit = firstMatch(header, DEBIT);
        String credit = firstMatch(header, CREDIT);
        String currency = firstMatch(header, CURRENCY);

        if (amount != null) {
            // A single signed column wins over a pair: a file that has both is one where "Amount"
            // is the real figure and "Debit"/"Credit" are a duplicated presentation of it, and
            // adding the two shapes together would double every row.
            debit = null;
            credit = null;
        }
        if (date == null) {
            throw new BankSourceConnectionException("This file's header has no column that looks "
                + "like a transaction date, so its rows cannot be dated. Map the columns by hand.");
        }
        if (amount == null && debit == null && credit == null) {
            throw new BankSourceConnectionException("This file's header has no amount column and "
                + "no debit/credit pair, so its rows carry no money. Map the columns by hand.");
        }
        return new CsvColumnMapping(date, description, amount, debit, credit, currency, balance,
            null, String.valueOf(delimiter), charset, true);
    }

    /** The decoded text and the charset that produced it. */
    public record Decoded(String text, String charset) {
    }

    /**
     * The header text as the synonym table sees it: lowercase, punctuation and underscores
     * collapsed to single spaces, trimmed. {@code "Transaction_Date"} and {@code "TRANSACTION
     * DATE"} are the same column name to a bank and have to be the same string here.
     */
    static String normalise(String header) {
        if (header == null) {
            return "";
        }
        String lower = header.toLowerCase(Locale.ROOT).replace('﻿', ' ');
        StringBuilder text = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            text.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static String firstMatch(List<String> header, Pattern pattern) {
        for (String cell : header) {
            if (BoundedRegex.matches(pattern, normalise(cell))) {
                return cell;
            }
        }
        return null;
    }

    private static String firstMatchExcluding(List<String> header, Pattern pattern,
                                              String excluded) {
        for (String cell : header) {
            if (!cell.equals(excluded) && BoundedRegex.matches(pattern, normalise(cell))) {
                return cell;
            }
        }
        return null;
    }

    private static boolean startsWithBom(byte[] content) {
        if (content.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (content[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            // swallow-ok: "these bytes are not this charset" is the question being asked, and the
            // caller's next move is the fallback charset rather than a failure.
            return null;
        }
    }

    private static String firstNonEmptyLine(String text) {
        for (String line : text.split("\\R", 64)) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    private static Pattern compile(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
