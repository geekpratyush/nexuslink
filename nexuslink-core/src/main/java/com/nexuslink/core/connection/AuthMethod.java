package com.nexuslink.core.connection;

/**
 * The ways a connection can authenticate. Intentionally broad so enterprise connections aren't
 * limited to password-less or no-security modes — each protocol surfaces the subset it supports.
 */
public enum AuthMethod {
    NONE("None"),
    BASIC("Username / Password"),
    BEARER_TOKEN("Bearer token"),
    API_KEY("API key"),
    CONNECTION_STRING("Connection string"),
    TLS("TLS (server cert)"),
    MUTUAL_TLS("Mutual TLS (client cert)"),
    SASL_PLAIN("SASL/PLAIN"),
    SASL_SCRAM("SASL/SCRAM"),
    KERBEROS("Kerberos / GSSAPI"),
    OAUTH2("OAuth 2.0"),
    SSH_KEY("SSH key"),
    AWS_SIGV4("AWS Signature v4");

    private final String label;
    AuthMethod(String label) { this.label = label; }
    public String label() { return label; }
}
