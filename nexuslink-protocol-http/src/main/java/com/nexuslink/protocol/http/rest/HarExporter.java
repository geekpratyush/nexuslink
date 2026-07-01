package com.nexuslink.protocol.http.rest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link RestRequest} and its {@link RestResponse} into an
 * <a href="http://www.softwareishard.com/blog/har-12-spec/">HTTP Archive (HAR) 1.2</a> JSON
 * document. Pure and dependency-free: the module carries no JSON library, so the object graph is
 * hand-rolled and every string is escaped per RFC 8259 (control characters emitted as
 * {@code \\uXXXX}). No I/O and no network — the result is a single {@code String} the caller may
 * hand to a viewer such as the Chrome DevTools network panel or Fiddler.
 *
 * <p>A HAR {@code log} contains one {@code entry} per exchange; each entry mirrors the request
 * (method, URL, query string, headers, {@code postData}) and the response (status, headers,
 * {@code content}) alongside {@code timings} and the wall-clock {@code time}. Timing phases that
 * NexusLink does not measure are reported as {@code -1}, which HAR treats as "not applicable".
 */
public final class HarExporter {

    private static final String CREATOR_NAME = "NexusLink";
    private static final String CREATOR_VERSION = "1.0";

    private HarExporter() {}

    /** One request/response exchange plus the instant the request was started. */
    public record Entry(RestRequest request, RestResponse response, Instant startedDateTime) {
        public Entry(RestRequest request, RestResponse response) {
            this(request, response, Instant.now());
        }
    }

    /** A single-entry HAR document for one request/response, timestamped now. */
    public static String toHar(RestRequest request, RestResponse response) {
        return toHar(new Entry(request, response));
    }

    /** A single-entry HAR document for one request/response started at {@code startedAt}. */
    public static String toHar(RestRequest request, RestResponse response, Instant startedAt) {
        return toHar(new Entry(request, response, startedAt));
    }

    /** A HAR document for the given exchanges (varargs convenience). */
    public static String toHar(Entry... entries) {
        return toHar(List.of(entries));
    }

    /** A HAR document whose {@code log.entries[]} holds every supplied exchange in order. */
    public static String toHar(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"log\": {\n");
        sb.append("    \"version\": \"1.2\",\n");
        sb.append("    \"creator\": {\"name\": ").append(str(CREATOR_NAME))
          .append(", \"version\": ").append(str(CREATOR_VERSION)).append("},\n");
        sb.append("    \"entries\": [");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('\n');
            appendEntry(sb, entries.get(i));
        }
        sb.append(entries.isEmpty() ? "]\n" : "\n    ]\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendEntry(StringBuilder sb, Entry entry) {
        RestRequest req = entry.request();
        RestResponse resp = entry.response();
        RestResponse.Timing t = resp == null ? null : resp.timing();
        long time = t == null ? -1 : t.totalMs();

        sb.append("      {\n");
        sb.append("        \"startedDateTime\": ")
          .append(str((entry.startedDateTime() == null ? Instant.EPOCH : entry.startedDateTime()).toString()))
          .append(",\n");
        sb.append("        \"time\": ").append(time).append(",\n");

        appendRequest(sb, req);
        sb.append(",\n");
        appendResponse(sb, resp);
        sb.append(",\n");

        sb.append("        \"cache\": {},\n");
        appendTimings(sb, t);
        sb.append("\n      }");
    }

    private static void appendRequest(StringBuilder sb, RestRequest req) {
        String method = req.getMethod() == null ? "" : req.getMethod();
        String httpVersion = "HTTP/1.1";
        boolean body = hasBody(req);

        sb.append("        \"request\": {\n");
        sb.append("          \"method\": ").append(str(method)).append(",\n");
        sb.append("          \"url\": ").append(str(req.requestUri())).append(",\n");
        sb.append("          \"httpVersion\": ").append(str(httpVersion)).append(",\n");
        sb.append("          \"cookies\": [],\n");

        // headers
        sb.append("          \"headers\": ");
        appendNameValueArray(sb, requestHeaders(req), 10);
        sb.append(",\n");

        // queryString
        sb.append("          \"queryString\": ");
        appendNameValueArray(sb, queryParams(req), 10);
        sb.append(",\n");

        if (body) {
            sb.append("          \"postData\": {");
            sb.append("\"mimeType\": ").append(str(requestMimeType(req)));
            sb.append(", \"text\": ").append(str(req.getBody()));
            sb.append("},\n");
        }

        long bodySize = body ? utf8Len(req.getBody()) : 0;
        sb.append("          \"headersSize\": -1,\n");
        sb.append("          \"bodySize\": ").append(bodySize).append('\n');
        sb.append("        }");
    }

    private static void appendResponse(StringBuilder sb, RestResponse resp) {
        int status = resp == null ? 0 : resp.statusCode();
        String statusText = resp == null || resp.statusText() == null ? "" : resp.statusText();
        String httpVersion = resp != null && resp.httpVersion() != null && !resp.httpVersion().isBlank()
                ? resp.httpVersion() : "HTTP/1.1";
        String bodyText = resp == null || resp.body() == null ? "" : resp.body();
        long size = resp == null ? 0 : resp.bodyBytes();
        String mime = resp == null ? "" : headerValue(resp.headers(), "Content-Type");
        String redirect = resp == null ? "" : headerValue(resp.headers(), "Location");

        sb.append("        \"response\": {\n");
        sb.append("          \"status\": ").append(status).append(",\n");
        sb.append("          \"statusText\": ").append(str(statusText)).append(",\n");
        sb.append("          \"httpVersion\": ").append(str(httpVersion)).append(",\n");
        sb.append("          \"cookies\": [],\n");

        sb.append("          \"headers\": ");
        appendNameValueArray(sb, responseHeaders(resp), 10);
        sb.append(",\n");

        sb.append("          \"content\": {");
        sb.append("\"size\": ").append(size);
        sb.append(", \"mimeType\": ").append(str(mime));
        sb.append(", \"text\": ").append(str(bodyText));
        sb.append("},\n");

        sb.append("          \"redirectURL\": ").append(str(redirect)).append(",\n");
        sb.append("          \"headersSize\": -1,\n");
        sb.append("          \"bodySize\": ").append(size).append('\n');
        sb.append("        }");
    }

    private static void appendTimings(StringBuilder sb, RestResponse.Timing t) {
        long blocked = -1;
        long dns = t == null ? -1 : t.dnsMs();
        long connect = t == null ? -1 : t.connectMs();
        long ssl = t == null ? -1 : t.tlsMs();
        long send = -1; // request-send duration is not measured separately
        long wait = t == null ? -1 : t.ttfbMs();
        long receive = t == null ? -1 : t.downloadMs();

        sb.append("        \"timings\": {")
          .append("\"blocked\": ").append(blocked)
          .append(", \"dns\": ").append(dns)
          .append(", \"connect\": ").append(connect)
          .append(", \"send\": ").append(send)
          .append(", \"wait\": ").append(wait)
          .append(", \"receive\": ").append(receive)
          .append(", \"ssl\": ").append(ssl)
          .append('}');
    }

    // ---- model extraction ----

    private static boolean hasBody(RestRequest r) {
        return r.getBodyType() != RestRequest.BodyType.NONE
                && r.getBody() != null && !r.getBody().isBlank();
    }

    /** Content-Type for the request body: an explicit header wins, else the body type's default. */
    private static String requestMimeType(RestRequest r) {
        for (RestRequest.KeyValue kv : r.getHeaders()) {
            if (kv.isEnabled() && kv.getKey() != null
                    && kv.getKey().equalsIgnoreCase("Content-Type")) {
                return kv.getValue() == null ? "" : kv.getValue();
            }
        }
        String ct = r.contentType();
        return ct == null ? "" : ct;
    }

    /** Enabled request headers, plus the implied Content-Type when a body is present. */
    private static List<String[]> requestHeaders(RestRequest r) {
        List<String[]> out = new ArrayList<>();
        boolean userCt = false;
        for (RestRequest.KeyValue kv : r.getHeaders()) {
            if (!kv.isEnabled() || kv.getKey() == null || kv.getKey().isBlank()) continue;
            if (kv.getKey().equalsIgnoreCase("Content-Type")) userCt = true;
            out.add(new String[]{kv.getKey(), kv.getValue() == null ? "" : kv.getValue()});
        }
        if (!userCt && hasBody(r)) {
            String ct = r.contentType();
            if (ct != null) out.add(new String[]{"Content-Type", ct});
        }
        return out;
    }

    private static List<String[]> queryParams(RestRequest r) {
        List<String[]> out = new ArrayList<>();
        for (RestRequest.KeyValue kv : r.getQueryParams()) {
            if (!kv.isEnabled() || kv.getKey() == null || kv.getKey().isBlank()) continue;
            out.add(new String[]{kv.getKey(), kv.getValue() == null ? "" : kv.getValue()});
        }
        return out;
    }

    /** Response headers flattened to name/value rows (a multi-valued header repeats per value). */
    private static List<String[]> responseHeaders(RestResponse resp) {
        List<String[]> out = new ArrayList<>();
        if (resp == null || resp.headers() == null) return out;
        for (Map.Entry<String, List<String>> e : resp.headers().entrySet()) {
            if (e.getKey() == null) continue; // HTTP status line pseudo-header
            List<String> values = e.getValue();
            if (values == null || values.isEmpty()) {
                out.add(new String[]{e.getKey(), ""});
            } else {
                for (String v : values) out.add(new String[]{e.getKey(), v == null ? "" : v});
            }
        }
        return out;
    }

    /** First value of a header, matched case-insensitively; {@code ""} when absent. */
    private static String headerValue(Map<String, List<String>> headers, String name) {
        if (headers == null) return "";
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() || v.get(0) == null ? "" : v.get(0);
            }
        }
        return "";
    }

    // ---- JSON emission ----

    /** Appends a HAR name/value array (headers or query params) indented by {@code indent} spaces. */
    private static void appendNameValueArray(StringBuilder sb, List<String[]> pairs, int indent) {
        if (pairs.isEmpty()) {
            sb.append("[]");
            return;
        }
        String pad = " ".repeat(indent);
        String inner = pad + "  ";
        sb.append("[\n");
        for (int i = 0; i < pairs.size(); i++) {
            String[] nv = pairs.get(i);
            sb.append(inner).append("{\"name\": ").append(str(nv[0]))
              .append(", \"value\": ").append(str(nv[1])).append('}');
            sb.append(i < pairs.size() - 1 ? ",\n" : "\n");
        }
        sb.append(pad).append(']');
    }

    private static long utf8Len(String s) {
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** A JSON string literal, escaped per RFC 8259; a null becomes an empty string literal. */
    private static String str(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
