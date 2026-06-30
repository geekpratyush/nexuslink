package com.nexuslink.protocol.http.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the full NTLM challenge-response wiring in {@link RestExecutionService} against a
 * loopback {@link HttpServer} that emulates an NTLM-protected endpoint: a Type 1 (negotiate) token
 * earns a 401 + Type 2 (challenge), and a Type 3 (authenticate) token earns a 200. This verifies the
 * 401 → Type 2 → Type 3 sequence end-to-end offline (the message crypto itself is covered by
 * {@link NtlmAuthenticatorTest}).
 */
class RestExecutionNtlmTest {

    /** Builds a minimal but well-formed Type 2 message with the given 8-byte server challenge. */
    private static String type2(byte[] serverChallenge) {
        byte[] msg = new byte[48];
        byte[] sig = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};
        System.arraycopy(sig, 0, msg, 0, 8);
        msg[8] = 0x02;                       // message type 2 (little-endian)
        System.arraycopy(serverChallenge, 0, msg, 24, 8);
        // TargetInfo security buffer at offset 40 left zero (len 0) → empty target info.
        return Base64.getEncoder().encodeToString(msg);
    }

    private static int messageType(String authHeader) {
        byte[] msg = Base64.getDecoder().decode(authHeader.substring("NTLM ".length()).trim());
        return msg[8]; // type byte of the little-endian message-type field
    }

    @Test
    void completesNtlmHandshakeToTwoHundred() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] challenge = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
        server.createContext("/secure", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status;
            if (auth == null || !auth.startsWith("NTLM ") || auth.trim().equals("NTLM")) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "NTLM");
                status = 401;
                body = "negotiate".getBytes(StandardCharsets.UTF_8);
            } else if (messageType(auth) == 1) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "NTLM " + type2(challenge));
                status = 401;
                body = "challenge".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "authenticated".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            RestRequest req = new RestRequest();
            req.setMethod("GET");
            req.setUrl("http://127.0.0.1:" + port + "/secure");
            req.setAuthType(RestRequest.AuthType.NTLM);
            req.setNtlmDomain("DOMAIN");
            req.setNtlmUsername("user");
            req.setNtlmPassword("Password");
            req.setNtlmWorkstation("WS");

            RestResponse resp = new RestExecutionService().execute(req);

            assertEquals(200, resp.statusCode(), "handshake should end in 200; body=" + resp.body());
            assertEquals("authenticated", resp.body());
        } finally {
            server.stop(0);
        }
    }
}
