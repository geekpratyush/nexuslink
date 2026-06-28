package com.nexuslink.protocol.snmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A typed, validated SNMPv3 User-based Security Model (USM) configuration. This is the config shape
 * the browser would hand to an engine; it carries the security name, security level, and the
 * authentication / privacy protocols and passphrases — no live USM engine is involved here.
 *
 * <p>The model is pure and side-effect free. {@link #validate()} encodes the USM rules: a security
 * name is always required; {@code authNoPriv}/{@code authPriv} need an auth protocol and an
 * RFC&nbsp;3414 passphrase of at least eight characters; {@code authPriv} additionally needs a
 * privacy protocol and passphrase. It is fully unit-testable offline.
 */
public record SnmpV3Config(
        String username,
        SecurityLevel level,
        AuthProtocol authProtocol,
        String authPassword,
        PrivProtocol privProtocol,
        String privPassword,
        String contextName) {

    /** SNMPv3 message security level. */
    public enum SecurityLevel {
        NO_AUTH_NO_PRIV("noAuthNoPriv", false, false),
        AUTH_NO_PRIV("authNoPriv", true, false),
        AUTH_PRIV("authPriv", true, true);

        private final String label;
        private final boolean auth;
        private final boolean priv;

        SecurityLevel(String label, boolean auth, boolean priv) {
            this.label = label;
            this.auth = auth;
            this.priv = priv;
        }

        public String label() { return label; }
        public boolean requiresAuth() { return auth; }
        public boolean requiresPriv() { return priv; }

        /** Parses a textual level ({@code noAuthNoPriv} / {@code authNoPriv} / {@code authPriv}). */
        public static SecurityLevel parse(String s) {
            if (s == null) return NO_AUTH_NO_PRIV;
            String v = s.trim().toLowerCase(Locale.ROOT);
            for (SecurityLevel l : values()) {
                if (l.label.toLowerCase(Locale.ROOT).equals(v)) return l;
            }
            return NO_AUTH_NO_PRIV;
        }
    }

    /** USM authentication protocol. */
    public enum AuthProtocol {
        NONE("None"),
        MD5("MD5"),
        SHA("SHA"),
        SHA_256("SHA-256");

        private final String label;
        AuthProtocol(String label) { this.label = label; }
        public String label() { return label; }

        public static AuthProtocol parse(String s) {
            if (s == null) return NONE;
            String v = s.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            return switch (v) {
                case "md5" -> MD5;
                case "sha", "sha1" -> SHA;
                case "sha256" -> SHA_256;
                default -> NONE;
            };
        }
    }

    /** USM privacy (encryption) protocol. */
    public enum PrivProtocol {
        NONE("None"),
        DES("DES"),
        AES_128("AES-128");

        private final String label;
        PrivProtocol(String label) { this.label = label; }
        public String label() { return label; }

        public static PrivProtocol parse(String s) {
            if (s == null) return NONE;
            String v = s.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            return switch (v) {
                case "des" -> DES;
                case "aes", "aes128" -> AES_128;
                default -> NONE;
            };
        }
    }

    /** Minimum USM passphrase length required by RFC 3414. */
    public static final int MIN_PASSPHRASE_LENGTH = 8;

    /** Normalises {@code null} enums to their {@code NONE}/{@code noAuthNoPriv} defaults. */
    public SnmpV3Config {
        level = level == null ? SecurityLevel.NO_AUTH_NO_PRIV : level;
        authProtocol = authProtocol == null ? AuthProtocol.NONE : authProtocol;
        privProtocol = privProtocol == null ? PrivProtocol.NONE : privProtocol;
    }

    /** A convenience {@code noAuthNoPriv} config carrying only a security name. */
    public static SnmpV3Config noAuthNoPriv(String username) {
        return new SnmpV3Config(username, SecurityLevel.NO_AUTH_NO_PRIV,
                AuthProtocol.NONE, null, PrivProtocol.NONE, null, null);
    }

    /**
     * Validates the configuration against the USM rules, returning an ordered list of human-readable
     * problems (empty ⇒ valid).
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (isBlank(username)) errors.add("Security name (username) is required");

        if (level.requiresAuth()) {
            if (authProtocol == AuthProtocol.NONE) {
                errors.add("Authentication protocol is required for " + level.label());
            }
            if (isBlank(authPassword)) {
                errors.add("Authentication passphrase is required for " + level.label());
            } else if (authPassword.length() < MIN_PASSPHRASE_LENGTH) {
                errors.add("Authentication passphrase must be at least " + MIN_PASSPHRASE_LENGTH + " characters");
            }
        }

        if (level.requiresPriv()) {
            if (privProtocol == PrivProtocol.NONE) {
                errors.add("Privacy protocol is required for " + level.label());
            }
            if (isBlank(privPassword)) {
                errors.add("Privacy passphrase is required for " + level.label());
            } else if (privPassword.length() < MIN_PASSPHRASE_LENGTH) {
                errors.add("Privacy passphrase must be at least " + MIN_PASSPHRASE_LENGTH + " characters");
            }
        }
        return errors;
    }

    /** True when {@link #validate()} reports no problems. */
    public boolean isValid() {
        return validate().isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
