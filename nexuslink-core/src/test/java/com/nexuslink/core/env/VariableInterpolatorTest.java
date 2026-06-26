package com.nexuslink.core.env;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class VariableInterpolatorTest {

    private static Function<String, String> resolver(Map<String, String> vars) {
        return vars::get;
    }

    @Test
    void substitutesKnownVariables() {
        var r = resolver(Map.of("HOST", "api.example.com", "PORT", "8443"));
        assertEquals("https://api.example.com:8443/v1",
                VariableInterpolator.interpolate("https://${HOST}:${PORT}/v1", r));
    }

    @Test
    void leavesUnknownVariablesAsLiteralsSoTheUserSeesThem() {
        assertEquals("token=${MISSING}",
                VariableInterpolator.interpolate("token=${MISSING}", resolver(Map.of())));
    }

    @Test
    void appliesDefaultWhenUnsetOrEmpty() {
        assertEquals("env=prod",
                VariableInterpolator.interpolate("env=${STAGE:-prod}", resolver(Map.of())));
        assertEquals("env=prod",
                VariableInterpolator.interpolate("env=${STAGE:-prod}", resolver(Map.of("STAGE", ""))));
        assertEquals("env=dev",
                VariableInterpolator.interpolate("env=${STAGE:-prod}", resolver(Map.of("STAGE", "dev"))));
    }

    @Test
    void escapesDoubleDollarToLiteral() {
        assertEquals("price is ${AMOUNT}",
                VariableInterpolator.interpolate("price is $${AMOUNT}", resolver(Map.of("AMOUNT", "5"))));
    }

    @Test
    void resolvesNestedReferences() {
        var r = resolver(Map.of("BASE", "https://${HOST}", "HOST", "example.com"));
        assertEquals("https://example.com/api",
                VariableInterpolator.interpolate("${BASE}/api", r));
    }

    @Test
    void stopsOnReferenceCycleInsteadOfLooping() {
        var r = resolver(Map.of("A", "${B}", "B", "${A}"));
        // Should terminate; we only assert it returns without hanging or throwing.
        assertDoesNotThrow(() -> VariableInterpolator.interpolate("${A}", r));
    }

    @Test
    void passesThroughStringsWithoutPlaceholders() {
        assertEquals("plain text", VariableInterpolator.interpolate("plain text", resolver(Map.of())));
        assertNull(VariableInterpolator.interpolate(null, resolver(Map.of())));
    }
}
