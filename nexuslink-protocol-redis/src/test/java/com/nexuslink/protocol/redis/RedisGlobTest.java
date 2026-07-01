package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RedisGlobTest {

    @Test
    void starMatchesAnyRun() {
        assertTrue(RedisGlob.matches("user:*", "user:1000"));
        assertTrue(RedisGlob.matches("user:*", "user:"));
        assertTrue(RedisGlob.matches("*", ""));
        assertTrue(RedisGlob.matches("*bar", "foobar"));
        assertTrue(RedisGlob.matches("foo*bar", "fooXYZbar"));
        assertFalse(RedisGlob.matches("user:*", "admin:1"));
    }

    @Test
    void questionMarkMatchesSingle() {
        assertTrue(RedisGlob.matches("h?llo", "hello"));
        assertTrue(RedisGlob.matches("h?llo", "hallo"));
        assertFalse(RedisGlob.matches("h?llo", "hllo"));
        assertFalse(RedisGlob.matches("h?llo", "heello"));
    }

    @Test
    void characterClassAndRange() {
        assertTrue(RedisGlob.matches("h[ae]llo", "hello"));
        assertTrue(RedisGlob.matches("h[ae]llo", "hallo"));
        assertFalse(RedisGlob.matches("h[ae]llo", "hillo"));
        assertTrue(RedisGlob.matches("key:[0-9]", "key:7"));
        assertFalse(RedisGlob.matches("key:[0-9]", "key:a"));
    }

    @Test
    void negatedClass() {
        assertTrue(RedisGlob.matches("h[^e]llo", "hallo"));
        assertFalse(RedisGlob.matches("h[^e]llo", "hello"));
    }

    @Test
    void escapes() {
        assertTrue(RedisGlob.matches("a\\*b", "a*b"));
        assertFalse(RedisGlob.matches("a\\*b", "axb"));
        assertTrue(RedisGlob.matches("a\\?b", "a?b"));
    }

    @Test
    void exactAndAnchored() {
        assertTrue(RedisGlob.matches("hello", "hello"));
        assertFalse(RedisGlob.matches("hello", "hello world"));
        assertFalse(RedisGlob.matches("hello", "ohello"));
    }

    @Test
    void multipleStarsCollapse() {
        assertTrue(RedisGlob.matches("a**b", "aXYZb"));
        assertTrue(RedisGlob.matches("**", "anything"));
    }
}
