package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;
import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nexuslink.protocol.snmp.SnmpV3Config.AuthProtocol;
import com.nexuslink.protocol.snmp.SnmpV3Config.PrivProtocol;

/**
 * Offline unit-tests for the pure {@link SnmpV3Usm} mapper: each auth / privacy enum maps to the
 * expected SNMP4J OID, each security level to the expected {@code SecurityLevel} int, and a
 * {@link UsmUser} is built with the right fields for each USM security level. Deterministic; no agent.
 */
class SnmpV3UsmTest {

    @Test
    void authProtocolMapsToExpectedOid() {
        assertNull(SnmpV3Usm.authProtocolOid(AuthProtocol.NONE));
        assertNull(SnmpV3Usm.authProtocolOid(null));
        assertEquals(AuthMD5.ID, SnmpV3Usm.authProtocolOid(AuthProtocol.MD5));
        assertEquals(AuthSHA.ID, SnmpV3Usm.authProtocolOid(AuthProtocol.SHA));
        assertEquals(AuthHMAC192SHA256.ID, SnmpV3Usm.authProtocolOid(AuthProtocol.SHA_256));
    }

    @Test
    void privProtocolMapsToExpectedOid() {
        assertNull(SnmpV3Usm.privProtocolOid(PrivProtocol.NONE));
        assertNull(SnmpV3Usm.privProtocolOid(null));
        assertEquals(PrivDES.ID, SnmpV3Usm.privProtocolOid(PrivProtocol.DES));
        assertEquals(PrivAES128.ID, SnmpV3Usm.privProtocolOid(PrivProtocol.AES_128));
    }

    @Test
    void securityLevelMapsToExpectedInt() {
        assertEquals(SecurityLevel.NOAUTH_NOPRIV,
                SnmpV3Usm.securityLevel(SnmpV3Config.SecurityLevel.NO_AUTH_NO_PRIV));
        assertEquals(SecurityLevel.AUTH_NOPRIV,
                SnmpV3Usm.securityLevel(SnmpV3Config.SecurityLevel.AUTH_NO_PRIV));
        assertEquals(SecurityLevel.AUTH_PRIV,
                SnmpV3Usm.securityLevel(SnmpV3Config.SecurityLevel.AUTH_PRIV));
        assertEquals(SecurityLevel.NOAUTH_NOPRIV, SnmpV3Usm.securityLevel(null));
    }

    @Test
    void securityNameIsNeverNull() {
        assertEquals(new OctetString("alice"),
                SnmpV3Usm.securityName(SnmpV3Config.noAuthNoPriv("alice")));
        assertEquals(new OctetString(""),
                SnmpV3Usm.securityName(SnmpV3Config.noAuthNoPriv(null)));
    }

    @Test
    void usmUserForNoAuthNoPrivCarriesNoProtocols() {
        UsmUser user = SnmpV3Usm.toUsmUser(SnmpV3Config.noAuthNoPriv("alice"));
        assertEquals(new OctetString("alice"), user.getSecurityName());
        assertNull(user.getAuthenticationProtocol());
        assertNull(user.getPrivacyProtocol());
        assertNull(user.getAuthenticationPassphrase());
        assertNull(user.getPrivacyPassphrase());
    }

    @Test
    void usmUserForAuthNoPrivCarriesAuthOnly() {
        SnmpV3Config cfg = new SnmpV3Config("bob", SnmpV3Config.SecurityLevel.AUTH_NO_PRIV,
                AuthProtocol.SHA, "authpass1", PrivProtocol.AES_128, "privpass1", null);
        UsmUser user = SnmpV3Usm.toUsmUser(cfg);
        assertEquals(new OctetString("bob"), user.getSecurityName());
        assertEquals(AuthSHA.ID, user.getAuthenticationProtocol());
        assertEquals(new OctetString("authpass1"), user.getAuthenticationPassphrase());
        // Privacy is suppressed because the level is authNoPriv, even though the config supplied it.
        assertNull(user.getPrivacyProtocol());
        assertNull(user.getPrivacyPassphrase());
    }

    @Test
    void usmUserForAuthPrivCarriesAuthAndPriv() {
        SnmpV3Config cfg = new SnmpV3Config("carol", SnmpV3Config.SecurityLevel.AUTH_PRIV,
                AuthProtocol.MD5, "authpass2", PrivProtocol.DES, "privpass2", "ctx1");
        UsmUser user = SnmpV3Usm.toUsmUser(cfg);
        assertEquals(new OctetString("carol"), user.getSecurityName());
        assertEquals(AuthMD5.ID, user.getAuthenticationProtocol());
        assertEquals(new OctetString("authpass2"), user.getAuthenticationPassphrase());
        assertEquals(PrivDES.ID, user.getPrivacyProtocol());
        assertEquals(new OctetString("privpass2"), user.getPrivacyPassphrase());
    }
}
