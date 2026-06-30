package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexuslink.protocol.snmp.SnmpV3Config.AuthProtocol;
import com.nexuslink.protocol.snmp.SnmpV3Config.PrivProtocol;

/**
 * Construction smoke-tests for the SNMPv3 wire seam: building the {@link UserTarget} and
 * {@link ScopedPDU} from a {@link SnmpV3Config} without sending anything. No socket, no agent — this
 * verifies the version, security name, security level, address and context-name wiring.
 */
class SnmpV3WireTest {

    @Test
    void v3TargetCarriesVersionSecurityNameLevelAndAddress() {
        SnmpV3Config cfg = new SnmpV3Config("carol", SnmpV3Config.SecurityLevel.AUTH_PRIV,
                AuthProtocol.SHA, "authpass2", PrivProtocol.AES_128, "privpass2", "ctx1");
        UserTarget<UdpAddress> t = SnmpService.v3Target(cfg, "10.0.0.5", 1161);

        assertEquals(SnmpConstants.version3, t.getVersion());
        assertEquals(new OctetString("carol"), t.getSecurityName());
        assertEquals(SecurityLevel.AUTH_PRIV, t.getSecurityLevel());
        assertEquals("10.0.0.5/1161", t.getAddress().toString());
    }

    @Test
    void v3TargetDefaultsPortAndUsesNoAuthLevel() {
        UserTarget<UdpAddress> t = SnmpService.v3Target(SnmpV3Config.noAuthNoPriv("alice"), "10.0.0.9", 0);
        assertEquals(SecurityLevel.NOAUTH_NOPRIV, t.getSecurityLevel());
        assertEquals("10.0.0.9/161", t.getAddress().toString());
    }

    @Test
    void v3PduSetsTypeAndContextName() {
        SnmpV3Config cfg = new SnmpV3Config("carol", SnmpV3Config.SecurityLevel.AUTH_PRIV,
                AuthProtocol.SHA, "authpass2", PrivProtocol.AES_128, "privpass2", "ctx1");
        ScopedPDU pdu = SnmpService.v3Pdu(cfg, PDU.GETNEXT);
        assertEquals(PDU.GETNEXT, pdu.getType());
        assertEquals(new OctetString("ctx1"), pdu.getContextName());
    }

    @Test
    void v3PduWithoutContextLeavesContextEmpty() {
        ScopedPDU pdu = SnmpService.v3Pdu(SnmpV3Config.noAuthNoPriv("alice"), PDU.GET);
        assertEquals(PDU.GET, pdu.getType());
        assertTrue(pdu.getContextName() == null || pdu.getContextName().length() == 0);
    }

    @Test
    void getV3RejectsInvalidConfig() {
        // authPriv with no protocols/passphrases fails validation before any I/O is attempted.
        SnmpV3Config invalid = new SnmpV3Config("bob", SnmpV3Config.SecurityLevel.AUTH_PRIV,
                AuthProtocol.NONE, null, PrivProtocol.NONE, null, null);
        try (SnmpService svc = new SnmpService()) {
            assertThrows(IllegalArgumentException.class,
                    () -> svc.getV3(invalid, "127.0.0.1", "1.3.6.1.2.1.1.1.0"));
            assertThrows(IllegalArgumentException.class,
                    () -> svc.walkV3(null, "127.0.0.1", "1.3.6.1.2.1.1", 0));
        }
    }
}
