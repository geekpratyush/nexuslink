package com.nexuslink.security.cert;

import java.util.Objects;

/**
 * One decoded PEM block: the textual {@code label} taken from its {@code -----BEGIN <LABEL>-----}
 * armour (for example {@code CERTIFICATE}, {@code RSA PRIVATE KEY}, {@code PRIVATE KEY},
 * {@code PUBLIC KEY}, {@code X509 CRL}, {@code CERTIFICATE REQUEST}) and the DER {@code content}
 * recovered by base64-decoding the body between the markers. Produced by {@link PemParser}.
 */
public record PemBlock(String label, byte[] content) {

    /** Coarse classification of a PEM block, derived from its {@link #label()}. */
    public enum Type {
        CERTIFICATE, PRIVATE_KEY, PUBLIC_KEY, CRL, CSR, OTHER
    }

    public PemBlock {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(content, "content");
    }

    /**
     * Classifies this block from its label. Recognises certificate, private-key (PKCS#1/PKCS#8/EC),
     * public-key, CRL and CSR labels; anything else maps to {@link Type#OTHER}.
     */
    public Type type() {
        String l = label.trim().toUpperCase(java.util.Locale.ROOT);
        if (l.equals("CERTIFICATE") || l.equals("X509 CERTIFICATE") || l.equals("TRUSTED CERTIFICATE")) {
            return Type.CERTIFICATE;
        }
        if (l.equals("CERTIFICATE REQUEST") || l.equals("NEW CERTIFICATE REQUEST")) {
            return Type.CSR;
        }
        if (l.endsWith("CRL")) {
            return Type.CRL;
        }
        if (l.equals("PUBLIC KEY")) {
            return Type.PUBLIC_KEY;
        }
        if (l.endsWith("PRIVATE KEY")) {
            return Type.PRIVATE_KEY;
        }
        return Type.OTHER;
    }

    /** Length in bytes of the decoded DER payload. */
    public int length() {
        return content.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PemBlock other)) return false;
        return label.equals(other.label) && java.util.Arrays.equals(content, other.content);
    }

    @Override
    public int hashCode() {
        return 31 * label.hashCode() + java.util.Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "PemBlock[label=" + label + ", length=" + content.length + "]";
    }
}
