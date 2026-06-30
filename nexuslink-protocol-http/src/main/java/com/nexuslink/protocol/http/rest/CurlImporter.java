package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a {@code curl ...} command line into a {@link RestRequest} — the inverse of the cURL
 * snippet produced by {@link RestCodeGenerator}. Lets a user paste a curl command (e.g. copied
 * from browser dev-tools) and get a ready-to-send request.
 *
 * <p>The parser is intentionally tolerant: it understands the common curl surface (method, URL,
 * headers, body, basic auth) and silently skips benign flags it does not model rather than
 * failing. The only hard error is the complete absence of a URL.</p>
 */
public final class CurlImporter {

    private CurlImporter() {}

    /**
     * Builds a {@link RestRequest} from a curl command string.
     *
     * @param command the full command, e.g. {@code curl -X POST https://api.example.com -d '{"a":1}'}
     * @return the parsed request
     * @throws IllegalArgumentException if the command contains no URL
     */
    public static RestRequest fromCurl(String command) {
        if (command == null) throw new IllegalArgumentException("curl command is null");

        List<String> tokens = tokenize(command);
        // Drop a leading "curl" word if present.
        if (!tokens.isEmpty() && tokens.get(0).equals("curl")) tokens.remove(0);

        String url = null;
        String method = null;
        boolean methodExplicit = false;
        List<RestRequest.KeyValue> headers = new ArrayList<>();
        List<String> dataParts = new ArrayList<>();
        String user = null;

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            switch (t) {
                case "-X", "--request" -> {
                    String v = next(tokens, ++i);
                    if (v != null) { method = v; methodExplicit = true; }
                }
                case "-H", "--header" -> {
                    String v = next(tokens, ++i);
                    if (v != null) addHeader(headers, v);
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode" -> {
                    String v = next(tokens, ++i);
                    if (v != null) dataParts.add(v);
                }
                case "-u", "--user" -> {
                    String v = next(tokens, ++i);
                    if (v != null) user = v;
                }
                case "--url" -> {
                    String v = next(tokens, ++i);
                    if (v != null) url = v;
                }
                // Benign flags that take no value — skip silently.
                case "--compressed", "-s", "--silent", "-k", "--insecure", "-L", "--location",
                     "-i", "--include", "-v", "--verbose", "-g", "--globoff", "-S", "--show-error",
                     "-f", "--fail", "-0", "--http1.0", "--http1.1", "--http2" -> { /* ignored */ }
                default -> {
                    if (t.startsWith("-")) {
                        // Unknown option: skip without throwing. We cannot know whether it takes a
                        // value, so we conservatively skip only the flag itself.
                    } else if (url == null) {
                        url = t; // first bare argument is the URL
                    }
                    // additional bare arguments are ignored
                }
            }
        }

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("No URL found in curl command");
        }

        RestRequest r = new RestRequest();
        r.setUrl(url);

        boolean hasBody = !dataParts.isEmpty();
        if (methodExplicit) {
            r.setMethod(method.toUpperCase());
        } else if (hasBody) {
            r.setMethod("POST"); // curl defaults to POST when a body is supplied
        } else {
            r.setMethod("GET");
        }

        String contentType = null;
        for (RestRequest.KeyValue h : headers) {
            // Map an Authorization: Bearer <token> header to BEARER auth; keep other headers as-is.
            if (h.getKey().equalsIgnoreCase("Authorization")
                    && h.getValue().regionMatches(true, 0, "Bearer ", 0, 7)) {
                r.setAuthType(RestRequest.AuthType.BEARER);
                r.setAuthToken(h.getValue().substring(7).trim());
                continue;
            }
            if (h.getKey().equalsIgnoreCase("Content-Type")) contentType = h.getValue();
            r.getHeaders().add(h);
        }

        if (hasBody) {
            r.setBody(String.join("&", dataParts));
            r.setBodyType(bodyTypeFor(contentType));
        }

        if (user != null) {
            r.setAuthType(RestRequest.AuthType.BASIC);
            int colon = user.indexOf(':');
            if (colon >= 0) {
                r.setAuthUsername(user.substring(0, colon));
                r.setAuthPassword(user.substring(colon + 1));
            } else {
                r.setAuthUsername(user);
                r.setAuthPassword("");
            }
        }

        return r;
    }

    /** Maps a Content-Type value to the closest {@link RestRequest.BodyType}. */
    private static RestRequest.BodyType bodyTypeFor(String contentType) {
        if (contentType == null) return RestRequest.BodyType.TEXT;
        String c = contentType.toLowerCase();
        if (c.contains("json")) return RestRequest.BodyType.JSON;
        if (c.contains("xml")) return RestRequest.BodyType.XML;
        if (c.contains("x-www-form-urlencoded")) return RestRequest.BodyType.FORM_URLENCODED;
        return RestRequest.BodyType.TEXT;
    }

    /** Splits a {@code Name: value} header token and appends it. */
    private static void addHeader(List<RestRequest.KeyValue> headers, String raw) {
        int colon = raw.indexOf(':');
        if (colon < 0) {
            headers.add(new RestRequest.KeyValue(raw.trim(), ""));
            return;
        }
        String name = raw.substring(0, colon).trim();
        String value = raw.substring(colon + 1).trim();
        headers.add(new RestRequest.KeyValue(name, value));
    }

    private static String next(List<String> tokens, int i) {
        return i < tokens.size() ? tokens.get(i) : null;
    }

    /**
     * Splits a command line into tokens, honouring single quotes, double quotes, {@code $'...'}
     * (treated loosely as a quote) and backslash escapes. Line continuations
     * ({@code \\\n}) are folded away first.
     */
    static List<String> tokenize(String command) {
        String s = command
                .replace("\\\r\n", " ")
                .replace("\\\n", " ")
                .strip();

        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        int n = s.length();

        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);

            if (c == '\'' || c == '"') {
                inToken = true;
                char quote = c;
                i++;
                while (i < n && s.charAt(i) != quote) {
                    char q = s.charAt(i);
                    // Backslash escapes are honoured inside double quotes only (shell semantics).
                    if (q == '\\' && quote == '"' && i + 1 < n) {
                        cur.append(s.charAt(++i));
                    } else {
                        cur.append(q);
                    }
                    i++;
                }
                // trailing char is the closing quote (or end of string)
                continue;
            }

            if (c == '$' && i + 1 < n && (s.charAt(i + 1) == '\'' || s.charAt(i + 1) == '"')) {
                // $'...' / $"..." — treat the $ as a no-op prefix to the quote.
                inToken = true;
                continue;
            }

            if (c == '\\' && i + 1 < n) {
                inToken = true;
                cur.append(s.charAt(++i));
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
                continue;
            }

            inToken = true;
            cur.append(c);
        }

        if (inToken) out.add(cur.toString());
        return out;
    }
}
