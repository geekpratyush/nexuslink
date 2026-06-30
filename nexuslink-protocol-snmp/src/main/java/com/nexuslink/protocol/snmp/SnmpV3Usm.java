package com.nexuslink.protocol.snmp;

import org.snmp4j.security.AuthHMAC192SHA256;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

/**
 * Pure mapper from a {@link SnmpV3Config} to the SNMP4J User-based Security Model (USM) primitives a
 * v3 session needs: the authentication / privacy protocol OIDs, the {@link SecurityLevel} integer,
 * and a localizable {@link UsmUser}. SNMP4J's own USM then does the key localization, authentication
 * and privacy on the wire — this class only translates the config shape, it performs no crypto.
 *
 * <p>Everything here is deterministic and offline-testable: the protocol enums map to fixed SNMP4J
 * OIDs ({@link AuthMD5#ID}, {@link AuthSHA#ID}, {@link AuthHMAC192SHA256#ID} for SHA-256, and
 * {@link PrivDES#ID} / {@link PrivAES128#ID} for privacy), and each {@link SnmpV3Config.SecurityLevel}
 * maps to the matching {@code SecurityLevel.*} constant.
 */
public final class SnmpV3Usm {

    private SnmpV3Usm() {}

    /**
     * Maps a config authentication protocol to its SNMP4J auth OID, or {@code null} for
     * {@link SnmpV3Config.AuthProtocol#NONE} (noAuth). SHA-256 is carried by HMAC-192-SHA-256.
     */
    public static OID authProtocolOid(SnmpV3Config.AuthProtocol protocol) {
        if (protocol == null) return null;
        return switch (protocol) {
            case NONE -> null;
            case MD5 -> AuthMD5.ID;
            case SHA -> AuthSHA.ID;
            case SHA_256 -> AuthHMAC192SHA256.ID;
        };
    }

    /**
     * Maps a config privacy protocol to its SNMP4J privacy OID, or {@code null} for
     * {@link SnmpV3Config.PrivProtocol#NONE} (noPriv).
     */
    public static OID privProtocolOid(SnmpV3Config.PrivProtocol protocol) {
        if (protocol == null) return null;
        return switch (protocol) {
            case NONE -> null;
            case DES -> PrivDES.ID;
            case AES_128 -> PrivAES128.ID;
        };
    }

    /** Maps a config security level to the SNMP4J {@code SecurityLevel} integer (defaults to noAuthNoPriv). */
    public static int securityLevel(SnmpV3Config.SecurityLevel level) {
        if (level == null) return SecurityLevel.NOAUTH_NOPRIV;
        return switch (level) {
            case NO_AUTH_NO_PRIV -> SecurityLevel.NOAUTH_NOPRIV;
            case AUTH_NO_PRIV -> SecurityLevel.AUTH_NOPRIV;
            case AUTH_PRIV -> SecurityLevel.AUTH_PRIV;
        };
    }

    /** The USM security name (username) as an {@link OctetString}; never {@code null}. */
    public static OctetString securityName(SnmpV3Config cfg) {
        String name = cfg == null || cfg.username() == null ? "" : cfg.username();
        return new OctetString(name);
    }

    /**
     * Builds a SNMP4J {@link UsmUser} from {@code cfg}. The auth / privacy protocols and passphrases
     * are only populated when the configured security level actually requires them, so a
     * {@code noAuthNoPriv} user carries no protocols and an {@code authNoPriv} user carries no privacy
     * protocol — matching what {@link SnmpV3Config#validate()} enforces.
     */
    public static UsmUser toUsmUser(SnmpV3Config cfg) {
        if (cfg == null) throw new IllegalArgumentException("config must not be null");

        boolean auth = cfg.level().requiresAuth();
        boolean priv = cfg.level().requiresPriv();

        OID authOid = auth ? authProtocolOid(cfg.authProtocol()) : null;
        OctetString authPass = authOid != null && cfg.authPassword() != null
                ? new OctetString(cfg.authPassword()) : null;

        OID privOid = priv ? privProtocolOid(cfg.privProtocol()) : null;
        OctetString privPass = privOid != null && cfg.privPassword() != null
                ? new OctetString(cfg.privPassword()) : null;

        return new UsmUser(securityName(cfg), authOid, authPass, privOid, privPass);
    }
}
