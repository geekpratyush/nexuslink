package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

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
    public enum AuthType { NONE, BASIC, BEARER, API_KEY, OAUTH2, AWS_SIGV4, DIGEST, HMAC }
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
    // AWS Signature v4 (Digest reuses authUsername/authPassword)
    private String awsRegion = "us-east-1";
    private String awsService = "execute-api";
    private String awsAccessKey = "";
    private String awsSecretKey = "";
    private String awsSessionToken = "";
    // Generic HMAC auth (sign a templated canonical string with a shared secret)
    private HmacAuthenticator.Algorithm hmacAlgorithm = HmacAuthenticator.Algorithm.HMAC_SHA256;
    private HmacAuthenticator.Encoding hmacEncoding = HmacAuthenticator.Encoding.BASE64;
    private String hmacKeyId = "";
    private String hmacSecret = "";
    private String hmacStringToSign = "{method}\\n{path}\\n{date}";
    private String hmacHeaderName = "Authorization";
    private String hmacHeaderValue = "HMAC {signature}";

    // TLS / mTLS (trust store verifies the server; key store presents a client cert for mutual TLS)
    private String tlsTrustStorePath = "";
    private String tlsTrustStorePassword = "";
    private String tlsKeyStorePath = "";
    private String tlsKeyStorePassword = "";
    private boolean tlsTrustAll = false;

    // Settings
    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs = 30_000;
    private boolean followRedirects = true;

    // Response assertions ("tests") authored in the UI and evaluated after each call
    private final List<AssertionSpec> assertions = new ArrayList<>();

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<KeyValue> getQueryParams() { return queryParams; }
    public List<KeyValue> getHeaders() { return headers; }

    public List<AssertionSpec> getAssertions() { return assertions; }

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

    public String getAwsRegion() { return awsRegion; }
    public void setAwsRegion(String v) { this.awsRegion = v; }

    public String getAwsService() { return awsService; }
    public void setAwsService(String v) { this.awsService = v; }

    public String getAwsAccessKey() { return awsAccessKey; }
    public void setAwsAccessKey(String v) { this.awsAccessKey = v; }

    public String getAwsSecretKey() { return awsSecretKey; }
    public void setAwsSecretKey(String v) { this.awsSecretKey = v; }

    public String getAwsSessionToken() { return awsSessionToken; }
    public void setAwsSessionToken(String v) { this.awsSessionToken = v; }

    public HmacAuthenticator.Algorithm getHmacAlgorithm() { return hmacAlgorithm; }
    public void setHmacAlgorithm(HmacAuthenticator.Algorithm v) { this.hmacAlgorithm = v; }

    public HmacAuthenticator.Encoding getHmacEncoding() { return hmacEncoding; }
    public void setHmacEncoding(HmacAuthenticator.Encoding v) { this.hmacEncoding = v; }

    public String getHmacKeyId() { return hmacKeyId; }
    public void setHmacKeyId(String v) { this.hmacKeyId = v; }

    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String v) { this.hmacSecret = v; }

    public String getHmacStringToSign() { return hmacStringToSign; }
    public void setHmacStringToSign(String v) { this.hmacStringToSign = v; }

    public String getHmacHeaderName() { return hmacHeaderName; }
    public void setHmacHeaderName(String v) { this.hmacHeaderName = v; }

    public String getHmacHeaderValue() { return hmacHeaderValue; }
    public void setHmacHeaderValue(String v) { this.hmacHeaderValue = v; }

    public String getTlsTrustStorePath() { return tlsTrustStorePath; }
    public void setTlsTrustStorePath(String v) { this.tlsTrustStorePath = v; }

    public String getTlsTrustStorePassword() { return tlsTrustStorePassword; }
    public void setTlsTrustStorePassword(String v) { this.tlsTrustStorePassword = v; }

    public String getTlsKeyStorePath() { return tlsKeyStorePath; }
    public void setTlsKeyStorePath(String v) { this.tlsKeyStorePath = v; }

    public String getTlsKeyStorePassword() { return tlsKeyStorePassword; }
    public void setTlsKeyStorePassword(String v) { this.tlsKeyStorePassword = v; }

    public boolean isTlsTrustAll() { return tlsTrustAll; }
    public void setTlsTrustAll(boolean v) { this.tlsTrustAll = v; }

    /** Builds the {@link TlsConfig} for this request (empty paths ⇒ JDK default trust). */
    public com.nexuslink.security.tls.TlsConfig tlsConfig() {
        return new com.nexuslink.security.tls.TlsConfig(
                blankToNull(tlsTrustStorePath), tlsTrustStorePassword == null ? null : tlsTrustStorePassword.toCharArray(), null,
                blankToNull(tlsKeyStorePath), tlsKeyStorePassword == null ? null : tlsKeyStorePassword.toCharArray(), null,
                tlsTrustAll);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }

    public boolean isFollowRedirects() { return followRedirects; }
    public void setFollowRedirects(boolean v) { this.followRedirects = v; }

    /**
     * Returns a deep copy of this request with every string field passed through {@code fn}, used to
     * resolve {@code ${VAR}} references at send time without mutating the editor's bound model. The
     * UI keeps the templated request (so history/replay stay parameterised); only the executed copy
     * carries resolved values.
     */
    public RestRequest interpolated(UnaryOperator<String> fn) {
        RestRequest r = new RestRequest();
        r.method = method;
        r.url = fn.apply(url);
        for (KeyValue kv : queryParams) {
            KeyValue c = new KeyValue(fn.apply(kv.key), fn.apply(kv.value));
            c.enabled = kv.enabled;
            r.queryParams.add(c);
        }
        for (KeyValue kv : headers) {
            KeyValue c = new KeyValue(fn.apply(kv.key), fn.apply(kv.value));
            c.enabled = kv.enabled;
            r.headers.add(c);
        }
        r.bodyType = bodyType;
        r.body = fn.apply(body);
        r.authType = authType;
        r.authUsername = fn.apply(authUsername);
        r.authPassword = fn.apply(authPassword);
        r.authToken = fn.apply(authToken);
        r.apiKeyName = fn.apply(apiKeyName);
        r.apiKeyValue = fn.apply(apiKeyValue);
        r.apiKeyLocation = apiKeyLocation;
        r.oauthTokenUrl = fn.apply(oauthTokenUrl);
        r.oauthClientId = fn.apply(oauthClientId);
        r.oauthClientSecret = fn.apply(oauthClientSecret);
        r.oauthScope = fn.apply(oauthScope);
        r.awsRegion = fn.apply(awsRegion);
        r.awsService = fn.apply(awsService);
        r.awsAccessKey = fn.apply(awsAccessKey);
        r.awsSecretKey = fn.apply(awsSecretKey);
        r.awsSessionToken = fn.apply(awsSessionToken);
        r.hmacAlgorithm = hmacAlgorithm;
        r.hmacEncoding = hmacEncoding;
        r.hmacKeyId = fn.apply(hmacKeyId);
        r.hmacSecret = fn.apply(hmacSecret);
        r.hmacStringToSign = fn.apply(hmacStringToSign);
        r.hmacHeaderName = fn.apply(hmacHeaderName);
        r.hmacHeaderValue = fn.apply(hmacHeaderValue);
        r.tlsTrustStorePath = fn.apply(tlsTrustStorePath);
        r.tlsTrustStorePassword = fn.apply(tlsTrustStorePassword);
        r.tlsKeyStorePath = fn.apply(tlsKeyStorePath);
        r.tlsKeyStorePassword = fn.apply(tlsKeyStorePassword);
        r.tlsTrustAll = tlsTrustAll;
        r.connectTimeoutMs = connectTimeoutMs;
        r.readTimeoutMs = readTimeoutMs;
        r.followRedirects = followRedirects;
        for (AssertionSpec a : assertions) {
            AssertionSpec c = new AssertionSpec(a.getType(),
                    fn.apply(a.getName()), fn.apply(a.getTarget()), a.getMax());
            c.setEnabled(a.isEnabled());
            r.assertions.add(c);
        }
        return r;
    }

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
