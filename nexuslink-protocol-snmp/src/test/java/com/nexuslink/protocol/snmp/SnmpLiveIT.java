package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live SNMP GET/WALK against the local {@code test-env} net-snmp agent (community "public",
 * mapped to UDP 1161 on the host).
 * <pre>docker compose -f test-env/docker-compose.yml up -d snmpd</pre>
 * Run with {@code -Dnexuslink.it=true}.
 */
@EnabledIfSystemProperty(named = "nexuslink.it", matches = "true")
class SnmpLiveIT {

    private static final String SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    private static final String SYSTEM = "1.3.6.1.2.1.1";

    @Test
    void getSysDescr() throws Exception {
        try (SnmpService svc = new SnmpService()) {
            svc.open("localhost", 1161, "public", "2c");
            assertTrue(svc.isOpen());
            List<SnmpService.VarBind> vbs = svc.get(SYS_DESCR);
            assertEquals(1, vbs.size());
            assertEquals(SYS_DESCR, vbs.get(0).oid());
            assertNotNull(vbs.get(0).value());
            assertFalse(vbs.get(0).value().isBlank());
        }
    }

    @Test
    void walkSystemSubtree() throws Exception {
        try (SnmpService svc = new SnmpService()) {
            svc.open("localhost", 1161, "public", "2c");
            List<SnmpService.VarBind> vbs = svc.walk(SYSTEM, 50);
            assertFalse(vbs.isEmpty(), "walk of the system subtree returned no varbinds");
            assertTrue(vbs.stream().allMatch(v -> v.oid().startsWith(SYSTEM)));
        }
    }
}
