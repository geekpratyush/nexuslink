package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class RestRequestJsonTest {

    private static final String STORED = """
            {"method":"POST","url":"https://${host}/v1/login","bodyType":"JSON",
             "body":"{\\"user\\":\\"${user}\\"}",
             "params":[{"enabled":true,"key":"debug","value":"1"}],
             "headers":[{"enabled":true,"key":"Authorization","value":"Bearer ${token}"},
                        {"enabled":false,"key":"X-Off","value":"no"}],
             "authType":"BEARER","authToken":"${token}",
             "assertions":[{"enabled":true,"type":"STATUS_EQUALS","name":"","target":"200","max":""}],
             "extractions":"token = json_path: /data/token"}""";

    @Test
    void aStoredRequestComesBackWithEveryPart() {
        RestRequest request = RestRequestJson.parse(STORED, UnaryOperator.identity());
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals(RestRequest.BodyType.JSON, request.getBodyType());
        assertEquals(1, request.getQueryParams().size());
        assertEquals(2, request.getHeaders().size());
        assertFalse(request.getHeaders().get(1).isEnabled(), "a disabled header stays disabled");
        assertEquals(RestRequest.AuthType.BEARER, request.getAuthType());
        assertEquals(1, request.getAssertions().size());
        assertEquals("200", request.getAssertions().get(0).getTarget());
    }

    @Test
    void substitutionReachesTheUrlHeadersAndBody() {
        var substitute = CollectionRunner.substitution(
                Map.of("host", "api.test", "user", "ada", "token", "abc123"));
        RestRequest request = RestRequestJson.parse(STORED, substitute);
        assertEquals("https://api.test/v1/login", request.getUrl());
        assertEquals("{\"user\":\"ada\"}", request.getBody());
        assertEquals("Bearer abc123", request.getHeaders().get(0).getValue());
        assertEquals("abc123", request.getAuthToken());
    }

    @Test
    void withoutSubstitutionTheReferencesAreKept() {
        assertEquals("https://${host}/v1/login",
                RestRequestJson.parse(STORED, UnaryOperator.identity()).getUrl());
    }

    @Test
    void theStoredExtractionRulesComeBack() {
        var rules = RestRequestJson.extractionsOf(STORED);
        assertEquals(1, rules.size());
        assertEquals("token", rules.get(0).variable());
        assertEquals(ResponseExtraction.Source.JSON_PATH, rules.get(0).source());
        assertTrue(RestRequestJson.extractionsOf("{}").isEmpty());
    }

    @Test
    void anUnknownEnumFallsBackRatherThanFailingTheWholeRequest() {
        RestRequest request = RestRequestJson.parse(
                "{\"method\":\"GET\",\"bodyType\":\"PROTOBUF\",\"authType\":\"KERBEROS\"}",
                UnaryOperator.identity());
        assertNotNull(request);
        assertEquals(RestRequest.BodyType.NONE, request.getBodyType());
        assertEquals(RestRequest.AuthType.NONE, request.getAuthType());
    }

    @Test
    void unreadableJsonIsNullRatherThanAnException() {
        assertNull(RestRequestJson.parse("not json", UnaryOperator.identity()));
        assertNull(RestRequestJson.parse("", UnaryOperator.identity()));
        assertNull(RestRequestJson.parse(null, UnaryOperator.identity()));
    }

    @Test
    void describeNamesTheMethodAndUrl() {
        assertEquals("POST https://${host}/v1/login", RestRequestJson.describe(STORED));
        assertEquals("(unreadable request)", RestRequestJson.describe("nope"));
    }
}
