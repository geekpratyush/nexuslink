package com.nexuslink.protocol.snmp;

import org.junit.jupiter.api.Test;

import com.nexuslink.protocol.snmp.SnmpV3Config.AuthProtocol;
import com.nexuslink.protocol.snmp.SnmpV3Config.PrivProtocol;
import com.nexuslink.protocol.snmp.SnmpV3Config.SecurityLevel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the pure {@link SnmpV3Config} USM model — enum parsing, constructor normalisation, and
 * the per-security-level validation rules. No live USM engine involved.
 */
class SnmpV3ConfigTest {

    @Test
    void noAuthNoPrivIsValidWithJustAUsername() {
        SnmpV3Config cfg = SnmpV3Config.noAuthNoPriv("monitor");
        assertTrue(cfg.isValid());
        assertEquals(List.of(), cfg.validate());
    }

    @Test
    void usernameIsAlwaysRequired() {
        SnmpV3Config cfg = SnmpV3Config.noAuthNoPriv("  ");
        assertFalse(cfg.isValid());
        assertTrue(cfg.validate().stream().anyMatch(m -> m.contains("Security name")));
    }

    @Test
    void authNoPrivRequiresProtocolAndPassphrase() {
        SnmpV3Config missing = new SnmpV3Config("u", SecurityLevel.AUTH_NO_PRIV,
                AuthProtocol.NONE, null, PrivProtocol.NONE, null, null);
        List<String> errors = missing.validate();
        assertTrue(errors.stream().anyMatch(m -> m.contains("Authentication protocol")));
        assertTrue(errors.stream().anyMatch(m -> m.contains("Authentication passphrase is required")));

        SnmpV3Config ok = new SnmpV3Config("u", SecurityLevel.AUTH_NO_PRIV,
                AuthProtocol.SHA, "s3cr3tpass", PrivProtocol.NONE, null, null);
        assertTrue(ok.isValid());
    }

    @Test
    void authPassphraseMustMeetMinimumLength() {
        SnmpV3Config shortPass = new SnmpV3Config("u", SecurityLevel.AUTH_NO_PRIV,
                AuthProtocol.MD5, "short", PrivProtocol.NONE, null, null);
        assertFalse(shortPass.isValid());
        assertTrue(shortPass.validate().stream()
                .anyMatch(m -> m.contains("at least " + SnmpV3Config.MIN_PASSPHRASE_LENGTH)));
    }

    @Test
    void authPrivRequiresBothAuthAndPrivMaterial() {
        SnmpV3Config missingPriv = new SnmpV3Config("u", SecurityLevel.AUTH_PRIV,
                AuthProtocol.SHA_256, "authpass1", PrivProtocol.NONE, null, null);
        List<String> errors = missingPriv.validate();
        assertTrue(errors.stream().anyMatch(m -> m.contains("Privacy protocol")));
        assertTrue(errors.stream().anyMatch(m -> m.contains("Privacy passphrase is required")));

        SnmpV3Config ok = new SnmpV3Config("u", SecurityLevel.AUTH_PRIV,
                AuthProtocol.SHA_256, "authpass1", PrivProtocol.AES_128, "privpass1", "ctx");
        assertTrue(ok.isValid());
        assertEquals(List.of(), ok.validate());
    }

    @Test
    void privPassphraseMustMeetMinimumLength() {
        SnmpV3Config shortPriv = new SnmpV3Config("u", SecurityLevel.AUTH_PRIV,
                AuthProtocol.SHA, "authpass1", PrivProtocol.DES, "tiny", null);
        assertFalse(shortPriv.isValid());
        assertTrue(shortPriv.validate().stream()
                .anyMatch(m -> m.contains("Privacy passphrase must be at least")));
    }

    @Test
    void constructorNormalisesNullEnumsToDefaults() {
        SnmpV3Config cfg = new SnmpV3Config("u", null, null, null, null, null, null);
        assertEquals(SecurityLevel.NO_AUTH_NO_PRIV, cfg.level());
        assertEquals(AuthProtocol.NONE, cfg.authProtocol());
        assertEquals(PrivProtocol.NONE, cfg.privProtocol());
        assertTrue(cfg.isValid());
    }

    @Test
    void securityLevelParseAndFlags() {
        assertEquals(SecurityLevel.NO_AUTH_NO_PRIV, SecurityLevel.parse("noAuthNoPriv"));
        assertEquals(SecurityLevel.AUTH_NO_PRIV, SecurityLevel.parse("authNoPriv"));
        assertEquals(SecurityLevel.AUTH_PRIV, SecurityLevel.parse("authPriv"));
        assertEquals(SecurityLevel.NO_AUTH_NO_PRIV, SecurityLevel.parse("garbage"));
        assertEquals(SecurityLevel.NO_AUTH_NO_PRIV, SecurityLevel.parse(null));

        assertFalse(SecurityLevel.NO_AUTH_NO_PRIV.requiresAuth());
        assertTrue(SecurityLevel.AUTH_NO_PRIV.requiresAuth());
        assertFalse(SecurityLevel.AUTH_NO_PRIV.requiresPriv());
        assertTrue(SecurityLevel.AUTH_PRIV.requiresPriv());
    }

    @Test
    void authProtocolParseHandlesAliases() {
        assertEquals(AuthProtocol.MD5, AuthProtocol.parse("md5"));
        assertEquals(AuthProtocol.SHA, AuthProtocol.parse("SHA"));
        assertEquals(AuthProtocol.SHA, AuthProtocol.parse("sha1"));
        assertEquals(AuthProtocol.SHA_256, AuthProtocol.parse("SHA-256"));
        assertEquals(AuthProtocol.SHA_256, AuthProtocol.parse("sha256"));
        assertEquals(AuthProtocol.NONE, AuthProtocol.parse("none"));
        assertEquals(AuthProtocol.NONE, AuthProtocol.parse(null));
    }

    @Test
    void privProtocolParseHandlesAliases() {
        assertEquals(PrivProtocol.DES, PrivProtocol.parse("des"));
        assertEquals(PrivProtocol.AES_128, PrivProtocol.parse("AES-128"));
        assertEquals(PrivProtocol.AES_128, PrivProtocol.parse("aes"));
        assertEquals(PrivProtocol.AES_128, PrivProtocol.parse("aes128"));
        assertEquals(PrivProtocol.NONE, PrivProtocol.parse("none"));
        assertEquals(PrivProtocol.NONE, PrivProtocol.parse(null));
    }
}
