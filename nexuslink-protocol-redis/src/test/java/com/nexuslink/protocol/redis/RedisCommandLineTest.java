package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisCommandLineTest {

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("SET", "k", "v"), RedisCommandLine.parse("SET k v"));
        assertEquals(List.of("PING"), RedisCommandLine.parse("   PING   "));
    }

    @Test
    void aQuotedRunIsOneArgument() {
        assertEquals(List.of("SET", "greeting", "hello world"),
                RedisCommandLine.parse("SET greeting \"hello world\""));
        assertEquals(List.of("SET", "greeting", "hello world"),
                RedisCommandLine.parse("SET greeting 'hello world'"));
    }

    @Test
    void escapesInsideDoubleQuotesAreDecoded() {
        assertEquals(List.of("SET", "k", "a\nb\tc"), RedisCommandLine.parse("SET k \"a\\nb\\tc\""));
        assertEquals(List.of("SET", "k", "say \"hi\""), RedisCommandLine.parse("SET k \"say \\\"hi\\\"\""));
        assertEquals(List.of("SET", "k", "A"), RedisCommandLine.parse("SET k \"\\x41\""));
    }

    @Test
    void singleQuotesTakeTheTextLiterally() {
        assertEquals(List.of("SET", "k", "a\\nb"), RedisCommandLine.parse("SET k 'a\\nb'"));
        assertEquals(List.of("SET", "k", "it's"), RedisCommandLine.parse("SET k 'it\\'s'"));
    }

    @Test
    void quotedAndUnquotedRunsCanTouch() {
        assertEquals(List.of("SET", "prefix:key", "value"),
                RedisCommandLine.parse("SET prefix:\"key\" value"));
    }

    @Test
    void anEmptyQuotedArgumentSurvives() {
        assertEquals(List.of("SET", "k", ""), RedisCommandLine.parse("SET k \"\""));
    }

    @Test
    void anUnbalancedQuoteIsAnError() {
        assertThrows(RedisCommandLine.RedisCommandLineException.class,
                () -> RedisCommandLine.parse("SET k \"unterminated"));
        assertThrows(RedisCommandLine.RedisCommandLineException.class,
                () -> RedisCommandLine.parse("SET k 'unterminated"));
    }

    @Test
    void emptyInputYieldsNoArguments() {
        assertTrue(RedisCommandLine.parse("").isEmpty());
        assertTrue(RedisCommandLine.parse("   ").isEmpty());
        assertTrue(RedisCommandLine.parse(null).isEmpty());
    }
}
