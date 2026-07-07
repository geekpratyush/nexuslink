package com.nexuslink.protocol.secrets;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Pure, dependency-free helpers for building CyberArk Conjur API paths. Kept separate from
 * {@link ConjurService} so the URL-encoding rules (Conjur resource identifiers embed {@code /}
 * separators that must be percent-encoded as a single path segment) are unit-testable offline.
 */
public final class ConjurPaths {

    private ConjurPaths() {}

    /** Trims and strips a trailing slash from the appliance URL. */
    public static String normalizeUrl(String url) {
        if (url == null) throw new IllegalArgumentException("appliance url is required");
        String u = url.trim();
        if (u.isEmpty()) throw new IllegalArgumentException("appliance url is required");
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * Percent-encodes a Conjur identifier as one path segment: {@link URLEncoder} is form-encoding, so
     * we additionally turn {@code +} into {@code %20} and encode {@code /} — Conjur wants slashes inside
     * a resource id encoded, not treated as path separators.
     */
    static String encodeSegment(String s) {
        String e = URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        return e.replace("+", "%20");
    }

    /** Login/authenticate path, e.g. {@code authn/myAccount/admin/authenticate}. */
    public static String authenticatePath(String account, String login) {
        return "authn/" + encodeSegment(account) + "/" + encodeSegment(login) + "/authenticate";
    }

    /** Read-secret path for a {@code variable} resource, e.g. {@code secrets/myAccount/variable/nexus%2Fdb%2Fpassword}. */
    public static String secretPath(String account, String variableId) {
        return "secrets/" + encodeSegment(account) + "/variable/" + encodeSegment(variableId);
    }

    /** Value of the {@code Authorization} header for a base64-encoded Conjur access token. */
    public static String authorizationHeader(String base64Token) {
        return "Token token=\"" + base64Token + "\"";
    }
}
