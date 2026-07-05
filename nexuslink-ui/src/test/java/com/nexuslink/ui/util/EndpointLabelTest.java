package com.nexuslink.ui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndpointLabelTest {

    @Test
    void stripsSchemeAndQuery() {
        assertEquals("GET api.example.com/users",
                EndpointLabel.forRest("GET", "https://api.example.com/users?page=2&q=x"));
    }

    @Test
    void differentQueryValuesCollapseToOneLabel() {
        String a = EndpointLabel.forRest("GET", "https://h/search?q=cats");
        String b = EndpointLabel.forRest("GET", "https://h/search?q=dogs");
        assertEquals(a, b);
    }

    @Test
    void keepsPortAndTrimsTrailingSlash() {
        assertEquals("POST localhost:8080/api",
                EndpointLabel.forRest("post", "http://localhost:8080/api/"));
    }

    @Test
    void rootPathIsPreserved() {
        assertEquals("GET example.com/", EndpointLabel.forRest("GET", "https://example.com/"));
    }

    @Test
    void dropsFragment() {
        assertEquals("GET h/page", EndpointLabel.forRest("GET", "https://h/page#section"));
    }

    @Test
    void blankUrlYieldsJustMethod() {
        assertEquals("GET", EndpointLabel.forRest("GET", ""));
        assertEquals("GET", EndpointLabel.forRest("", null));
    }

    @Test
    void methodIsUppercased() {
        assertTrue(EndpointLabel.forRest("delete", "https://h/x").startsWith("DELETE "));
    }

    @Test
    void handlesUrlWithoutScheme() {
        assertEquals("GET h/x", EndpointLabel.forRest("GET", "h/x?a=1"));
    }
}
