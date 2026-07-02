package com.nexuslink.ui.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the shared HTTP-verb styling helper (no JavaFX toolkit needed). */
class HttpMethodsTest {

    @Test
    void mapsEachVerbToItsStyleClass() {
        assertEquals("method-get", HttpMethods.styleClass("GET"));
        assertEquals("method-post", HttpMethods.styleClass("post"));   // case-insensitive
        assertEquals("method-put", HttpMethods.styleClass("Put"));
        assertEquals("method-patch", HttpMethods.styleClass("PATCH"));
        assertEquals("method-delete", HttpMethods.styleClass("DELETE"));
        assertEquals("method-head", HttpMethods.styleClass("HEAD"));
        assertEquals("method-options", HttpMethods.styleClass("OPTIONS"));
    }

    @Test
    void unknownOrBlankVerbFallsBackToGet() {
        assertEquals("method-get", HttpMethods.styleClass("WEIRD"));
        assertEquals("method-get", HttpMethods.styleClass(""));
        assertEquals("method-get", HttpMethods.styleClass(null));
    }

    @Test
    void isMethodRecognisesOnlyRealVerbs() {
        assertTrue(HttpMethods.isMethod("get"));
        assertTrue(HttpMethods.isMethod(" DELETE "));
        assertFalse(HttpMethods.isMethod("https://api.example.com"));
        assertFalse(HttpMethods.isMethod("SELECT"));
        assertFalse(HttpMethods.isMethod(null));
    }
}
