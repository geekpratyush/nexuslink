package com.nexuslink.protocol.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test that drives the pure {@link RespCodec} over a raw TCP socket against the real Redis in
 * the local {@code test-env} stack — proving the hand-rolled RESP2/RESP3 wire codec interoperates
 * with an actual server (not just its own round-trips).
 * <pre>docker compose -f test-env/docker-compose.yml up -d redis</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class RespCodecLiveIT {

    private final RespCodec codec = new RespCodec();

    private RespValue roundTrip(Socket sock, String... command) throws Exception {
        OutputStream out = sock.getOutputStream();
        out.write(codec.encodeCommand(command));
        out.flush();
        InputStream in = sock.getInputStream();
        return codec.decode(in);
    }

    @Test
    void pingSetGetAgainstRealRedis() throws Exception {
        try (Socket sock = new Socket("localhost", 6379)) {
            RespValue pong = roundTrip(sock, "PING");
            assertInstanceOf(RespValue.SimpleString.class, pong);
            assertEquals("PONG", ((RespValue.SimpleString) pong).value());

            String key = "nexus:resp:" + System.currentTimeMillis();
            RespValue set = roundTrip(sock, "SET", key, "hello-resp");
            assertInstanceOf(RespValue.SimpleString.class, set);
            assertEquals("OK", ((RespValue.SimpleString) set).value());

            RespValue got = roundTrip(sock, "GET", key);
            assertInstanceOf(RespValue.BulkString.class, got);
            assertEquals("hello-resp",
                    new String(((RespValue.BulkString) got).bytes(), StandardCharsets.UTF_8));

            RespValue del = roundTrip(sock, "DEL", key);
            assertInstanceOf(RespValue.RespInteger.class, del);
            assertEquals(1, ((RespValue.RespInteger) del).value());

            // A GET of the deleted key is a null bulk string.
            RespValue missing = roundTrip(sock, "GET", key);
            assertTrue(missing instanceof RespValue.RespNull
                    || (missing instanceof RespValue.BulkString bs && bs.bytes() == null),
                    "expected null reply for a missing key, got " + missing);
        }
    }
}
