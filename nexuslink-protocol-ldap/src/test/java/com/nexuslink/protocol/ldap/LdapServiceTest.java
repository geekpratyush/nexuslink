package com.nexuslink.protocol.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link LdapService} end-to-end against UnboundID's bundled in-memory directory server,
 * so connect/bind/search are verified with no external LDAP. {@link LdapService#scopeOf} is checked
 * directly as a pure mapping.
 */
class LdapServiceTest {

    private InMemoryDirectoryServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=Directory Manager", "secret");
        config.setListenerConfigs(
                com.unboundid.ldap.listener.InMemoryListenerConfig.createLDAPConfig("default", 0));
        server = new InMemoryDirectoryServer(config);
        server.startListening();
        port = server.getListenPort();

        server.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example");
        server.add("dn: ou=people,dc=example,dc=com", "objectClass: organizationalUnit", "ou: people");
        server.add("dn: uid=alice,ou=people,dc=example,dc=com",
                "objectClass: inetOrgPerson", "uid: alice", "cn: Alice Adams", "sn: Adams",
                "mail: alice@example.com");
        server.add("dn: uid=bob,ou=people,dc=example,dc=com",
                "objectClass: inetOrgPerson", "uid: bob", "cn: Bob Brown", "sn: Brown",
                "mail: bob@example.com");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.shutDown(true);
    }

    @Test
    void connectsAndBindsThenSearchesSubtree() throws Exception {
        try (LdapService ldap = new LdapService()) {
            ldap.connect("localhost", port, "cn=Directory Manager", "secret", false);
            assertTrue(ldap.isConnected());

            List<LdapService.Entry> people = ldap.search(
                    "ou=people,dc=example,dc=com", "(objectClass=inetOrgPerson)", "sub", 0);
            assertEquals(2, people.size());
        }
    }

    @Test
    void filterNarrowsResultsAndDecodesAttributes() throws Exception {
        try (LdapService ldap = new LdapService()) {
            ldap.connect("localhost", port, "cn=Directory Manager", "secret", false);

            List<LdapService.Entry> hits = ldap.search(
                    "dc=example,dc=com", "(uid=alice)", "sub", 0);
            assertEquals(1, hits.size());
            LdapService.Entry alice = hits.get(0);
            assertEquals("uid=alice,ou=people,dc=example,dc=com", alice.dn());
            assertEquals(List.of("alice@example.com"), alice.attributes().get("mail"));
            assertEquals(List.of("Alice Adams"), alice.attributes().get("cn"));
        }
    }

    @Test
    void baseScopeReturnsOnlyTheBaseEntry() throws Exception {
        try (LdapService ldap = new LdapService()) {
            ldap.connect("localhost", port, "cn=Directory Manager", "secret", false);
            List<LdapService.Entry> base = ldap.search(
                    "ou=people,dc=example,dc=com", "(objectClass=*)", "base", 0);
            assertEquals(1, base.size());
            assertEquals("ou=people,dc=example,dc=com", base.get(0).dn());
        }
    }

    @Test
    void anonymousConnectThenSearchWorks() throws Exception {
        try (LdapService ldap = new LdapService()) {
            ldap.connect("localhost", port, "", "", false);
            List<LdapService.Entry> all = ldap.search("dc=example,dc=com", null, "sub", 0);
            assertFalse(all.isEmpty());
        }
    }

    @Test
    void searchBeforeConnectIsRejected() {
        LdapService ldap = new LdapService();
        assertThrows(IllegalStateException.class,
                () -> ldap.search("dc=example,dc=com", "(uid=alice)", "sub", 0));
    }

    @Test
    void scopeOfMapsTextToEnum() {
        assertEquals(SearchScope.BASE, LdapService.scopeOf("base"));
        assertEquals(SearchScope.ONE, LdapService.scopeOf("one"));
        assertEquals(SearchScope.SUB, LdapService.scopeOf("sub"));
        assertEquals(SearchScope.SUB, LdapService.scopeOf("anything-else"));
        assertEquals(SearchScope.SUB, LdapService.scopeOf(null));
    }
}
