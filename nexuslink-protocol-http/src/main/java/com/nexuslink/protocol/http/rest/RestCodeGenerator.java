package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates ready-to-run client snippets for a {@link RestRequest} in several languages.
 * The same effective request (URL incl. query params, headers, resolved auth, body) is rendered
 * per target so what you copy matches what the client actually sends.
 */
public final class RestCodeGenerator {

    private RestCodeGenerator() {}

    /** Supported output targets, in display order. */
    public enum Language { CURL, PYTHON, JAVASCRIPT, JAVA, POWERSHELL;
        public String label() {
            return switch (this) {
                case CURL -> "cURL";
                case PYTHON -> "Python";
                case JAVASCRIPT -> "JavaScript";
                case JAVA -> "Java";
                case POWERSHELL -> "PowerShell";
            };
        }
    }

    public static String generate(Language lang, RestRequest r) {
        return switch (lang) {
            case CURL -> curl(r);
            case PYTHON -> python(r);
            case JAVASCRIPT -> javascript(r);
            case JAVA -> java(r);
            case POWERSHELL -> powershell(r);
        };
    }

    /** Effective headers including the resolved auth header (Basic/Bearer/API-key-in-header). */
    private static Map<String, String> headers(RestRequest r) {
        Map<String, String> h = new LinkedHashMap<>();
        String ct = r.contentType();
        boolean userCt = r.getHeaders().stream()
                .anyMatch(kv -> kv.isEnabled() && kv.getKey().equalsIgnoreCase("Content-Type"));
        if (ct != null && !userCt) h.put("Content-Type", ct);
        for (RestRequest.KeyValue kv : r.getHeaders()) {
            if (kv.isEnabled() && !kv.getKey().isBlank()) h.put(kv.getKey(), kv.getValue());
        }
        switch (r.getAuthType()) {
            case BASIC -> h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (r.getAuthUsername() + ":" + r.getAuthPassword()).getBytes()));
            case BEARER -> h.put("Authorization", "Bearer " + r.getAuthToken());
            case API_KEY -> {
                if (r.getApiKeyLocation() == RestRequest.ApiKeyLocation.HEADER && !r.getApiKeyName().isBlank()) {
                    h.put(r.getApiKeyName(), r.getApiKeyValue());
                }
            }
            case OAUTH2 -> h.put("Authorization",
                    "Bearer <access_token from " + r.getOauthTokenUrl() + ">");
            case NONE -> { /* none */ }
        }
        return h;
    }

    private static boolean hasBody(RestRequest r) {
        return r.getBodyType() != RestRequest.BodyType.NONE && !r.getBody().isBlank();
    }

    // ---- cURL ----
    private static String curl(RestRequest r) {
        StringBuilder sb = new StringBuilder("curl -X ").append(r.getMethod().toUpperCase())
                .append(" '").append(r.requestUri()).append("'");
        headers(r).forEach((k, v) -> sb.append(" \\\n  -H '").append(k).append(": ").append(v).append('\''));
        if (hasBody(r)) sb.append(" \\\n  --data '").append(r.getBody().replace("'", "'\\''")).append('\'');
        return sb.toString();
    }

    // ---- Python (requests) ----
    private static String python(RestRequest r) {
        StringBuilder sb = new StringBuilder("import requests\n\n");
        sb.append("url = ").append(q(r.requestUri())).append('\n');
        sb.append("headers = {\n");
        headers(r).forEach((k, v) -> sb.append("    ").append(q(k)).append(": ").append(q(v)).append(",\n"));
        sb.append("}\n");
        if (hasBody(r)) sb.append("data = ").append(q(r.getBody())).append('\n');
        sb.append("\nresp = requests.request(").append(q(r.getMethod().toUpperCase()))
                .append(", url, headers=headers");
        if (hasBody(r)) sb.append(", data=data");
        sb.append(")\nprint(resp.status_code)\nprint(resp.text)\n");
        return sb.toString();
    }

    // ---- JavaScript (fetch) ----
    private static String javascript(RestRequest r) {
        StringBuilder sb = new StringBuilder("const res = await fetch(").append(q(r.requestUri())).append(", {\n");
        sb.append("  method: ").append(q(r.getMethod().toUpperCase())).append(",\n");
        sb.append("  headers: {\n");
        headers(r).forEach((k, v) -> sb.append("    ").append(q(k)).append(": ").append(q(v)).append(",\n"));
        sb.append("  },\n");
        if (hasBody(r)) sb.append("  body: ").append(q(r.getBody())).append(",\n");
        sb.append("});\nconsole.log(res.status);\nconsole.log(await res.text());\n");
        return sb.toString();
    }

    // ---- Java (java.net.http) ----
    private static String java(RestRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("HttpClient client = HttpClient.newHttpClient();\n");
        sb.append("HttpRequest request = HttpRequest.newBuilder()\n");
        sb.append("    .uri(URI.create(").append(q(r.requestUri())).append("))\n");
        headers(r).forEach((k, v) -> sb.append("    .header(").append(q(k)).append(", ").append(q(v)).append(")\n"));
        String publisher = hasBody(r)
                ? "HttpRequest.BodyPublishers.ofString(" + q(r.getBody()) + ")"
                : "HttpRequest.BodyPublishers.noBody()";
        sb.append("    .method(").append(q(r.getMethod().toUpperCase())).append(", ").append(publisher).append(")\n");
        sb.append("    .build();\n\n");
        sb.append("HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());\n");
        sb.append("System.out.println(response.statusCode());\n");
        sb.append("System.out.println(response.body());\n");
        return sb.toString();
    }

    // ---- PowerShell (Invoke-RestMethod) ----
    private static String powershell(RestRequest r) {
        StringBuilder sb = new StringBuilder("$headers = @{\n");
        headers(r).forEach((k, v) -> sb.append("  ").append(q(k)).append(" = ").append(q(v)).append('\n'));
        sb.append("}\n");
        sb.append("Invoke-RestMethod -Uri ").append(q(r.requestUri()))
                .append(" -Method ").append(r.getMethod().toUpperCase()).append(" -Headers $headers");
        if (hasBody(r)) sb.append(" -Body ").append(q(r.getBody()));
        sb.append('\n');
        return sb.toString();
    }

    private static String q(String s) {
        return '"' + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n")) + '"';
    }

    public static List<Language> languages() {
        return new ArrayList<>(List.of(Language.values()));
    }
}
