package com.nexuslink.ui.util;

import java.net.URI;
import java.util.Locale;

/**
 * Builds a compact per-endpoint label (e.g. {@code "GET api.example.com/users"}) used to key REST
 * metrics by endpoint rather than lumping every call under one channel. Pure and testable.
 *
 * <p>The query string and fragment are dropped so calls to the same endpoint with different query
 * values aggregate together; the scheme is dropped for brevity; a trailing slash is trimmed (except a
 * bare root path).</p>
 */
public final class EndpointLabel {

    private EndpointLabel() {}

    /** {@code "<METHOD> <host><path>"} for {@code url}; falls back gracefully on a blank/unparsable URL. */
    public static String forRest(String method, String url) {
        String m = (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        String target = target(url);
        return target.isEmpty() ? m : m + " " + target;
    }

    private static String target(String url) {
        if (url == null || url.isBlank()) return "";
        String s = url.trim();
        try {
            URI u = URI.create(s);
            String host = u.getHost();
            String path = u.getPath();
            if (host != null) {
                String port = u.getPort() > 0 ? ":" + u.getPort() : "";
                return host + port + trimPath(path);
            }
            // No authority (e.g. a relative URL): use the raw path portion, minus query/fragment.
            if (path != null && !path.isEmpty()) return stripQueryFragment(trimPath(path));
        } catch (IllegalArgumentException ignored) {
            // fall through to the string-based fallback below
        }
        // Fallback: strip scheme, query and fragment by hand.
        String noScheme = s.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        return stripQueryFragment(noScheme);
    }

    private static String stripQueryFragment(String s) {
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return trimPath(s);
    }

    /** Drops a trailing slash unless the whole path is just {@code "/"}. */
    private static String trimPath(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.length() > 1 && path.endsWith("/")) return path.substring(0, path.length() - 1);
        return path;
    }
}
