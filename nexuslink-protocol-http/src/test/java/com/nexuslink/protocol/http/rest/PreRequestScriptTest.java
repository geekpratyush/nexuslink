package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link PreRequestScript} DSL parser/evaluator: literals and concatenation, the
 * built-in functions (with deterministic format assertions for the nondeterministic ones), variable
 * chaining and {@code ${env}} resolution, and error collection for bad input. The HMAC path is
 * pinned to an RFC&nbsp;4231 known-answer vector.
 */
class PreRequestScriptTest {

    private static PreRequestScript.Result run(String script) {
        return PreRequestScript.run(script, null);
    }

    @Test
    void emptyOrNullScriptIsCleanAndEmpty() {
        assertTrue(run(null).variables().isEmpty());
        assertFalse(run(null).hasErrors());
        assertTrue(run("   \n\n  ").variables().isEmpty());
        assertFalse(run("   \n\n  ").hasErrors());
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        PreRequestScript.Result r = run("""
                # a comment
                // another comment

                set A = 'x'
                """);
        assertFalse(r.hasErrors());
        assertEquals(Map.of("A", "x"), r.variables());
    }

    @Test
    void stringLiteralsAndConcatenation() {
        PreRequestScript.Result r = run("set GREETING = 'Hello, ' + \"world\" + '!'");
        assertFalse(r.hasErrors());
        assertEquals("Hello, world!", r.variables().get("GREETING"));
    }

    @Test
    void escapesInsideStringLiterals() {
        PreRequestScript.Result r = run("set S = 'a\\tb\\nc' + \"quote:\\\"end\"");
        assertFalse(r.hasErrors());
        assertEquals("a\tb\nc" + "quote:\"end", r.variables().get("S"));
    }

    @Test
    void nowIsEpochMillisDigits() {
        PreRequestScript.Result r = run("set TS = now()");
        assertFalse(r.hasErrors());
        String ts = r.variables().get("TS");
        assertTrue(ts.matches("\\d{13,}"), "expected epoch millis, got: " + ts);
    }

    @Test
    void isoNowLooksLikeAnInstant() {
        PreRequestScript.Result r = run("set STAMP = isoNow()");
        assertFalse(r.hasErrors());
        String stamp = r.variables().get("STAMP");
        assertTrue(stamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z"),
                "expected ISO-8601 UTC instant, got: " + stamp);
    }

    @Test
    void uuidHasCanonicalShape() {
        PreRequestScript.Result r = run("set ID = uuid()");
        assertFalse(r.hasErrors());
        assertTrue(r.variables().get("ID")
                        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "not a UUID: " + r.variables().get("ID"));
    }

    @Test
    void base64EncodesUtf8() {
        PreRequestScript.Result r = run("set B = base64('user:pass')");
        assertFalse(r.hasErrors());
        assertEquals("dXNlcjpwYXNz", r.variables().get("B"));
    }

    /** RFC 4231 Test Case 1: key = 0x0b×20 (hex), data = "Hi There". */
    @Test
    void hmacSha256MatchesRfc4231VectorWithHexKey() {
        PreRequestScript.Result r = run(
                "set SIG = hmacSha256('0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b', 'Hi There')");
        assertFalse(r.hasErrors());
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                r.variables().get("SIG"));
    }

    /** RFC 4231 Test Case 2: key = "Jefe" (text, not hex), data = "what do ya want for nothing?". */
    @Test
    void hmacSha256TreatsNonHexKeyAsText() {
        PreRequestScript.Result r = run(
                "set SIG = hmacSha256('Jefe', 'what do ya want for nothing?')");
        assertFalse(r.hasErrors());
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                r.variables().get("SIG"));
    }

    @Test
    void randomIntStaysWithinInclusiveRange() {
        for (int i = 0; i < 200; i++) {
            PreRequestScript.Result r = run("set N = randomInt(5, 7)");
            assertFalse(r.hasErrors());
            int n = Integer.parseInt(r.variables().get("N"));
            assertTrue(n >= 5 && n <= 7, "out of range: " + n);
        }
    }

    @Test
    void randomIntSingletonRangeIsExact() {
        PreRequestScript.Result r = run("set N = randomInt(42, 42)");
        assertFalse(r.hasErrors());
        assertEquals("42", r.variables().get("N"));
    }

    @Test
    void laterVariablesReferenceEarlierOnes() {
        PreRequestScript.Result r = run("""
                set A = 'foo'
                set B = ${A} + 'bar'
                """);
        assertFalse(r.hasErrors());
        assertEquals("foo", r.variables().get("A"));
        assertEquals("foobar", r.variables().get("B"));
    }

    @Test
    void envReferencesResolveThroughBaseResolver() {
        Function<String, String> env = name -> "SECRET".equals(name) ? "s3cr3t" : null;
        PreRequestScript.Result r = PreRequestScript.run(
                "set TOKEN = 'Bearer ' + ${SECRET}", env);
        assertFalse(r.hasErrors());
        assertEquals("Bearer s3cr3t", r.variables().get("TOKEN"));
    }

    @Test
    void unknownVarReferenceResolvesToEmpty() {
        PreRequestScript.Result r = run("set X = 'a' + ${MISSING} + 'b'");
        assertFalse(r.hasErrors());
        assertEquals("ab", r.variables().get("X"));
    }

    @Test
    void hmacCanCombineEnvKeyAndComputedMessage() {
        Function<String, String> env = name -> "API_SECRET".equals(name) ? "topsecret" : null;
        PreRequestScript.Result r = PreRequestScript.run("""
                set MSG = 'GET' + '/orders'
                set SIG = hmacSha256(${API_SECRET}, ${MSG})
                """, env);
        assertFalse(r.hasErrors());
        assertTrue(r.variables().get("SIG").matches("[0-9a-f]{64}"));
    }

    @Test
    void unknownFunctionIsReportedNotThrown() {
        PreRequestScript.Result r = run("set X = frobnicate('y')");
        assertTrue(r.hasErrors());
        assertTrue(r.errors().get(0).contains("unknown function 'frobnicate'"));
        assertTrue(r.variables().isEmpty());
    }

    @Test
    void wrongArgumentCountIsReported() {
        PreRequestScript.Result r = run("set X = uuid('extra')");
        assertTrue(r.hasErrors());
        assertTrue(r.errors().get(0).contains("uuid() expects 0 arguments"));
    }

    @Test
    void missingSetKeywordIsReported() {
        PreRequestScript.Result r = run("X = 'y'");
        assertTrue(r.hasErrors());
        assertTrue(r.errors().get(0).contains("expected 'set NAME = <expr>'"));
    }

    @Test
    void malformedExpressionIsReportedPerLineAndGoodLinesSurvive() {
        PreRequestScript.Result r = run("""
                set GOOD = 'ok'
                set BAD = 'unterminated
                set ALSO_GOOD = uuid()
                """);
        assertTrue(r.hasErrors());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).startsWith("Line 2:"), r.errors().get(0));
        assertEquals("ok", r.variables().get("GOOD"));
        assertTrue(r.variables().containsKey("ALSO_GOOD"));
    }

    @Test
    void invalidVariableNameIsReported() {
        PreRequestScript.Result r = run("set 9bad = 'x'");
        assertTrue(r.hasErrors());
        assertTrue(r.errors().get(0).contains("invalid variable name"));
    }

    @Test
    void trailingTokensAfterExpressionAreReported() {
        PreRequestScript.Result r = run("set X = 'a' 'b'");
        assertTrue(r.hasErrors());
        assertTrue(r.errors().get(0).toLowerCase().contains("unexpected"));
    }
}
