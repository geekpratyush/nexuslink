package com.nexuslink.protocol.http.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP Digest access authentication (RFC 2617 / RFC 7616), {@code qop=auth} with {@code MD5}. Pure
 * and side-effect free: parse a {@code WWW-Authenticate: Digest …} challenge and compute the
 * matching {@code Authorization: Digest …} header. The {@link #computeResponse} digest chain is
 * verified against the canonical RFC 2617 §3.5 known-answer vector.
 *
 * <p>Digest is a challenge-response scheme: the client sends an unauthenticated request, the server
 * replies 401 with a challenge, and the client retries with the computed header — the REST executor
 * drives that single retry using {@link #parseChallenge} + {@link #authorization}.
 */
public final class DigestAuthenticator {

    private DigestAuthenticator() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Parses the parameters of a {@code WWW-Authenticate: Digest …} header into a map. */
    public static Map<String, String> parseChallenge(String header) {
        Map<String, String> params = new LinkedHashMap<>();
        if (header == null) return params;
        String h = header.trim();
        if (h.regionMatches(true, 0, "Digest", 0, 6)) h = h.substring(6).trim();

        int i = 0, n = h.length();
        while (i < n) {
            int eq = h.indexOf('=', i);
            if (eq < 0) break;
            String key = h.substring(i, eq).trim().toLowerCase();
            int v = eq + 1;
            String value;
            if (v < n && h.charAt(v) == '"') {
                int end = h.indexOf('"', v + 1);
                if (end < 0) end = n;
                value = h.substring(v + 1, end);
                i = end + 1;
            } else {
                int comma = h.indexOf(',', v);
                if (comma < 0) comma = n;
                value = h.substring(v, comma).trim();
                i = comma;
            }
            params.put(key, value);
            while (i < n && (h.charAt(i) == ',' || h.charAt(i) == ' ')) i++;
        }
        return params;
    }

    /**
     * Computes the Digest {@code response} value. When {@code qop} is non-blank the {@code nc} +
     * {@code cnonce} are folded in (RFC 2617 §3.2.2.1); otherwise the legacy RFC 2069 form is used.
     */
    public static String computeResponse(String username, String realm, String password,
                                         String method, String uri, String nonce,
                                         String nc, String cnonce, String qop) {
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        if (qop == null || qop.isBlank()) {
            return md5(ha1 + ":" + nonce + ":" + ha2);
        }
        return md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
    }

    /**
     * Builds an {@code Authorization: Digest …} header value for a parsed {@code challenge}. A random
     * client nonce is generated; {@code nc} defaults to {@code 00000001}. {@code auth} is chosen when
     * the server offers it among the {@code qop} options.
     */
    public static String authorization(Map<String, String> challenge, String username, String password,
                                       String method, String uri) {
        return authorization(challenge, username, password, method, uri,
                newCnonce(), "00000001");
    }

    /** Deterministic variant used by tests (fixed cnonce + nc). */
    public static String authorization(Map<String, String> challenge, String username, String password,
                                       String method, String uri, String cnonce, String nc) {
        String realm = challenge.getOrDefault("realm", "");
        String nonce = challenge.getOrDefault("nonce", "");
        String opaque = challenge.get("opaque");
        String qopRaw = challenge.get("qop");
        String qop = chooseQop(qopRaw);

        String response = computeResponse(username, realm, password, method, uri, nonce, nc, cnonce, qop);

        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uri).append("\", ");
        if (qop != null) {
            sb.append("qop=").append(qop).append(", ");
            sb.append("nc=").append(nc).append(", ");
            sb.append("cnonce=\"").append(cnonce).append("\", ");
        }
        sb.append("response=\"").append(response).append("\"");
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
        sb.append(", algorithm=MD5");
        return sb.toString();
    }

    private static String chooseQop(String qopRaw) {
        if (qopRaw == null || qopRaw.isBlank()) return null;
        for (String option : qopRaw.split(",")) {
            if (option.trim().equalsIgnoreCase("auth")) return "auth";
        }
        // Fall back to the first offered option (e.g. auth-int not supported → still send "auth").
        return "auth";
    }

    private static String newCnonce() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
