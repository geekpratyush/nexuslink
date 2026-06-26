package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestRequestTest {

    @Test
    void effectiveUrlAppendsEnabledQueryParams() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/v1/users");
        r.getQueryParams().add(new RestRequest.KeyValue("page", "2"));
        RestRequest.KeyValue disabled = new RestRequest.KeyValue("debug", "true");
        disabled.setEnabled(false);
        r.getQueryParams().add(disabled);

        assertEquals("https://api.example.com/v1/users?page=2", r.effectiveUrl());
    }

    @Test
    void apiKeyInQueryIsFoldedIntoRequestUri() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/data");
        r.setAuthType(RestRequest.AuthType.API_KEY);
        r.setApiKeyName("api_key");
        r.setApiKeyValue("s3cr3t value");
        r.setApiKeyLocation(RestRequest.ApiKeyLocation.QUERY);

        assertEquals("https://api.example.com/data?api_key=s3cr3t+value", r.requestUri());
    }

    @Test
    void apiKeyInHeaderDoesNotTouchTheUri() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/data");
        r.setAuthType(RestRequest.AuthType.API_KEY);
        r.setApiKeyName("X-API-Key");
        r.setApiKeyValue("abc");
        r.setApiKeyLocation(RestRequest.ApiKeyLocation.HEADER);

        assertEquals("https://api.example.com/data", r.requestUri());
    }

    @Test
    void noAuthLeavesUriUnchanged() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/x");
        assertEquals("https://api.example.com/x", r.requestUri());
    }

    @Test
    void curlSnippetIncludesMethodUrlAndBearer() {
        RestRequest r = new RestRequest();
        r.setMethod("post");
        r.setUrl("https://api.example.com/items");
        r.setBodyType(RestRequest.BodyType.JSON);
        r.setBody("{\"a\":1}");
        r.setAuthType(RestRequest.AuthType.BEARER);
        r.setAuthToken("tok123");

        String curl = RestCodeGenerator.generate(RestCodeGenerator.Language.CURL, r);
        assertTrue(curl.contains("curl -X POST"), curl);
        assertTrue(curl.contains("https://api.example.com/items"), curl);
        assertTrue(curl.contains("Authorization: Bearer tok123"), curl);
        assertTrue(curl.contains("Content-Type: application/json"), curl);
        assertTrue(curl.contains("--data"), curl);
    }

    @Test
    void pythonSnippetReferencesRequests() {
        RestRequest r = new RestRequest();
        r.setUrl("https://api.example.com/x");
        String py = RestCodeGenerator.generate(RestCodeGenerator.Language.PYTHON, r);
        assertTrue(py.contains("import requests"), py);
        assertTrue(py.contains("https://api.example.com/x"), py);
    }

    @Test
    void interpolatedResolvesEveryStringFieldWithoutMutatingTheOriginal() {
        RestRequest r = new RestRequest();
        r.setMethod("POST");
        r.setUrl("https://${HOST}/v1/users");
        r.getQueryParams().add(new RestRequest.KeyValue("api-version", "${API_VERSION}"));
        r.getHeaders().add(new RestRequest.KeyValue("X-Tenant", "${TENANT}"));
        r.setBodyType(RestRequest.BodyType.JSON);
        r.setBody("{\"token\":\"${TOKEN}\"}");
        r.setAuthType(RestRequest.AuthType.BEARER);
        r.setAuthToken("${TOKEN}");

        java.util.Map<String, String> vars = java.util.Map.of(
                "HOST", "api.example.com", "API_VERSION", "2024-01",
                "TENANT", "acme", "TOKEN", "tok_123");
        RestRequest out = r.interpolated(s -> {
            for (var e : vars.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
            return s;
        });

        // Resolved copy.
        assertEquals("https://api.example.com/v1/users", out.getUrl());
        assertEquals("2024-01", out.getQueryParams().get(0).getValue());
        assertEquals("acme", out.getHeaders().get(0).getValue());
        assertEquals("{\"token\":\"tok_123\"}", out.getBody());
        assertEquals("tok_123", out.getAuthToken());
        assertEquals("POST", out.getMethod());

        // Original is untouched (so the editor keeps showing templates for history/replay).
        assertEquals("https://${HOST}/v1/users", r.getUrl());
        assertEquals("${TOKEN}", r.getAuthToken());
        assertEquals("${API_VERSION}", r.getQueryParams().get(0).getValue());
    }
}
