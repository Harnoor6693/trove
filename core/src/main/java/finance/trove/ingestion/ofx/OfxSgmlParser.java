package finance.trove.ingestion.ofx;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.Amounts;
import finance.trove.ingestion.ofx.dto.OfxAccount;
import finance.trove.ingestion.ofx.dto.OfxDocument;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OFX 1.x, the legacy SGML format ({@code docs/recovery/inventory/plugins.md} §4).
 *
 * <p>OFX 1.x is not valid XML — tags are not closed — so no XML parser can read it. This is the
 * pre-collapse plugin's substring-and-regex reader, with two defects fixed.
 *
 * <h2>Fix 1: the header's declared encoding is honoured</h2>
 * {@code OfxParser} decoded the whole byte buffer as UTF-8 unconditionally (plugins.md §4.1).
 * Almost every OFX 1.x file a North American bank produces declares {@code CHARSET:1252} and
 * contains windows-1252 bytes, so an accented merchant name — {@code Café}, {@code Dépanneur} —
 * decoded into replacement characters and was stored that way, permanently, with nothing to say it
 * had happened. The {@code ENCODING} and {@code CHARSET} lines of the header are ASCII by
 * specification, so they can be read before the body's charset is known, which is what
 * {@link #charsetOf} does.
 *
 * <h2>Fix 2: a missing ACCTID is refused</h2>
 * The pre-collapse SGML parser defaulted it to the literal {@code "unknown"} while the XML parser
 * threw. Step 7 stores {@code ACCTID} as {@code connected_account.external_account_id}, whose
 * uniqueness key is {@code (household_id, source_kind, external_account_id)} — so a shared literal
 * would make every ACCTID-less upload in a household resolve to ONE account and merge two banks'
 * statements. Both parsers now refuse, which is the answer that cannot corrupt anything.
 */
public class OfxSgmlParser {

    private static final DateTimeFormatter OFX_DATE = DateTimeFormatter.ofPattern("uuuuMMdd");

    /** How many leading bytes the header is read from; the same window the detector uses. */
    private static final int HEADER_BYTES = OfxFormatDetector.HEADER_BYTES;

    private static final Pattern HEADER_LINE =
        Pattern.compile("^\\s*([A-Z]+)\\s*:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /**
     * Decodes and parses {@code content}.
     *
     * @param maxTransactions the per-file cap; the pre-collapse parser's 10,000, now
     *                        {@code ImportLimits.maxRows}
     */
    public OfxDocument parse(byte[] content, int maxTransactions) {
        return parse(new String(content, charsetOf(content)), maxTransactions);
    }

    /**
     * The charset the file's own header declares.
     *
     * <p>{@code ENCODING:UTF-8} settles it. Otherwise {@code CHARSET} decides, because a file that
     * says {@code ENCODING:USASCII} and {@code CHARSET:1252} is a windows-1252 file — that pairing
     * is what nearly every North American bank writes. An unrecognised or absent value falls back
     * to windows-1252, which is a superset of US-ASCII and cannot fail to decode, rather than to
     * UTF-8, which is what mangled the accented names in the first place.
     */
    static Charset charsetOf(byte[] content) {
        int length = Math.min(content.length, HEADER_BYTES);
        String header = new String(content, 0, length, StandardCharsets.US_ASCII);
        String encoding = headerValue(header, "ENCODING");
        String charset = headerValue(header, "CHARSET");
        if (encoding != null && encoding.replace("-", "").equalsIgnoreCase("UTF8")) {
            return StandardCharsets.UTF_8;
        }
        if (charset != null) {
            Charset named = charsetNamed(charset);
            if (named != null) {
                return named;
            }
        }
        return Charset.forName("windows-1252");
    }

    private static Charset charsetNamed(String declared) {
        String value = declared.trim().toUpperCase(Locale.ROOT);
        switch (value) {
            case "1252", "CP1252", "WINDOWS-1252" -> {
                return Charset.forName("windows-1252");
            }
            case "8859-1", "ISO-8859-1", "LATIN1" -> {
                return StandardCharsets.ISO_8859_1;
            }
            case "UTF-8", "UTF8" -> {
                return StandardCharsets.UTF_8;
            }
            case "NONE", "USASCII", "US-ASCII" -> {
                return StandardCharsets.US_ASCII;
            }
            default -> {
                try {
                    return Charset.forName(value);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    // swallow-ok: an unrecognised CHARSET is not a reason to refuse a statement.
                    // The caller falls back to windows-1252, which decodes every byte.
                    return null;
                }
            }
        }
    }

    private static String headerValue(String header, String name) {
        Matcher matcher = HEADER_LINE.matcher(header);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(name)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    /** Parses already-decoded text. */
    public OfxDocument parse(String sgmlContent, int maxTransactions) {
        int bodyStart = sgmlContent.indexOf("<OFX>");
        String body = bodyStart < 0 ? sgmlContent : sgmlContent.substring(bodyStart);
        return new OfxDocument(parseAccount(body), parseTransactions(body, maxTransactions));
    }

    private OfxAccount parseAccount(String body) {
        String accountId = extractTagValue(body, "ACCTID");
        if (accountId == null || accountId.isBlank()) {
            throw new BankSourceConnectionException("This OFX file has no ACCTID, so there is no "
                + "way to tell which account its transactions belong to.");
        }
        String bankId = extractTagValue(body, "BANKID");
        String accountType = extractTagValue(body, "ACCTTYPE");
        return new OfxAccount(accountId, bankId != null ? bankId : "",
            accountType != null ? accountType : "CHECKING", extractTagValue(body, "CURDEF"));
    }

    private List<OfxTransaction> parseTransactions(String body, int maxTransactions) {
        List<OfxTransaction> result = new ArrayList<>();
        String[] blocks = body.split("<STMTTRN>");
        for (int i = 1; i < blocks.length; i++) {
            if (result.size() >= maxTransactions) {
                throw new BankSourceConnectionException("This OFX file has more than "
                    + maxTransactions + " transactions, which is more than this server imports in "
                    + "one file.");
            }
            OfxTransaction transaction = parseTransaction(blocks[i]);
            if (transaction != null) {
                result.add(transaction);
            }
        }
        return result;
    }

    private OfxTransaction parseTransaction(String block) {
        String fitId = extractTagValue(block, "FITID");
        String amount = extractTagValue(block, "TRNAMT");
        if (fitId == null || amount == null) {
            return null;
        }
        BigDecimal parsedAmount = amount(amount);
        if (parsedAmount == null) {
            return null;
        }
        return new OfxTransaction(fitId, extractTagValue(block, "TRNTYPE"),
            date(extractTagValue(block, "DTPOSTED")), date(extractTagValue(block, "DTUSER")),
            parsedAmount, extractTagValue(block, "NAME"), extractTagValue(block, "MEMO"),
            extractTagValue(block, "CHECKNUM"), extractTagValue(block, "CURSYM"));
    }

    /** Through {@link Amounts#parse}: an exponent such as {@code -1E9999999} is not an amount. */
    private static BigDecimal amount(String raw) {
        return Amounts.parse(raw);
    }

    /** OFX dates are {@code yyyyMMdd} with an optional time and zone suffix that is ignored. */
    static LocalDate date(String raw) {
        if (raw == null || raw.trim().length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, 8), OFX_DATE);
        } catch (DateTimeParseException e) {
            // swallow-ok: an unreadable date leaves the field null; the ingestion pipeline then
            // refuses the row with a reason rather than dating it by guesswork.
            return null;
        }
    }

    private String extractTagValue(String text, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + ">([^<\n\r]+)").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
