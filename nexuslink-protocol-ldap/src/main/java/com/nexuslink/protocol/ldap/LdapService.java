package com.nexuslink.protocol.ldap;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * LDAP / Active Directory client over the UnboundID LDAP SDK. Connects (plain or LDAPS), optionally
 * binds with a DN + password, reports the directory's naming contexts, and runs subtree/one-level/
 * base searches returning decoded entries. Blocking I/O — the UI drives it on a background task,
 * mirroring the other protocol services.
 *
 * <p>The {@link #scopeOf} mapping is a pure, unit-tested helper; the connect/search paths are tested
 * end-to-end against UnboundID's bundled in-memory directory server (no external LDAP needed).
 */
public final class LdapService implements AutoCloseable {

    /** A directory entry: its DN plus attributes decoded to string lists (binary values summarised). */
    public record Entry(String dn, Map<String, List<String>> attributes) {}

    private volatile LDAPConnection connection;

    /**
     * Connects to {@code host:port}. When {@code bindDn} is blank the bind is anonymous. With
     * {@code useSsl} true the connection is LDAPS and the server certificate is trusted blindly
     * (NexusLink is a testing tool); set it false for plain LDAP / StartTLS-less connections.
     */
    public void connect(String host, int port, String bindDn, String password, boolean useSsl)
            throws LDAPException, java.security.GeneralSecurityException {
        close();
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(15_000);
        options.setResponseTimeoutMillis(30_000);

        LDAPConnection conn;
        if (useSsl) {
            SSLUtil sslUtil = new SSLUtil(new TrustAllTrustManager());
            conn = new LDAPConnection(sslUtil.createSSLSocketFactory(), options);
        } else {
            conn = new LDAPConnection(options);
        }
        conn.connect(host, port);
        if (bindDn != null && !bindDn.isBlank()) {
            conn.bind(bindDn, password == null ? "" : password);
        }
        this.connection = conn;
    }

    public boolean isConnected() {
        LDAPConnection c = connection;
        return c != null && c.isConnected();
    }

    /**
     * Returns the directory's naming contexts (subtree roots) read from the Root DSE — a natural
     * starting point for the tree browser. Empty if the server doesn't publish them.
     */
    public List<String> namingContexts() throws LDAPException {
        var rootDse = require().getRootDSE();
        if (rootDse == null) return List.of();
        String[] contexts = rootDse.getNamingContextDNs();
        return contexts == null ? List.of() : List.of(contexts);
    }

    /**
     * Searches under {@code baseDn} with an RFC-4515 {@code filter}. {@code scope} is one of
     * {@code base}, {@code one}, {@code sub} (case-insensitive). {@code sizeLimit} caps the result
     * count (0 ⇒ server default). Pass {@code attributes} to restrict returned attributes, or none
     * for all user attributes.
     */
    public List<Entry> search(String baseDn, String filter, String scope, int sizeLimit,
                              String... attributes) throws LDAPException {
        String effectiveFilter = (filter == null || filter.isBlank()) ? "(objectClass=*)" : filter;
        SearchRequest request = new SearchRequest(baseDn, scopeOf(scope), effectiveFilter, attributes);
        if (sizeLimit > 0) request.setSizeLimit(sizeLimit);
        SearchResult result = require().search(request);

        List<Entry> entries = new ArrayList<>();
        for (SearchResultEntry e : result.getSearchEntries()) {
            entries.add(new Entry(e.getDN(), decode(e)));
        }
        return entries;
    }

    /** Maps a textual scope to the SDK enum; defaults to subtree for unknown/blank input. */
    public static SearchScope scopeOf(String scope) {
        if (scope == null) return SearchScope.SUB;
        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "base", "object" -> SearchScope.BASE;
            case "one", "onelevel", "single" -> SearchScope.ONE;
            default -> SearchScope.SUB;
        };
    }

    private static Map<String, List<String>> decode(SearchResultEntry entry) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Attribute a : entry.getAttributes()) {
            List<String> values = new ArrayList<>();
            for (String v : a.getValues()) values.add(v);
            out.put(a.getName(), values);
        }
        return out;
    }

    private LDAPConnection require() {
        LDAPConnection c = connection;
        if (c == null || !c.isConnected()) throw new IllegalStateException("Not connected to an LDAP server");
        return c;
    }

    @Override
    public void close() {
        LDAPConnection c = connection;
        connection = null;
        if (c != null) c.close();
    }
}
