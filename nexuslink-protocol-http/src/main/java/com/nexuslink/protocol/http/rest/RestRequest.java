package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable builder-style model for a REST request.
 * Held by the UI and handed to {@link RestExecutionService} for execution.
 */
public final class RestRequest {

    public enum BodyType { NONE, JSON, XML, TEXT, FORM_URLENCODED }

    private String method = "GET";
    private String url = "";
    private final List<KeyValue> queryParams = new ArrayList<>();
    private final List<KeyValue> headers = new ArrayList<>();
    private BodyType bodyType = BodyType.NONE;
    private String body = "";

    // Auth
    public enum AuthType { NONE, BASIC, BEARER, API_KEY, OAUTH2 }
    /** Where an API key is sent. */
    public enum ApiKeyLocation { HEADER, QUERY }
    private AuthType authType = AuthType.NONE;
    private String authUsername = "";
    private String authPassword = "";
    private String authToken = "";
    private String apiKeyName = "X-API-Key";
    private String apiKeyValue = "";
    private ApiKeyLocation apiKeyLocation = ApiKeyLocation.HEADER;
    // OAuth 2.0 client-credentials grant
    private String oauthTokenUrl = "";
    private String oauthClientId = "";
    private String oauthClientSecret = "";
    private String oauthScope = "";

    // Settings
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 30_000;
    private boolean followRedirects = true;

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<KeyValue> getQueryParams() { return queryParams; }
    public List<KeyValue> getHeaders() { return headers; }

    public BodyType getBodyType() { return bodyType; }
    public void setBodyType(BodyType bodyType) { this.bodyType = bodyType; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public AuthType getAuthType() { return authType; }
    public void setAuthType(AuthType authType) { this.authType = authType; }

    public String getAuthUsername() { return authUsername; }
    public void setAuthUsername(String v) { this.authUsername = v; }

    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String v) { this.authPassword = v; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String v) { this.authToken = v; }

    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String v) { this.apiKeyName = v; }

    public String getApiKeyValue() { return apiKeyValue; }
    public void setApiKeyValue(String v) { this.apiKeyValue = v; }

    public ApiKeyLocation getApiKeyLocation() { return apiKeyLocation; }
    public void setApiKeyLocation(ApiKeyLocation v) { this.apiKeyLocation = v; }

    public String getOauthTokenUrl() { return oauthTokenUrl; }
    public void setOauthTokenUrl(String v) { this.oauthTokenUrl = v; }

    public String getOauthClientId() { return oauthClientId; }
    public void setOauthClientId(String v) { this.oauthClientId = v; }

    public String getOauthClientSecret() { return oauthClientSecret; }
    public void setOauthClientSecret(String v) { this.oauthClientSecret = v; }

    public String getOauthScope() { return oauthScope; }
    public void setOauthScope(String v) { this.oauthScope = v; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }

    public boolean isFollowRedirects() { return followRedirects; }
    public void setFollowRedirects(boolean v) { this.followRedirects = v; }

    /** Content-Type implied by the body type. */
    public String contentType() {
        return switch (bodyType) {
            case JSON -> "application/json";
            case XML -> "application/xml";
            case TEXT -> "text/plain";
            case FORM_URLENCODED -> "application/x-www-form-urlencoded";
            case NONE -> null;
        };
    }

    /** Builds the effective URL including enabled query parameters. */
    public String effectiveUrl() {
        var enabled = queryParams.stream().filter(KeyValue::isEnabled)
                .filter(kv -> !kv.getKey().isBlank()).toList();
        if (enabled.isEmpty()) return url;
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? '&' : '?');
        for (int i = 0; i < enabled.size(); i++) {
            if (i > 0) sb.append('&');
            KeyValue kv = enabled.get(i);
            sb.append(urlEncode(kv.getKey())).append('=').append(urlEncode(kv.getValue()));
        }
        return sb.toString();
    }

    /** {@link #effectiveUrl()} plus the API-key query param when API-key auth is in QUERY mode. */
    public String requestUri() {
        String base = effectiveUrl();
        if (authType == AuthType.API_KEY && apiKeyLocation == ApiKeyLocation.QUERY
                && !apiKeyName.isBlank()) {
            base += (base.contains("?") ? '&' : '?')
                    + urlEncode(apiKeyName) + '=' + urlEncode(apiKeyValue);
        }
        return base;
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A single editable key/value row (header or query param). */
    public static final class KeyValue {
        private boolean enabled = true;
        private String key;
        private String value;

        public KeyValue() { this("", ""); }
        public KeyValue(String key, String value) { this.key = key; this.value = value; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }
}
