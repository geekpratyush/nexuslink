package com.nexuslink.security.cert;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Pure, dependency-free parser for PEM text (RFC 7468 "textual encodings"). Scans a string for one
 * or more {@code -----BEGIN <LABEL>-----} … base64 … {@code -----END <LABEL>-----} blocks and
 * decodes each body into its DER {@code byte[]}, returning {@link PemBlock} values.
 *
 * <p>It tolerates both CRLF and LF line endings, ignores any explanatory text or comments before,
 * between and after blocks (per RFC 7468 §5.2), and strips all whitespace inside a body before
 * base64-decoding. The {@code BEGIN} and {@code END} labels of each block must match; a mismatch,
 * a truncated block (missing {@code END}), or invalid base64 raises {@link PemException}.
 *
 * <p>Only {@link java.util.Base64} from the JDK is used; no certificate objects are built here, so
 * the parser stays a thin lexical layer usable ahead of {@link CertificateParser} and friends.
 */
public final class PemParser {

    private static final String BEGIN = "-----BEGIN ";
    private static final String END = "-----END ";
    private static final String DASHES = "-----";

    private PemParser() {}

    /** Raised when PEM armour is malformed: label mismatch, truncation, or undecodable base64. */
    public static final class PemException extends RuntimeException {
        public PemException(String message) {
            super(message);
        }

        public PemException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Parses every PEM block found in {@code text}, in order. Text outside the armour markers is
     * ignored. Returns an empty list when the input contains no {@code BEGIN} marker.
     *
     * @throws PemException on a label mismatch, an unterminated block, or invalid base64
     */
    public static List<PemBlock> parseAll(String text) {
        List<PemBlock> blocks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return blocks;
        }

        int cursor = 0;
        while (true) {
            int begin = text.indexOf(BEGIN, cursor);
            if (begin < 0) {
                break;
            }
            int labelEnd = text.indexOf(DASHES, begin + BEGIN.length());
            if (labelEnd < 0) {
                throw new PemException("Unterminated BEGIN marker at offset " + begin);
            }
            String label = text.substring(begin + BEGIN.length(), labelEnd).trim();
            if (label.isEmpty()) {
                throw new PemException("Empty PEM label in BEGIN marker at offset " + begin);
            }

            int bodyStart = labelEnd + DASHES.length();
            String endMarker = END + label + DASHES;
            int endIdx = text.indexOf(endMarker, bodyStart);
            if (endIdx < 0) {
                // Distinguish "no END at all" from "END with a different label" for a clearer error.
                int anyEnd = text.indexOf(END, bodyStart);
                if (anyEnd < 0) {
                    throw new PemException("Truncated PEM block: missing END marker for label '"
                            + label + "'");
                }
                int anyEndLabelEnd = text.indexOf(DASHES, anyEnd + END.length());
                String otherLabel = anyEndLabelEnd < 0
                        ? "?" : text.substring(anyEnd + END.length(), anyEndLabelEnd).trim();
                throw new PemException("PEM label mismatch: BEGIN '" + label + "' but END '"
                        + otherLabel + "'");
            }

            String body = text.substring(bodyStart, endIdx);
            byte[] der = decode(stripWhitespace(body), label);
            blocks.add(new PemBlock(label, der));

            cursor = endIdx + endMarker.length();
        }
        return blocks;
    }

    /**
     * Returns the first block whose {@link PemBlock#label()} equals {@code label} (case-sensitive,
     * as PEM labels are), or {@code null} when no such block is present.
     */
    public static PemBlock first(String text, String label) {
        if (label == null) {
            return null;
        }
        for (PemBlock block : parseAll(text)) {
            if (block.label().equals(label)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Quick, allocation-light check for whether {@code text} looks like PEM — that it contains a
     * well-formed {@code -----BEGIN <LABEL>-----} marker. Does not validate the body or the END
     * marker, and never throws.
     */
    public static boolean isPem(String text) {
        if (text == null) {
            return false;
        }
        int begin = text.indexOf(BEGIN);
        if (begin < 0) {
            return false;
        }
        int labelEnd = text.indexOf(DASHES, begin + BEGIN.length());
        return labelEnd > begin + BEGIN.length();
    }

    private static byte[] decode(String base64, String label) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new PemException("Invalid base64 in PEM block '" + label + "'", e);
        }
    }

    private static String stripWhitespace(String body) {
        StringBuilder sb = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n' && c != '\f') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
