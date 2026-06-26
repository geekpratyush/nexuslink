package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the pure {@link SnmpService} logic — version mapping, address normalization, OID
 * validation, and variable-binding decoding — offline (no SNMP agent needed).
 */
class SnmpServiceTest {

    @Test
    void versionMappingCoversV1V2cV3AndDefaults() {
        assertEquals(SnmpConstants.version1, SnmpService.versionOf("1"));
        assertEquals(SnmpConstants.version1, SnmpService.versionOf("v1"));
        assertEquals(SnmpConstants.version2c, SnmpService.versionOf("2c"));
        assertEquals(SnmpConstants.version2c, SnmpService.versionOf("v2c"));
        assertEquals(SnmpConstants.version3, SnmpService.versionOf("3"));
        assertEquals(SnmpConstants.version2c, SnmpService.versionOf("nonsense"));
        assertEquals(SnmpConstants.version2c, SnmpService.versionOf(null));
    }

    @Test
    void addressNormalizationAddsUdpSchemeAndDefaultPort() {
        assertEquals("udp:10.0.0.1/161", SnmpService.normalizeAddress("10.0.0.1", 0));
        assertEquals("udp:host.example.com/1161", SnmpService.normalizeAddress("host.example.com", 1161));
        assertEquals("udp:127.0.0.1/161", SnmpService.normalizeAddress("", 161));
    }

    @Test
    void oidValidationAcceptsDottedNumbersAndRejectsGarbage() {
        assertTrue(SnmpService.isValidOid("1.3.6.1.2.1.1.5.0"));
        assertTrue(SnmpService.isValidOid(".1.3.6.1"));     // leading dot allowed
        assertFalse(SnmpService.isValidOid("1.3.x.1"));
        assertFalse(SnmpService.isValidOid("1..3"));
        assertFalse(SnmpService.isValidOid(""));
        assertFalse(SnmpService.isValidOid(null));
    }

    @Test
    void varBindDecodesOidAndValue() {
        VarBindCheck(new VariableBinding(new OID("1.3.6.1.2.1.1.5.0"), new OctetString("router-1")),
                "1.3.6.1.2.1.1.5.0", "router-1");
        VarBindCheck(new VariableBinding(new OID("1.3.6.1.2.1.1.7.0"), new Integer32(72)),
                "1.3.6.1.2.1.1.7.0", "72");
    }

    private static void VarBindCheck(VariableBinding vb, String expectedOid, String expectedValue) {
        SnmpService.VarBind decoded = SnmpService.toVarBind(vb);
        assertEquals(expectedOid, decoded.oid());
        assertEquals(expectedValue, decoded.value());
        assertFalse(decoded.type().isBlank());
    }
}
