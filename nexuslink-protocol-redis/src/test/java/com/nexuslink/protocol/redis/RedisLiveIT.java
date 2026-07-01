package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live Redis test against the local {@code test-env} stack.
 * <pre>docker compose -f test-env/docker-compose.yml up -d redis</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RedisLiveIT {

    @Test
    void setGetScanAndType() {
        try (RedisService svc = new RedisService()) {
            svc.connect("redis://localhost:6379");
            assertTrue(svc.isConnected());

            assertEquals("OK", svc.execute("SET nexus:it:k1 hello").trim().replace("\"", ""));
            svc.execute("SET nexus:it:k2 world");

            assertEquals("hello", svc.value("nexus:it:k1"));
            assertEquals("string", svc.type("nexus:it:k1"));

            List<String> keys = svc.scanKeys("nexus:it:*", 100);
            assertTrue(keys.contains("nexus:it:k1"));
            assertTrue(keys.contains("nexus:it:k2"));

            svc.execute("DEL nexus:it:k1");
            svc.execute("DEL nexus:it:k2");
        }
    }
}
