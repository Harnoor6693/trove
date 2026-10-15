package finance.trove.ingestion.ofx;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.ofx.dto.OfxAccount;
import finance.trove.ingestion.ofx.dto.OfxDocument;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses OFX 2.x, the XML format ({@code docs/recovery/inventory/plugins.md} §4).
 *
 * <p><strong>The XXE hardening is kept exactly as it was</strong>, and extended. An OFX file is an
 * attacker-supplied XML document uploaded by an authenticated member, so a DOCTYPE with an
 * external entity would be a file-read primitive against the server:
 * {@code disallow-doctype-decl} refuses the declaration outright, external general and parameter
 * entities are off, entity references are not expanded, and — added here —
 * {@code XMLConstants.FEATURE_SECURE_PROCESSING} plus empty {@code ACCESS_EXTERNAL_DTD} and
 * {@code ACCESS_EXTERNAL_SCHEMA} close the two resolver routes the source configuration left open.
 *
 * <p><strong>A missing {@code ACCTID} is refused</strong>, which is what this parser always did.
 * The SGML parser now agrees with it — see {@link OfxSgmlParser}.
 *
 * <p>A single unreadable {@code STMTTRN} is skipped with a warning rather than abandoning the
 * file, which is the pre-collapse behaviour and the right one: one corrupt line should not cost a
 * member their whole statement. Nothing about the row is logged.
 */
public class OfxXmlParser {

    private static final Logger LOG = LoggerFactory.getLogger(OfxXmlParser.class);

    /**
     * Parses {@code xmlBytes}.
     *
     * @param maxTransactions the per-file cap
     */
    public OfxDocument parse(byte[] xmlBytes, int maxTransactions) {
        Document document = parseXml(xmlBytes);
        String accountId = text(document, "ACCTID");
        if (accountId == null || accountId.isBlank()) {
            throw new BankSourceConnectionException("This OFX file has no ACCTID, so there is no "
                + "way to tell which account its transactions belong to.");
        }
        String bankId = text(document, "BANKID");
        String accountType = text(document, "ACCTTYPE");
        OfxAccount account = new OfxAccount(accountId, bankId != null ? bankId : "",
            accountType != null ? accountType : "CHECKING", text(document, "CURDEF"));

        NodeList nodes = document.getElementsByTagName("STMTTRN");
        if (nodes.getLength() > maxTransactions) {
            throw new BankSourceConnectionException("This OFX file has more than " + maxTransactions
                + " transactions, which is more than this server imports in one file.");
        }

        List<OfxTransaction> transactions = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            OfxTransaction transaction = transaction((Element) nodes.item(i), i);
            if (transaction != null) {
                transactions.add(transaction);
            }
        }
        return new OfxDocument(account, transactions);
    }

    private OfxTransaction transaction(Element element, int index) {
        String fitId = childText(element, "FITID");
        String amount = childText(element, "TRNAMT");
        if (fitId == null || amount == null) {
            return null;
        }
        BigDecimal parsedAmount;
        try {
            parsedAmount = new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            // swallow-ok: one unreadable amount skips one transaction. The index identifies it for
            // an operator; nothing about the row's content is logged.
            LOG.warn("Skipping OFX transaction {}: its TRNAMT is not a number", index);
            return null;
        }
        return new OfxTransaction(fitId, childText(element, "TRNTYPE"),
            OfxSgmlParser.date(childText(element, "DTPOSTED")),
            OfxSgmlParser.date(childText(element, "DTUSER")), parsedAmount,
            childText(element, "NAME"), childText(element, "MEMO"),
            childText(element, "CHECKNUM"), childText(element, "CURSYM"));
    }

    private Document parseXml(byte[] xmlBytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> {
                throw new SAXException("External entities are not resolved");
            });
            return builder.parse(new ByteArrayInputStream(xmlBytes));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            // The class name, never the parser's message: a SAX message quotes the offending
            // markup, which for an uploaded statement is a household's own transaction data.
            throw new BankSourceConnectionException("This file could not be parsed as OFX XML ("
                + e.getClass().getSimpleName() + ").", e);
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private static String childText(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }
}
