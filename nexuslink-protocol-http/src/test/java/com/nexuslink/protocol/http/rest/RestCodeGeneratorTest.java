package com.nexuslink.protocol.http.rest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestCodeGeneratorTest {

    /**
     * Representative POST with a JSON body plus a custom header whose value contains a
     * double quote, and a body containing {@code '}, {@code "}, {@code #} and {@code $}
     * so each language's escaping can be asserted.
     */
    private static RestRequest sample() {
        RestRequest r = new RestRequest();
        r.setMethod("POST");
        r.setUrl("https://api.example.com/v1/items");
        r.getHeaders().add(new RestRequest.KeyValue("X-Custom", "a\"b"));
        r.setBodyType(RestRequest.BodyType.JSON);
        r.setBody("{\"name\":\"O'Brien\",\"tag\":\"#1\",\"price\":\"$9\"}");
        return r;
    }

    @Test
    void everyLanguageProducesNonBlankOutput() {
        RestRequest r = sample();
        for (RestCodeGenerator.Language lang : RestCodeGenerator.Language.values()) {
            String out = RestCodeGenerator.generate(lang, r);
            assertNotNull(out, lang + " produced null");
            assertFalse(out.isBlank(), lang + " produced blank output");
        }
    }

    @Test
    void languagesIncludesAllNewTargets() {
        var langs = RestCodeGenerator.languages();
        assertTrue(langs.contains(RestCodeGenerator.Language.NODE_AXIOS));
        assertTrue(langs.contains(RestCodeGenerator.Language.CSHARP));
        assertTrue(langs.contains(RestCodeGenerator.Language.GO));
        assertTrue(langs.contains(RestCodeGenerator.Language.RUST));
        assertTrue(langs.contains(RestCodeGenerator.Language.PHP));
        assertTrue(langs.contains(RestCodeGenerator.Language.RUBY));
        for (RestCodeGenerator.Language l : RestCodeGenerator.Language.values()) {
            assertFalse(l.label().isBlank(), l + " has a blank label");
        }
    }

    @Test
    void nodeAxiosSnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.NODE_AXIOS, sample());
        assertTrue(out.contains("require('axios')"));
        assertTrue(out.contains("await axios({"));
        assertTrue(out.contains("method: \"POST\""));
        assertTrue(out.contains("url: \"https://api.example.com/v1/items\""));
        assertTrue(out.contains("\"Content-Type\": \"application/json\""));
        assertTrue(out.contains("data: "));
        // double quotes inside header value and body are backslash-escaped
        assertTrue(out.contains("\"X-Custom\": \"a\\\"b\""));
        assertTrue(out.contains("\\\"name\\\""));
    }

    @Test
    void csharpSnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.CSHARP, sample());
        assertTrue(out.contains("using System.Net.Http;"));
        assertTrue(out.contains("new HttpClient()"));
        assertTrue(out.contains("new HttpRequestMessage(new HttpMethod(\"POST\"), "
                + "\"https://api.example.com/v1/items\")"));
        // Content-Type must travel on the content, not the request headers
        assertFalse(out.contains("TryAddWithoutValidation(\"Content-Type\""));
        assertTrue(out.contains("request.Headers.TryAddWithoutValidation(\"X-Custom\", \"a\\\"b\")"));
        assertTrue(out.contains("new StringContent(") && out.contains("Encoding.UTF8, \"application/json\""));
        assertTrue(out.contains("await client.SendAsync(request)"));
    }

    @Test
    void goSnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.GO, sample());
        assertTrue(out.contains("package main"));
        assertTrue(out.contains("\"net/http\""));
        assertTrue(out.contains("\"strings\""));
        assertTrue(out.contains("strings.NewReader(\""));
        assertTrue(out.contains("http.NewRequest(\"POST\", \"https://api.example.com/v1/items\", body)"));
        assertTrue(out.contains("req.Header.Set(\"X-Custom\", \"a\\\"b\")"));
        assertTrue(out.contains("http.DefaultClient.Do(req)"));
        assertTrue(out.contains("\\\"name\\\""));
    }

    @Test
    void goSnippetWithoutBodyOmitsStringsImport() {
        RestRequest r = new RestRequest();
        r.setMethod("GET");
        r.setUrl("https://api.example.com/ping");
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.GO, r);
        assertFalse(out.contains("\"strings\""));
        assertTrue(out.contains("http.NewRequest(\"GET\", \"https://api.example.com/ping\", nil)"));
    }

    @Test
    void rustSnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.RUST, sample());
        assertTrue(out.contains("use reqwest::header::{HeaderMap, HeaderValue};"));
        assertTrue(out.contains("#[tokio::main]"));
        assertTrue(out.contains("reqwest::Client::new()"));
        assertTrue(out.contains("reqwest::Method::POST, \"https://api.example.com/v1/items\""));
        assertTrue(out.contains("headers.insert(\"X-Custom\", HeaderValue::from_static(\"a\\\"b\"))"));
        assertTrue(out.contains(".body(\""));
        assertTrue(out.contains(".send()"));
        assertTrue(out.contains(".await?;"));
    }

    @Test
    void phpSnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.PHP, sample());
        assertTrue(out.contains("<?php"));
        assertTrue(out.contains("curl_init()"));
        assertTrue(out.contains("CURLOPT_CUSTOMREQUEST, \"POST\""));
        assertTrue(out.contains("CURLOPT_URL, \"https://api.example.com/v1/items\""));
        assertTrue(out.contains("CURLOPT_POSTFIELDS, "));
        // header rendered as a single "Key: Value" string with escaped quote
        assertTrue(out.contains("\"X-Custom: a\\\"b\""));
        assertTrue(out.contains("\"Content-Type: application/json\""));
        // PHP double-quoted strings must escape the dollar sign
        assertTrue(out.contains("\\$9"));
    }

    @Test
    void rubySnippet() {
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.RUBY, sample());
        assertTrue(out.contains("require 'net/http'"));
        assertTrue(out.contains("uri = URI(\"https://api.example.com/v1/items\")"));
        assertTrue(out.contains("http.use_ssl = uri.scheme == \"https\""));
        // POST -> Net::HTTP::Post
        assertTrue(out.contains("Net::HTTP::Post.new(uri)"));
        assertTrue(out.contains("request[\"X-Custom\"] = \"a\\\"b\""));
        assertTrue(out.contains("request.body = "));
        // '#' must be escaped to avoid Ruby string interpolation
        assertTrue(out.contains("\\#1"));
        assertTrue(out.contains("puts response.code"));
    }

    @Test
    void rubyMethodIsTitleCased() {
        RestRequest r = sample();
        r.setMethod("delete");
        String out = RestCodeGenerator.generate(RestCodeGenerator.Language.RUBY, r);
        assertTrue(out.contains("Net::HTTP::Delete.new(uri)"));
    }
}
