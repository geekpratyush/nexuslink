package com.nexuslink.protocol.http.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The decoded, <strong>unverified</strong> contents of a JSON Web Token as produced by
 * {@link JwtDecoder}. This is an inspection view only: the signature segment is preserved verbatim
 * but is never checked, so nothing here implies the token is authentic, untampered, or issued by a
 * trusted party. Treat every field as attacker-controlled until validated elsewhere.
 *
 * <p>The header and payload are exposed both as their raw JSON text ({@link #headerJson()},
 * {@link #payloadJson()}) and through typed accessors for the registered header parameters
 * ({@code alg}, {@code typ}, {@code kid}) and the registered
 * <a href="https://www.rfc-editor.org/rfc/rfc7519#section-4.1">RFC 7519</a> claims. Any other claim
 * can be read as text via {@link #claim(String)}.
 *
 * <p>The {@code exp}, {@code iat} and {@code nbf} claims are interpreted as
 * <em>NumericDate</em> values — seconds elapsed since the UNIX epoch, UTC — and surfaced as
 * {@link Instant}s. A missing time claim yields {@code null} from its accessor.
 */
public final class DecodedJwt {

    private final String headerJson;
    private final String payloadJson;
    private final String signature;
    private final Map<String, Object> header;
    private final Map<String, Object> payload;

    DecodedJwt(String headerJson, String payloadJson, String signature,
               Map<String, Object> header, Map<String, Object> payload) {
        this.headerJson = headerJson;
        this.payloadJson = payloadJson;
        this.signature = signature;
        this.header = header;
        this.payload = payload;
    }

    // ---- raw segments ----

    /** The Base64URL-decoded header as its original JSON text. */
    public String headerJson() {
        return headerJson;
    }

    /** The Base64URL-decoded payload as its original JSON text. */
    public String payloadJson() {
        return payloadJson;
    }

    /**
     * The third JWT segment exactly as it appeared in the compact serialization: the Base64URL
     * (unpadded) signature string. It is returned untouched and is <strong>never verified</strong>;
     * for an unsecured JWS ({@code alg} = {@code none}) this is the empty string.
     */
    public String signature() {
        return signature;
    }

    /**
     * The raw signature bytes obtained by Base64URL-decoding {@link #signature()}. These are handed
     * back purely so a caller can display or fingerprint them; decoding them does not, and is not
     * meant to, validate anything. Empty for an unsecured JWS.
     */
    public byte[] signatureBytes() {
        return JwtDecoder.base64UrlDecode(signature);
    }

    // ---- header parameters ----

    /** The {@code alg} header parameter (e.g. {@code HS256}, {@code RS256}, {@code none}), or {@code null}. */
    public String algorithm() {
        return asString(header.get("alg"));
    }

    /** The {@code typ} header parameter (e.g. {@code JWT}), or {@code null}. */
    public String type() {
        return asString(header.get("typ"));
    }

    /** The {@code kid} (key id) header parameter, or {@code null}. */
    public String keyId() {
        return asString(header.get("kid"));
    }

    // ---- registered claims ----

    /** The {@code iss} (issuer) claim, or {@code null}. */
    public String issuer() {
        return asString(payload.get("iss"));
    }

    /** The {@code sub} (subject) claim, or {@code null}. */
    public String subject() {
        return asString(payload.get("sub"));
    }

    /**
     * The {@code aud} (audience) claim, or {@code null}. Per RFC 7519 the audience may be a single
     * string or an array of strings; when it is an array the elements are joined with {@code ", "}
     * for this convenience accessor.
     */
    public String audience() {
        Object a = payload.get("aud");
        if (a instanceof List<?> list) {
            StringBuilder b = new StringBuilder();
            for (Object o : list) {
                if (b.length() > 0) b.append(", ");
                b.append(asString(o));
            }
            return b.toString();
        }
        return asString(a);
    }

    /** The {@code exp} (expiration time) claim as an {@link Instant}, or {@code null} if absent. */
    public Instant expiration() {
        return asInstant(payload.get("exp"));
    }

    /** The {@code iat} (issued-at time) claim as an {@link Instant}, or {@code null} if absent. */
    public Instant issuedAt() {
        return asInstant(payload.get("iat"));
    }

    /** The {@code nbf} (not-before time) claim as an {@link Instant}, or {@code null} if absent. */
    public Instant notBefore() {
        return asInstant(payload.get("nbf"));
    }

    /** The {@code jti} (JWT id) claim, or {@code null}. */
    public String jwtId() {
        return asString(payload.get("jti"));
    }

    /**
     * The named payload claim rendered as text, or {@code null} if the claim is absent (or present
     * but JSON {@code null}). Scalar claims — strings, numbers, booleans — are returned in their
     * canonical text form; structured claims (objects and arrays) return {@code null} here, so use
     * {@link #payloadJson()} to inspect those.
     */
    public String claim(String name) {
        return asString(payload.get(name));
    }

    // ---- time helpers ----

    /**
     * Whether the token is expired relative to {@code now}: {@code true} when an {@code exp} claim
     * is present and {@code now} is at or after it. Returns {@code false} when {@code exp} is
     * absent. No clock-skew leeway is applied — the comparison is exact; add tolerance yourself if
     * needed. This checks only the timestamp, never the signature.
     */
    public boolean isExpired(Instant now) {
        Instant exp = expiration();
        return exp != null && !now.isBefore(exp);
    }

    /**
     * Whether the token is not yet valid relative to {@code now}: {@code true} when an {@code nbf}
     * claim is present and {@code now} is strictly before it. Returns {@code false} when {@code nbf}
     * is absent. No clock-skew leeway is applied. This checks only the timestamp, never the
     * signature.
     */
    public boolean isNotYetValid(Instant now) {
        Instant nbf = notBefore();
        return nbf != null && now.isBefore(nbf);
    }

    // ---- conversions ----

    private static String asString(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Boolean || v instanceof Long || v instanceof Double) return v.toString();
        return null;
    }

    private static Instant asInstant(Object v) {
        if (v instanceof Long l) return Instant.ofEpochSecond(l);
        if (v instanceof Double d) {
            long secs = (long) Math.floor(d);
            long nanos = Math.round((d - secs) * 1_000_000_000L);
            return Instant.ofEpochSecond(secs, nanos);
        }
        return null;
    }
}
