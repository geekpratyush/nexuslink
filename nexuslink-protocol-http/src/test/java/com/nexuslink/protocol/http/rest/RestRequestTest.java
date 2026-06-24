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
}
