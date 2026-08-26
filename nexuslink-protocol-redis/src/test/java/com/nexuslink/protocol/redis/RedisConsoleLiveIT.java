package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The console against a real server: proves that commands are dispatched as typed rather than
 * matched against a client-side list, so anything the server supports works — including commands
 * the old hardcoded switch had never heard of.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RedisConsoleLiveIT {

    private RedisService service;

    @BeforeEach
    void setUp() {
        service = new RedisService();
        service.connect("redis://localhost:6379");
    }

    @AfterEach
    void tearDown() { service.close(); }

    @Test
    void commandsOutsideTheOldAllowListNowWork() {
        service.execute("SET console:it hello");
        // None of these existed in the old hardcoded console switch.
        assertEquals("5", service.execute("STRLEN console:it"));
        assertEquals("embstr", service.execute("OBJECT ENCODING console:it"));
        assertEquals("1", service.execute("COPY console:it console:it2"));
        assertTrue(service.execute("MEMORY USAGE console:it").matches("\\d+"));
        service.execute("DEL console:it console:it2");
    }

    @Test
    void aQuotedValueWithSpacesSurvivesTheRoundTrip() {
        service.execute("SET console:quoted \"hello world\"");
        assertEquals("hello world", service.execute("GET console:quoted"));
        service.execute("DEL console:quoted");
    }

    @Test
    void multiWordAdministrativeCommandsReturnTheirReply() {
        String reply = service.execute("CONFIG GET maxmemory");
        assertTrue(reply.contains("maxmemory"), reply);
    }

    @Test
    void aListReplyIsRenderedOnePerLine() {
        service.execute("DEL console:list");
        service.execute("RPUSH console:list a b c");
        assertEquals("a\nb\nc", service.execute("LRANGE console:list 0 -1"));
        service.execute("DEL console:list");
    }

    @Test
    void anUnknownCommandComesBackAsTheServersOwnError() {
        String reply = service.execute("NOSUCHCOMMAND x");
        assertTrue(reply.toLowerCase().contains("unknown command"), reply);
    }

    @Test
    void theServerIdentifiesItself() {
        RedisServerInfo info = service.serverInfo();
        assertFalse(info.version().isBlank(), "a live server reports its version");
        assertEquals("standalone", info.mode());
    }
}
