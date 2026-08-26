package com.nexuslink.protocol.redis;

import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The console's reply collector, driven the way Lettuce's decoder drives it — {@code multi} to open
 * an aggregate, {@code set} per element, {@code complete} as each level finishes.
 */
class RedisReplyOutputTest {

    private RedisReplyOutput output() { return new RedisReplyOutput(StringCodec.UTF8); }

    private static ByteBuffer buf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aTopLevelBulkStringIsReturnedAsAString() {
        RedisReplyOutput o = output();
        o.set(buf("hello world"));
        assertEquals("hello world", o.get());
    }

    @Test
    void aSimpleStringArrivesThroughSetSingle() {
        RedisReplyOutput o = output();
        o.setSingle(buf("OK"));
        assertEquals("OK", o.get());
    }

    @Test
    void integersDoublesAndBooleansKeepTheirTypes() {
        RedisReplyOutput integer = output();
        integer.set(42L);
        assertEquals(42L, integer.get());

        RedisReplyOutput dbl = output();
        dbl.set(1.5);
        assertEquals(1.5, dbl.get());

        RedisReplyOutput bool = output();
        bool.set(true);
        assertEquals(true, bool.get());
    }

    @Test
    void aNilBulkStringIsNull() {
        RedisReplyOutput o = output();
        o.set((ByteBuffer) null);
        assertNull(o.get());
    }

    @Test
    void anArrayKeepsEveryElementInOrder() {
        RedisReplyOutput o = output();
        o.multi(3);
        o.set(buf("a"));
        o.set(buf("b"));
        o.set(buf("c"));
        o.complete(1);
        assertEquals(List.of("a", "b", "c"), o.get());
    }

    @Test
    void anEmptyArrayIsAnEmptyList() {
        RedisReplyOutput o = output();
        o.multi(0);
        o.complete(1);
        assertEquals(List.of(), o.get());
    }

    @Test
    void aNullArrayIsNull() {
        RedisReplyOutput o = output();
        o.multi(-1);
        assertNull(o.get());
    }

    @Test
    void nestedArraysStayNested() {
        RedisReplyOutput o = output();
        o.multi(2);           // outer
        o.set(buf("first"));
        o.multi(2);           // inner
        o.set(buf("x"));
        o.set(buf("y"));
        o.complete(2);        // inner done
        o.complete(1);        // outer done
        assertEquals(List.of("first", List.of("x", "y")), o.get());
    }

    @Test
    void aMapIsFlattenedToAlternatingKeysAndValues() {
        RedisReplyOutput o = output();
        o.multiMap(1);        // one pair
        o.set(buf("maxmemory"));
        o.set(buf("0"));
        o.complete(1);
        assertEquals(List.of("maxmemory", "0"), o.get());
    }

    @Test
    void mixedScalarTypesInsideAnArrayAreKept() {
        RedisReplyOutput o = output();
        o.multi(3);
        o.set(buf("name"));
        o.set(7L);
        o.set((ByteBuffer) null);
        o.complete(1);
        assertEquals(java.util.Arrays.asList("name", 7L, null), o.get());
    }
}
