package com.nexuslink.protocol.http.rest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic HMAC request signer for the many bespoke "sign a canonical string with a shared secret"
 * auth schemes that APIs roll themselves (as opposed to the fully-specified AWS SigV4). Pure and
 * side-effect-free: it builds a <em>string-to-sign</em> from a caller-supplied template, HMACs it
 * with the secret, and returns the header(s) to add. The HMAC chain is verified offline against the
 * RFC 4231 known-answer vectors (see {@code HmacAuthenticatorTest}).
 *
 * <p>Both the string-to-sign and the header-value are templates over a small placeholder set, so a
 * user can express most real schemes without code:
 * <ul>
 *   <li>{@code {method}} — upper-cased HTTP method</li>
 *   <li>{@code {path}} — request path (leading {@code /})</li>
 *   <li>{@code {query}} — raw query string ({@code ""} when none)</li>
 *   <li>{@code {url}} — the full request URI</li>
 *   <li>{@code {host}} — host[:port]</li>
 *   <li>{@code {date}} — current time as an RFC&nbsp;1123 GMT date (also emitted as a {@code Date}
 *       header so the server can reconstruct the same string)</li>
 *   <li>{@code {body}} — request body as UTF-8 text</li>
 *   <li>{@code {body-sha256-hex}} / {@code {body-sha256-base64}} — digest of the body</li>
 *   <li>{@code {keyId}} — the (non-secret) key/access id</li>
 *   <li>{@code {signature}} — the computed signature (header-value template only)</li>
 * </ul>
 * Use a literal {@code \n} in a template to embed a newline (the typical line-oriented canonical
 * form, e.g. {@code {method}\n{path}\n{date}}).
 */
public final class HmacAuthenticator {

    private HmacAuthenticator() {}

    /** Supported MAC algorithms (JCA standard names). */
    public enum Algorithm {
        HMAC_SHA256("HmacSHA256"),
        HMAC_SHA1("HmacSHA1"),
        HMAC_SHA512("HmacSHA512");

        private final String jca;
        Algorithm(String jca) { this.jca = jca; }
        public String jcaName() { return jca; }
    }

    /** How the raw signature bytes are encoded into the header. */
    public enum Encoding { BASE64, HEX }

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME;

    /**
     * Computes the HMAC auth header(s) for a request.
     *
     * @return an ordered map of header name → value to add to the outgoing request. Always contains
     *         {@code headerName}; additionally contains {@code Date} when the string-to-sign uses
     *         {@code {date}} (so the value is verifiable server-side).
     */
    public static Map<String, String> sign(Algorithm algorithm, String secret, Encoding encoding,
                                           String stringToSignTemplate, String headerName,
                                           String headerValueTemplate, String keyId,
                                           String method, String uri, byte[] body, Instant when) {
        URI u = URI.create(uri);
        byte[] payload = body == null ? new byte[0] : body;
        String httpDate = HTTP_DATE.format(ZonedDateTime.ofInstant(when, ZoneOffset.UTC));

        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("method", method == null ? "" : method.toUpperCase());
        ctx.put("path", u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath());
        ctx.put("query", u.getRawQuery() == null ? "" : u.getRawQuery());
        ctx.put("url", uri);
        ctx.put("host", u.getHost() == null ? "" : u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""));
        ctx.put("date", httpDate);
        ctx.put("body", new String(payload, StandardCharsets.UTF_8));
        ctx.put("body-sha256-hex", hex(sha256(payload)));
        ctx.put("body-sha256-base64", Base64.getEncoder().encodeToString(sha256(payload)));
        ctx.put("keyId", keyId == null ? "" : keyId);

        String stringToSign = applyTemplate(stringToSignTemplate, ctx);
        byte[] sig = hmac(algorithm.jcaName(),
                (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8),
                stringToSign.getBytes(StandardCharsets.UTF_8));
        String signature = encoding == Encoding.HEX
                ? hex(sig) : Base64.getEncoder().encodeToString(sig);

        ctx.put("signature", signature);
        String headerValue = applyTemplate(
                headerValueTemplate == null || headerValueTemplate.isBlank()
                        ? "{signature}" : headerValueTemplate,
                ctx);

        Map<String, String> out = new LinkedHashMap<>();
        // Emit Date alongside so a server signing the same canonical string can verify it.
        boolean usesDate = (stringToSignTemplate != null && stringToSignTemplate.contains("{date}"))
                || headerValue.equals(httpDate);
        if (usesDate) out.put("Date", httpDate);
        out.put(headerName == null || headerName.isBlank() ? "Authorization" : headerName, headerValue);
        return out;
    }

    /** Replaces {@code {placeholder}} tokens and unescapes a literal {@code \n} into a newline. */
    private static String applyTemplate(String template, Map<String, String> ctx) {
        if (template == null) return "";
        String s = template.replace("\\n", "\n");
        for (Map.Entry<String, String> e : ctx.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    private static byte[] hmac(String jcaName, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(jcaName);
            // An empty key is invalid for SecretKeySpec; pad to a single zero byte so signing never
            // throws on a not-yet-filled-in form (the resulting signature is simply meaningless).
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, jcaName));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(jcaName + " failure", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
