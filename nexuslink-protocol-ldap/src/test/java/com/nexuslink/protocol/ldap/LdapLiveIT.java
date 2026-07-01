package com.nexuslink.protocol.ldap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live LDAP tests against the local {@code test-env} OpenLDAP (seeded with alice/bob under
 * dc=nexuslink,dc=dev).
 * <pre>docker compose -f test-env/docker-compose.yml up -d openldap</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class LdapLiveIT {

    private static final String BASE = "dc=nexuslink,dc=dev";
    private static final String ADMIN = "cn=admin," + BASE;

    @Test
    void bindSearchAndNamingContexts() throws Exception {
        try (LdapService svc = new LdapService()) {
            svc.connect("localhost", 389, ADMIN, "admin", false);
            assertTrue(svc.isConnected());

            assertTrue(svc.namingContexts().stream().anyMatch(c -> c.equalsIgnoreCase(BASE)));

            List<LdapService.Entry> people = svc.search(
                    "ou=people," + BASE, "(objectClass=inetOrgPerson)", "sub", 0);
            assertEquals(2, people.size());
            assertTrue(people.stream().anyMatch(e -> e.dn().startsWith("uid=alice")));

            List<LdapService.Entry> alice = svc.search(
                    "ou=people," + BASE, "(uid=alice)", "sub", 0, "mail");
            assertEquals(1, alice.size());
            assertEquals(List.of("alice@nexuslink.dev"), alice.get(0).attributes().get("mail"));
        }
    }
}
