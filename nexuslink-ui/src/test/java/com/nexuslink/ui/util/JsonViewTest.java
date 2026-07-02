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
}
