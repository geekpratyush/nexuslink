package com.nexuslink.protocol.ldap;

import com.unboundid.ldap.sdk.AddRequest;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ResultCode;
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

    /** Which way a {@link Mod} changes an attribute on an existing entry. */
    public enum ModType {
        /** Replace all of the attribute's values with the supplied ones (empty list removes it). */
        REPLACE,
        /** Add the supplied values to the attribute, keeping existing ones. */
        ADD,
        /** Delete the supplied values (or, when none are given, the whole attribute). */
        DELETE
    }

    /** A single attribute change applied by {@link #modifyEntry}. */
    public record Mod(ModType type, String attribute, List<String> values) {
        public Mod {
            type = type == null ? ModType.REPLACE : type;
            values = values == null ? List.of() : List.copyOf(values);
        }

        public static Mod replace(String attribute, List<String> values) {
            return new Mod(ModType.REPLACE, attribute, values);
        }

        public static Mod add(String attribute, List<String> values) {
            return new Mod(ModType.ADD, attribute, values);
        }

        public static Mod delete(String attribute) {
            return new Mod(ModType.DELETE, attribute, List.of());
        }
    }

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

    /**
     * Adds a new entry at {@code dn} with the given attributes (name &rarr; values). Values for each
     * attribute are written in iteration order; an {@code objectClass} attribute is normally required
     * by the server. Fails with {@link LDAPException} if the DN already exists or the data is invalid.
     */
    public void addEntry(String dn, Map<String, List<String>> attributes) throws LDAPException {
        List<Attribute> attrs = new ArrayList<>();
        if (attributes != null) {
            for (Map.Entry<String, List<String>> e : attributes.entrySet()) {
                List<String> values = e.getValue();
                if (values == null || values.isEmpty()) continue;
                attrs.add(new Attribute(e.getKey(), values));
            }
        }
        require().add(new AddRequest(dn, attrs));
    }

    /**
     * Applies attribute {@code modifications} (replace / add / delete) to the entry at {@code dn} in a
     * single modify operation. Fails with {@link LDAPException} if the entry is missing or a change is
     * rejected (e.g. deleting a non-existent value).
     */
    public void modifyEntry(String dn, List<Mod> modifications) throws LDAPException {
        List<Modification> mods = new ArrayList<>();
        if (modifications != null) {
            for (Mod m : modifications) {
                mods.add(toModification(m));
            }
        }
        require().modify(dn, mods);
    }

    /** Deletes the leaf entry at {@code dn}. Fails with {@link LDAPException} if it is missing or has children. */
    public void deleteEntry(String dn) throws LDAPException {
        require().delete(dn);
    }

    /** Whether {@link #applyEntry} created a new entry or updated an existing one. */
    public enum ApplyResult {
        /** The entry did not exist and was added. */
        ADDED,
        /** The entry already existed; its supplied attributes were replaced. */
        MODIFIED
    }

    /**
     * Applies one LDIF-style entry to the directory: adds it at {@code dn}, or — when an entry already
     * exists there — REPLACEs each supplied attribute's values on the existing entry (a re-import of
     * the same DN updates it in place rather than failing). The DN itself is never renamed. Used by the
     * LDIF import flow so a file mixing new and existing entries lands cleanly. Returns whether the
     * entry was {@link ApplyResult#ADDED added} or {@link ApplyResult#MODIFIED modified}.
     */
    public ApplyResult applyEntry(String dn, Map<String, List<String>> attributes) throws LDAPException {
        try {
            addEntry(dn, attributes);
            return ApplyResult.ADDED;
        } catch (LDAPException e) {
            if (e.getResultCode() != ResultCode.ENTRY_ALREADY_EXISTS) {
                throw e;
            }
            List<Mod> mods = new ArrayList<>();
            if (attributes != null) {
                for (Map.Entry<String, List<String>> a : attributes.entrySet()) {
                    List<String> values = a.getValue();
                    if (values == null || values.isEmpty()) continue;
                    mods.add(Mod.replace(a.getKey(), values));
                }
            }
            modifyEntry(dn, mods);
            return ApplyResult.MODIFIED;
        }
    }

    private static Modification toModification(Mod m) {
        ModificationType type = switch (m.type()) {
            case ADD -> ModificationType.ADD;
            case DELETE -> ModificationType.DELETE;
            case REPLACE -> ModificationType.REPLACE;
        };
        String[] values = m.values().toArray(new String[0]);
        return values.length == 0
                ? new Modification(type, m.attribute())
                : new Modification(type, m.attribute(), values);
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
