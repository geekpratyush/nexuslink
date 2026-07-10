package com.nexuslink.protocol.ibmmq;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure tests for the RFH2 folder parser — no queue manager involved. */
class Rfh2HeaderTest {

    @Test
    void flattensASingleFolder() {
        Map<String, String> fields = Rfh2Header.flatten(List.of("<usr><color>red</color><size>10</size></usr>"));

        assertEquals(Map.of("usr.color", "red", "usr.size", "10"), fields);
    }

    @Test
    void preservesDocumentOrder() {
        Map<String, String> fields = Rfh2Header.flatten(List.of("<usr><b>2</b><a>1</a><c>3</c></usr>"));

        assertIterableEquals(List.of("usr.b", "usr.a", "usr.c"), fields.keySet());
    }

    @Test
    void flattensNestedElementsIntoDottedPaths() {
        Map<String, String> fields = Rfh2Header.flatten(
                List.of("<usr><address><city>Pune</city><zip>411001</zip></address></usr>"));

        assertEquals("Pune", fields.get("usr.address.city"));
        assertEquals("411001", fields.get("usr.address.zip"));
    }

    @Test
    void indexesRepeatedSiblingsFromOneButLeavesUniqueNamesUnindexed() {
        Map<String, String> fields = Rfh2Header.flatten(
                List.of("<usr><item>a</item><item>b</item><only>x</only></usr>"));

        assertEquals("a", fields.get("usr.item[1]"));
        assertEquals("b", fields.get("usr.item[2]"));
        assertEquals("x", fields.get("usr.only"));
        assertNull(fields.get("usr.only[1]"), "a name that occurs once must not be indexed");
    }

    @Test
    void mergesMultipleFoldersKeyedByTheirRootName() {
        Map<String, String> fields = Rfh2Header.flatten(List.of(
                "<mcd><Msd>jms_text</Msd></mcd>",
                "<jms><Dst>queue://DEV.QUEUE.1</Dst></jms>",
                "<usr><color>red</color></usr>"));

        assertEquals("jms_text", fields.get("mcd.Msd"));
        assertEquals("queue://DEV.QUEUE.1", fields.get("jms.Dst"));
        assertEquals("red", fields.get("usr.color"));
    }

    @Test
    void toleratesWirePaddingAndEmptyFolders() {
        // MQ pads each folder out to a 4-byte boundary with spaces, and older putters use NULs.
        Map<String, String> fields = Rfh2Header.flatten(
                List.of("<usr><a>1</a></usr>   ", "<usr><b>2</b></usr>\0\0", "", "   "));

        assertEquals(Map.of("usr.a", "1", "usr.b", "2"), fields);
    }

    @Test
    void ignoresAttributesAndKeepsTheElementText() {
        // MQ writes datatype hints as attributes: <usr><count dt="i4">7</count></usr>
        Map<String, String> fields = Rfh2Header.flatten(List.of("<usr><count dt=\"i4\">7</count></usr>"));

        assertEquals(Map.of("usr.count", "7"), fields);
    }

    @Test
    void emptyElementYieldsEmptyValue() {
        assertEquals(Map.of("usr.blank", ""), Rfh2Header.flatten(List.of("<usr><blank></blank></usr>")));
    }

    @Test
    void skipsMalformedFolderButKeepsTheOthers() {
        Map<String, String> fields = Rfh2Header.flatten(
                List.of("<usr><a>1</a>", "<jms><Dst>q</Dst></jms>"));

        assertEquals(Map.of("jms.Dst", "q"), fields, "an unparseable folder must not sink the rest");
    }

    @Test
    void doesNotResolveExternalEntities() {
        // A folder is untrusted input: whoever put the message chose its bytes.
        String xxe = "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><usr><a>&x;</a></usr>";

        Map<String, String> fields = Rfh2Header.flatten(List.of(xxe));

        assertTrue(fields.isEmpty(), "DOCTYPE must be rejected outright, not expanded");
    }

    @Test
    void nullAndEmptyInputsAreEmptyMaps() {
        assertTrue(Rfh2Header.flatten(null).isEmpty());
        assertTrue(Rfh2Header.flatten(List.of()).isEmpty());
    }

    @Test
    void recordDefensivelyCopiesFoldersAndNullBecomesEmpty() {
        Rfh2Header header = new Rfh2Header("MQSTR", 273, 1208, 1208, null);
        assertEquals(List.of(), header.folders());

        Rfh2Header withFolders = new Rfh2Header("MQSTR", 273, 1208, 1208,
                new java.util.ArrayList<>(List.of("<usr><a>1</a></usr>")));
        assertThrows(UnsupportedOperationException.class, () -> withFolders.folders().add("<x/>"));
    }

    @Test
    void fieldsDelegatesToFlatten() {
        Rfh2Header header = new Rfh2Header("MQSTR", 273, 1208, 1208,
                List.of("<usr><color>red</color></usr>"));

        assertEquals(Map.of("usr.color", "red"), header.fields());
    }
}
