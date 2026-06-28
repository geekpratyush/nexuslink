package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Pure, dependency-light formatting of an HTTP response body for the viewer:
 * JSON / XML pretty-printing and a classic hex dump. Every method is total —
 * malformed input falls back to the original text rather than throwing — so the
 * UI can call it directly while rendering a response.
 */
public final class BodyFormatter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BodyFormatter() {
    }

    /** The viewer's render modes. */
    public enum Mode { PRETTY, RAW, HEX }

    /**
     * Renders {@code body} in {@code mode}. PRETTY auto-detects JSON or XML from
     * the content type (and a content sniff) and indents it; anything else is
     * returned unchanged.
     */
    public static String render(String body, String contentType, Mode mode) {
        if (body == null) return "";
        return switch (mode) {
            case RAW -> body;
            case HEX -> hexDump(body.getBytes(StandardCharsets.UTF_8));
            case PRETTY -> pretty(body, contentType);
        };
    }

    /** Auto pretty-print: JSON or XML when detected, else the original body. */
    public static String pretty(String body, String contentType) {
        if (body == null || body.isBlank()) return body == null ? "" : body;
        if (isJson(contentType, body)) return prettyJson(body);
        if (isXml(contentType, body)) return prettyXml(body);
        return body;
    }

    public static boolean isJson(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().contains("json")) return true;
        if (contentType != null && contentType.toLowerCase().contains("xml")) return false;
        String t = body == null ? "" : body.stripLeading();
        return t.startsWith("{") || t.startsWith("[");
    }

    public static boolean isXml(String contentType, String body) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("xml")) return true;
            if (ct.contains("json")) return false;
        }
        String t = body == null ? "" : body.stripLeading();
        return t.startsWith("<");
    }

    /** Pretty-prints JSON; returns the input unchanged if it does not parse. */
    public static String prettyJson(String body) {
        try {
            Object tree = JSON.readValue(body, Object.class);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception e) {
            return body;
        }
    }

    /** Pretty-prints XML with 2-space indentation; returns the input unchanged on any error. */
    public static String prettyXml(String body) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Harden against XXE — this is a viewer, never resolve external entities.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setExpandEntityReferences(false);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

            // Drop whitespace-only text nodes so the transformer can indent cleanly.
            NodeList blanks = (NodeList) XPathFactory.newInstance().newXPath()
                    .evaluate("//text()[normalize-space()='']", doc, XPathConstants.NODESET);
            for (int i = 0; i < blanks.getLength(); i++) {
                Node n = blanks.item(i);
                n.getParentNode().removeChild(n);
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString().trim();
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * Classic hex dump: {@code offset  16 hex bytes  |ascii|}, 16 bytes per row.
     * Non-printable bytes render as {@code .} in the ASCII gutter.
     */
    public static String hexDump(byte[] data) {
        if (data == null || data.length == 0) return "(empty body)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%08X  ", i));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < data.length) {
                    int b = data[i + j] & 0xFF;
                    sb.append(String.format("%02X ", b));
                    ascii.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(' '); // gutter between the two 8-byte halves
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }
}
