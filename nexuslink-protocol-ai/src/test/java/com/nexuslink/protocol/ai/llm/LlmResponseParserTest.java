package com.nexuslink.protocol.ai.llm;

import com.nexuslink.protocol.ai.llm.LlmEndpointConfig.Api;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmResponseParserTest {

    @Test
    void parsesAnthropicTextAndUsage() {
        String body = """
                {"content":[{"type":"text","text":"hello there"}],
                 "stop_reason":"end_turn",
                 "usage":{"input_tokens":12,"output_tokens":34}}""";
        var r = LlmResponseParser.parse(Api.ANTHROPIC, body, 250);

        assertTrue(r.success());
        assertEquals("hello there", r.text());
        assertEquals(12, r.inputTokens());
        assertEquals(34, r.outputTokens());
        assertEquals("end_turn", r.stopReason());
        assertEquals(250, r.durationMs());
    }

    @Test
    void thinkingBlocksAreExcludedFromTheAnswerText() {
        String body = """
                {"content":[{"type":"thinking","thinking":"deliberating"},
                            {"type":"text","text":"the answer"}],
                 "usage":{"input_tokens":1,"output_tokens":2}}""";
        assertEquals("the answer", LlmResponseParser.parse(Api.ANTHROPIC, body, 0).text());
    }

    @Test
    void concatenatesMultipleAnthropicTextBlocks() {
        String body = """
                {"content":[{"type":"text","text":"part one "},{"type":"text","text":"part two"}]}""";
        assertEquals("part one part two", LlmResponseParser.parse(Api.ANTHROPIC, body, 0).text());
    }

    @Test
    void parsesOpenAiChoicesAndUsage() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":7}}""";
        var r = LlmResponseParser.parse(Api.OPENAI, body, 10);

        assertTrue(r.success());
        assertEquals("hi", r.text());
        assertEquals(5, r.inputTokens());
        assertEquals(7, r.outputTokens());
        assertEquals("stop", r.stopReason());
    }

    @Test
    void aMissingUsageBlockYieldsZerosRatherThanFailing() {
        var r = LlmResponseParser.parse(Api.ANTHROPIC, """
                {"content":[{"type":"text","text":"ok"}]}""", 0);
        assertTrue(r.success());
        assertEquals(0, r.inputTokens());
        assertEquals("", r.stopReason());
    }

    @Test
    void unparseableBodyIsReportedAsAFailure() {
        var r = LlmResponseParser.parse(Api.ANTHROPIC, "<html>gateway error</html>", 5);
        assertFalse(r.success());
        assertTrue(r.error().contains("parse"), r.error());
    }

    @Test
    void errorMessagesSurfaceTheVendorDetail() {
        String message = LlmResponseParser.errorMessage(400,
                """
                {"error":{"type":"invalid_request_error","message":"max_tokens too large"}}""");
        assertTrue(message.startsWith("HTTP 400"), message);
        assertTrue(message.contains("max_tokens too large"), message);
    }

    @Test
    void authFailuresGetAnOidcHint() {
        String message = LlmResponseParser.errorMessage(401, "{}");
        assertTrue(message.contains("OIDC"), message);
    }

    @Test
    void notFoundHintsAtTheGatewayPath() {
        assertTrue(LlmResponseParser.errorMessage(404, "").contains("/v1/messages"));
    }

    @Test
    void aNonJsonErrorBodyIsShownRawAndTruncated() {
        String message = LlmResponseParser.errorMessage(502, "x".repeat(600));
        assertTrue(message.endsWith("…"), "long raw bodies are truncated");
        assertTrue(message.length() < 500);
    }
}
