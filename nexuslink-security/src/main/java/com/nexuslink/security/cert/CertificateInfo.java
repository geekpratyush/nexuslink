package com.nexuslink.security.cert;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A decoded, UI-friendly snapshot of an X.509 certificate's key fields. Produced by
 * {@link CertificateParser}; carries no JCA types so views and tests can use it freely.
 */
public record CertificateInfo(
        String subject,
        String issuer,
        String serialNumber,
        Instant notBefore,
        Instant notAfter,
        List<String> subjectAltNames,
        String keyAlgorithm,
        int keySize,
        String signatureAlgorithm,
        boolean selfSigned,
        boolean certAuthority,
        String sha256Fingerprint) {

    /** Validity buckets used to drive list status icons (green / amber / red). */
    public enum Status { VALID, EXPIRING_SOON, EXPIRED, NOT_YET_VALID }

    /** The expiration warning threshold (matches the watchdog's first alert). */
    public static final Duration EXPIRING_WINDOW = Duration.ofDays(30);

    /** Classifies the certificate's validity relative to {@code now}. */
    public Status statusAt(Instant now) {
        if (now.isBefore(notBefore)) return Status.NOT_YET_VALID;
        if (now.isAfter(notAfter)) return Status.EXPIRED;
        if (!now.plus(EXPIRING_WINDOW).isBefore(notAfter)) return Status.EXPIRING_SOON;
        return Status.VALID;
    }

    public Status status() {
        return statusAt(Instant.now());
    }

    /** Whole days until expiry (negative if already expired). */
    public long daysUntilExpiry() {
        return ChronoUnit.DAYS.between(Instant.now(), notAfter);
    }

    /** The CN portion of the subject, or the full subject if no CN is present. */
    public String commonName() {
        for (String part : subject.split(",")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "CN=", 0, 3)) return p.substring(3).trim();
        }
        return subject;
    }
}
