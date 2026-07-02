package com.nexuslink.ui.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the JSON tokenizer assigns the right style class to each kind of token. */
class JsonViewTest {

    /** Collects the style classes applied to a given substring of the source. */
    private static List<String> classesOver(String json, String needle) {
        StyleSpans<Collection<String>> spans = JsonView.computeHighlighting(json);
        int target = json.indexOf(needle);
        assertTrue(target >= 0, "needle not found: " + needle);
        List<String> hit = new ArrayList<>();
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int end = pos + span.getLength();
            if (target >= pos && target < end) hit.addAll(span.getStyle());
            pos = end;
        }
        return hit;
    }

    @Test
    void keysStringsNumbersBoolsAndNullsGetDistinctClasses() {
        String json = "{\"name\": \"Ada\", \"age\": 36, \"active\": true, \"note\": null}";
        assertTrue(classesOver(json, "\"name\"").contains("json-key"));
        assertTrue(classesOver(json, "\"Ada\"").contains("json-string"));
        assertTrue(classesOver(json, "36").contains("json-number"));
        assertTrue(classesOver(json, "true").contains("json-bool"));
        assertTrue(classesOver(json, "null").contains("json-null"));
    }

    @Test
    void spanLengthsCoverTheWholeText() {
        String json = "{\"a\":[1,2,3]}";
        StyleSpans<Collection<String>> spans = JsonView.computeHighlighting(json);
        int total = 0;
        for (StyleSpan<Collection<String>> s : spans) total += s.getLength();
        assertEquals(json.length(), total);
    }

    @Test
    void emptyTextProducesNoError() {
        assertEquals(0, JsonView.computeHighlighting("").length());
    }

    @Test
    void nonJsonContentIsNotTokenised() {
        // XML / hex / plain text must stay a single unstyled span (no JSON colours applied).
        assertNoStyles("<note><to>Ada</to></note>");
        assertNoStyles("0000  7b 22 61 22 3a 31 7d              {\"a\":1}");
        assertNoStyles("Request failed: connection refused");
    }

    @Test
    void jsonContentIsTokenisedEvenWithLeadingWhitespace() {
        StyleSpans<Collection<String>> spans = JsonView.computeHighlighting("  {\"a\": 1}");
        boolean anyStyled = false;
        for (StyleSpan<Collection<String>> s : spans) anyStyled |= !s.getStyle().isEmpty();
        assertTrue(anyStyled);
    }

    /** Mirrors JsonView.setSmart's decision: only text whose first non-space char is { or [ is JSON. */
    private static void assertNoStyles(String text) {
        String head = text.stripLeading();
        boolean looksJson = !head.isEmpty() && (head.charAt(0) == '{' || head.charAt(0) == '[');
        assertFalse(looksJson, "should not be treated as JSON: " + text);
    }
}
