package com.nexuslink.protocol.ibmmq;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed IBM MQ {@code MQRFH2} (Rules and Formatting Header 2) — the header that carries message
 * properties as a sequence of XML "folders" ({@code usr}, {@code jms}, {@code mcd}, {@code psc}, …)
 * ahead of the message body.
 *
 * <p>{@link #fields()} flattens those folders into dotted keys for display, so
 * {@code <usr><color>red</color></usr>} reads as {@code usr.color = red}.</p>
 *
 * @param format         format of the data that <em>follows</em> this header, e.g. {@code MQSTR}
 * @param encoding       numeric encoding of the following data ({@code MQENC_*})
 * @param codedCharSetId CCSID of the following data, e.g. 1208 for UTF-8
 * @param nameValueCcsid CCSID the folder XML itself is encoded in
 * @param folders        raw folder XML strings, in wire order
 */
public record Rfh2Header(String format,
                         int encoding,
                         int codedCharSetId,
                         int nameValueCcsid,
                         List<String> folders) {

    public Rfh2Header {
        folders = folders == null ? List.of() : List.copyOf(folders);
    }

    /**
     * The folders flattened to dotted {@code folder.path.field → value} entries, in document order.
     *
     * <p>Nesting extends the path ({@code usr.address.city}). Repeated sibling elements are indexed
     * from one ({@code usr.item[1]}, {@code usr.item[2]}); a name that occurs once is never indexed.
     * Element attributes — MQ uses them for datatype hints like {@code dt="i4"} — are not surfaced;
     * every value is its element's text. A folder that is empty or not well-formed XML is skipped.</p>
     */
    public Map<String, String> fields() {
        return flatten(folders);
    }

    /** @see #fields() */
    public static Map<String, String> flatten(List<String> folders) {
        Map<String, String> out = new LinkedHashMap<>();
        if (folders == null) return out;
        DocumentBuilder builder = newSecureBuilder();
        for (String folder : folders) {
            String xml = strip(folder);
            if (xml.isEmpty()) continue;
            Element root;
            try {
                Document doc = builder.parse(new InputSource(new StringReader(xml)));
                root = doc.getDocumentElement();
            } catch (Exception malformed) {
                continue; // a folder we can't read shouldn't sink the rest of the header
            }
            if (root != null) walk(root, root.getNodeName(), out);
            builder.reset();
        }
        return out;
    }

    private static void walk(Element element, String path, Map<String, String> out) {
        List<Element> children = childElements(element);
        if (children.isEmpty()) {
            out.put(path, element.getTextContent());
            return;
        }
        // Index only those names that actually repeat, so the common case stays unindexed.
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Element child : children) totals.merge(child.getNodeName(), 1, Integer::sum);

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Element child : children) {
            String name = child.getNodeName();
            String childPath = path + "." + name;
            if (totals.get(name) > 1) {
                int n = seen.merge(name, 1, Integer::sum);
                childPath += "[" + n + "]";
            }
            walk(child, childPath, out);
        }
    }

    private static List<Element> childElements(Element element) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) elements.add((Element) node);
        }
        return elements;
    }

    /** RFH2 folders are NUL/space padded out to a 4-byte boundary on the wire. */
    private static String strip(String folder) {
        if (folder == null) return "";
        int end = folder.length();
        while (end > 0) {
            char c = folder.charAt(end - 1);
            if (c != '\0' && !Character.isWhitespace(c)) break;
            end--;
        }
        return folder.substring(0, end).stripLeading();
    }

    /**
     * A parser that will not resolve DOCTYPEs or external entities. Folder XML arrives from whatever
     * put the message on the queue, so it is untrusted input.
     */
    private static DocumentBuilder newSecureBuilder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JAXP cannot supply a hardened DocumentBuilder", e);
        }
    }
}
