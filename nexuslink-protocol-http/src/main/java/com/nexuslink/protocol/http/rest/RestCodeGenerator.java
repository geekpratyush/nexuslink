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
    public enum Language { CURL, PYTHON, JAVASCRIPT, NODE_AXIOS, JAVA, CSHARP, GO, RUST, PHP, RUBY, POWERSHELL;
        public String label() {
            return switch (this) {
                case CURL -> "cURL";
                case PYTHON -> "Python (requests)";
                case JAVASCRIPT -> "JavaScript (fetch)";
                case NODE_AXIOS -> "Node.js (axios)";
                case JAVA -> "Java (HttpClient)";
                case CSHARP -> "C# (HttpClient)";
                case GO -> "Go (net/http)";
                case RUST -> "Rust (reqwest)";
                case PHP -> "PHP (curl)";
                case RUBY -> "Ruby (net/http)";
                case POWERSHELL -> "PowerShell";
            };
        }
    }

    public static String generate(Language lang, RestRequest r) {
        return switch (lang) {
            case CURL -> curl(r);
            case PYTHON -> python(r);
            case JAVASCRIPT -> javascript(r);
            case NODE_AXIOS -> nodeAxios(r);
            case JAVA -> java(r);
            case CSHARP -> csharp(r);
            case GO -> go(r);
            case RUST -> rust(r);
            case PHP -> php(r);
            case RUBY -> ruby(r);
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

    // ---- Node.js (axios) ----
    private static String nodeAxios(RestRequest r) {
        StringBuilder sb = new StringBuilder("const axios = require('axios');\n\n");
        sb.append("const res = await axios({\n");
        sb.append("  method: ").append(q(r.getMethod().toUpperCase())).append(",\n");
        sb.append("  url: ").append(q(r.requestUri())).append(",\n");
        sb.append("  headers: {\n");
        headers(r).forEach((k, v) -> sb.append("    ").append(q(k)).append(": ").append(q(v)).append(",\n"));
        sb.append("  },\n");
        if (hasBody(r)) sb.append("  data: ").append(q(r.getBody())).append(",\n");
        sb.append("});\nconsole.log(res.status);\nconsole.log(res.data);\n");
        return sb.toString();
    }

    // ---- C# (System.Net.Http.HttpClient) ----
    private static String csharp(RestRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("using System;\n");
        sb.append("using System.Net.Http;\n");
        sb.append("using System.Text;\n\n");
        sb.append("var client = new HttpClient();\n");
        sb.append("var request = new HttpRequestMessage(new HttpMethod(")
                .append(q(r.getMethod().toUpperCase())).append("), ").append(q(r.requestUri())).append(");\n");
        String contentType = null;
        for (Map.Entry<String, String> e : headers(r).entrySet()) {
            if (e.getKey().equalsIgnoreCase("Content-Type")) { contentType = e.getValue(); continue; }
            sb.append("request.Headers.TryAddWithoutValidation(").append(q(e.getKey()))
                    .append(", ").append(q(e.getValue())).append(");\n");
        }
        if (hasBody(r)) {
            String ct = contentType != null ? contentType
                    : (r.contentType() != null ? r.contentType() : "text/plain");
            sb.append("request.Content = new StringContent(").append(q(r.getBody()))
                    .append(", Encoding.UTF8, ").append(q(ct)).append(");\n");
        }
        sb.append("\nvar response = await client.SendAsync(request);\n");
        sb.append("Console.WriteLine((int)response.StatusCode);\n");
        sb.append("Console.WriteLine(await response.Content.ReadAsStringAsync());\n");
        return sb.toString();
    }

    // ---- Go (net/http) ----
    private static String go(RestRequest r) {
        boolean body = hasBody(r);
        StringBuilder sb = new StringBuilder("package main\n\n");
        sb.append("import (\n");
        sb.append("\t\"fmt\"\n");
        sb.append("\t\"io\"\n");
        sb.append("\t\"net/http\"\n");
        if (body) sb.append("\t\"strings\"\n");
        sb.append(")\n\n");
        sb.append("func main() {\n");
        String reqBody = body ? "strings.NewReader(" + q(r.getBody()) + ")" : "nil";
        if (body) sb.append("\tbody := ").append(reqBody).append("\n");
        sb.append("\treq, _ := http.NewRequest(").append(q(r.getMethod().toUpperCase()))
                .append(", ").append(q(r.requestUri())).append(", ").append(body ? "body" : "nil").append(")\n");
        headers(r).forEach((k, v) -> sb.append("\treq.Header.Set(").append(q(k))
                .append(", ").append(q(v)).append(")\n"));
        sb.append("\tresp, err := http.DefaultClient.Do(req)\n");
        sb.append("\tif err != nil {\n\t\tpanic(err)\n\t}\n");
        sb.append("\tdefer resp.Body.Close()\n");
        sb.append("\tdata, _ := io.ReadAll(resp.Body)\n");
        sb.append("\tfmt.Println(resp.Status)\n");
        sb.append("\tfmt.Println(string(data))\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ---- Rust (reqwest) ----
    private static String rust(RestRequest r) {
        StringBuilder sb = new StringBuilder("use reqwest::header::{HeaderMap, HeaderValue};\n\n");
        sb.append("#[tokio::main]\n");
        sb.append("async fn main() -> Result<(), Box<dyn std::error::Error>> {\n");
        sb.append("    let client = reqwest::Client::new();\n");
        sb.append("    let mut headers = HeaderMap::new();\n");
        headers(r).forEach((k, v) -> sb.append("    headers.insert(").append(q(k))
                .append(", HeaderValue::from_static(").append(q(v)).append("));\n"));
        sb.append("    let res = client\n");
        sb.append("        .request(reqwest::Method::").append(r.getMethod().toUpperCase())
                .append(", ").append(q(r.requestUri())).append(")\n");
        sb.append("        .headers(headers)\n");
        if (hasBody(r)) sb.append("        .body(").append(q(r.getBody())).append(")\n");
        sb.append("        .send()\n");
        sb.append("        .await?;\n");
        sb.append("    println!(\"{}\", res.status());\n");
        sb.append("    println!(\"{}\", res.text().await?);\n");
        sb.append("    Ok(())\n");
        sb.append("}\n");
        return sb.toString();
    }

    // ---- PHP (curl) ----
    private static String php(RestRequest r) {
        StringBuilder sb = new StringBuilder("<?php\n");
        sb.append("$ch = curl_init();\n");
        sb.append("curl_setopt($ch, CURLOPT_URL, ").append(phpQ(r.requestUri())).append(");\n");
        sb.append("curl_setopt($ch, CURLOPT_CUSTOMREQUEST, ")
                .append(phpQ(r.getMethod().toUpperCase())).append(");\n");
        sb.append("curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);\n");
        sb.append("curl_setopt($ch, CURLOPT_HTTPHEADER, [\n");
        headers(r).forEach((k, v) -> sb.append("    ").append(phpQ(k + ": " + v)).append(",\n"));
        sb.append("]);\n");
        if (hasBody(r)) sb.append("curl_setopt($ch, CURLOPT_POSTFIELDS, ")
                .append(phpQ(r.getBody())).append(");\n");
        sb.append("$response = curl_exec($ch);\n");
        sb.append("echo curl_getinfo($ch, CURLINFO_HTTP_CODE) . \"\\n\";\n");
        sb.append("echo $response;\n");
        sb.append("curl_close($ch);\n");
        return sb.toString();
    }

    // ---- Ruby (net/http) ----
    private static String ruby(RestRequest r) {
        StringBuilder sb = new StringBuilder("require 'net/http'\nrequire 'uri'\n\n");
        sb.append("uri = URI(").append(rubyQ(r.requestUri())).append(")\n");
        sb.append("http = Net::HTTP.new(uri.host, uri.port)\n");
        sb.append("http.use_ssl = uri.scheme == \"https\"\n\n");
        sb.append("request = Net::HTTP::").append(rubyMethod(r.getMethod())).append(".new(uri)\n");
        headers(r).forEach((k, v) -> sb.append("request[").append(rubyQ(k)).append("] = ")
                .append(rubyQ(v)).append("\n"));
        if (hasBody(r)) sb.append("request.body = ").append(rubyQ(r.getBody())).append("\n");
        sb.append("\nresponse = http.request(request)\n");
        sb.append("puts response.code\n");
        sb.append("puts response.body\n");
        return sb.toString();
    }

    /** Title-cases an HTTP method to the matching {@code Net::HTTP} request class name. */
    private static String rubyMethod(String m) {
        String u = m == null ? "" : m.trim().toLowerCase();
        if (u.isEmpty()) return "Get";
        return Character.toUpperCase(u.charAt(0)) + u.substring(1);
    }

    /** PHP double-quoted string literal: escapes backslash, quote, {@code $} and newline. */
    private static String phpQ(String s) {
        return '"' + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("$", "\\$").replace("\n", "\\n")) + '"';
    }

    /** Ruby double-quoted string literal: escapes backslash, quote, {@code #} and newline. */
    private static String rubyQ(String s) {
        return '"' + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("#", "\\#").replace("\n", "\\n")) + '"';
    }

    private static String q(String s) {
        return '"' + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n")) + '"';
    }

    public static List<Language> languages() {
        return new ArrayList<>(List.of(Language.values()));
    }
}
