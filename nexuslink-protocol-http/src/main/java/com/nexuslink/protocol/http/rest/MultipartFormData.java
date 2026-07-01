package com.nexuslink.protocol.http.rest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, dependency-free {@code multipart/form-data} request-body encoder (RFC 7578 / RFC 2046).
 * Callers add text and file parts through a small builder-style API and then obtain the assembled
 * body as a binary-safe {@code byte[]} plus the matching {@code Content-Type} header value.
 *
 * <p>No network and no file I/O: file contents are supplied by the caller as {@code byte[]}, so the
 * whole class is side-effect free and offline-testable.
 *
 * <p>Parts are serialized in the order they were added, each introduced by a {@code --boundary}
 * delimiter line and terminated by the closing {@code --boundary--} delimiter, with CRLF line
 * endings throughout as required by RFC 2046.
 *
 * <p><b>Name escaping.</b> Field and file names are emitted inside {@code Content-Disposition}
 * quoted strings. Following the WHATWG HTML form convention, the three characters that would
 * otherwise break the header — the double quote and the two line-ending bytes — are percent-encoded:
 * {@code "} becomes {@code %22}, CR becomes {@code %0D} and LF becomes {@code %0A}. No other bytes
 * are altered.
 */
public final class MultipartFormData {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] DASHES = {'-', '-'};
    private static final String DEFAULT_FILE_CONTENT_TYPE = "application/octet-stream";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Characters used to build a random boundary (RFC 2046 bcharsnospace subset). */
    private static final char[] BOUNDARY_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final List<Part> parts = new ArrayList<>();
    private String boundary;

    /** Creates an encoder; the boundary is chosen lazily so it can be guaranteed collision-free. */
    public MultipartFormData() {
    }

    /**
     * Creates an encoder with a caller-supplied boundary. The caller is responsible for ensuring the
     * boundary does not appear in any part content.
     *
     * @throws IllegalArgumentException if the boundary is null or empty
     */
    public MultipartFormData(String boundary) {
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("boundary must be non-empty");
        }
        this.boundary = boundary;
    }

    /**
     * Adds a text field part. The value is encoded as UTF-8.
     *
     * @return this encoder, for chaining
     */
    public MultipartFormData addField(String name, String value) {
        if (name == null) {
            throw new IllegalArgumentException("field name must not be null");
        }
        String v = value == null ? "" : value;
        parts.add(new Part(name, null, null, v.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /**
     * Adds a file part with the default content type {@code application/octet-stream}.
     *
     * @return this encoder, for chaining
     */
    public MultipartFormData addFile(String name, String filename, byte[] content) {
        return addFile(name, filename, null, content);
    }

    /**
     * Adds a file part.
     *
     * @param name        the field name
     * @param filename    the reported file name
     * @param contentType the part content type; {@code null}/blank defaults to
     *                    {@code application/octet-stream}
     * @param content     the raw bytes (copied defensively; {@code null} is treated as empty)
     * @return this encoder, for chaining
     */
    public MultipartFormData addFile(String name, String filename, String contentType, byte[] content) {
        if (name == null) {
            throw new IllegalArgumentException("field name must not be null");
        }
        if (filename == null) {
            throw new IllegalArgumentException("filename must not be null");
        }
        String ct = (contentType == null || contentType.isBlank())
                ? DEFAULT_FILE_CONTENT_TYPE : contentType;
        byte[] body = content == null ? new byte[0] : content.clone();
        parts.add(new Part(name, filename, ct, body));
        return this;
    }

    /**
     * Returns the boundary token, generating a collision-free one on first use if none was supplied.
     */
    public String getBoundary() {
        if (boundary == null) {
            boundary = generateBoundary();
        }
        return boundary;
    }

    /**
     * Returns the full {@code Content-Type} header value, e.g.
     * {@code multipart/form-data; boundary=...}.
     */
    public String getContentType() {
        return "multipart/form-data; boundary=" + getBoundary();
    }

    /**
     * Assembles and returns the complete request body as a binary-safe byte array. Repeated calls
     * with the same parts and boundary produce identical output.
     */
    public byte[] build() {
        String b = getBoundary();
        byte[] boundaryBytes = b.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (Part part : parts) {
            // Delimiter: "--" + boundary + CRLF
            out.writeBytes(DASHES);
            out.writeBytes(boundaryBytes);
            out.writeBytes(CRLF);

            // Content-Disposition header.
            StringBuilder disposition = new StringBuilder("Content-Disposition: form-data; name=\"");
            disposition.append(escapeName(part.name)).append('"');
            if (part.filename != null) {
                disposition.append("; filename=\"").append(escapeName(part.filename)).append('"');
            }
            out.writeBytes(disposition.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(CRLF);

            // Content-Type header (file parts only).
            if (part.contentType != null) {
                out.writeBytes(("Content-Type: " + part.contentType).getBytes(StandardCharsets.UTF_8));
                out.writeBytes(CRLF);
            }

            // Blank line separating headers from the body, then the raw content.
            out.writeBytes(CRLF);
            out.writeBytes(part.content);
            out.writeBytes(CRLF);
        }

        // Closing delimiter: "--" + boundary + "--" + CRLF
        out.writeBytes(DASHES);
        out.writeBytes(boundaryBytes);
        out.writeBytes(DASHES);
        out.writeBytes(CRLF);
        return out.toByteArray();
    }

    /**
     * Percent-encodes the characters that would break a quoted {@code Content-Disposition} value:
     * the double quote and the CR/LF line terminators. See the class documentation.
     */
    private static String escapeName(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '"' -> sb.append("%22");
                case '\r' -> sb.append("%0D");
                case '\n' -> sb.append("%0A");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Generates a random boundary that is guaranteed not to appear anywhere in the already-added part
     * content, so it can never be mistaken for a delimiter.
     */
    private String generateBoundary() {
        while (true) {
            String candidate = "----NexusLinkBoundary" + randomToken(24);
            if (!collides(candidate)) {
                return candidate;
            }
        }
    }

    /** True if {@code "--" + candidate} occurs within any part's raw content bytes. */
    private boolean collides(String candidate) {
        byte[] needle = ("--" + candidate).getBytes(StandardCharsets.US_ASCII);
        for (Part part : parts) {
            if (indexOf(part.content, needle) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String randomToken(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BOUNDARY_ALPHABET[RANDOM.nextInt(BOUNDARY_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Immutable value holder for one part. {@code filename}/{@code contentType} null for fields. */
    private static final class Part {
        final String name;
        final String filename;
        final String contentType;
        final byte[] content;

        Part(String name, String filename, String contentType, byte[] content) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.content = content;
        }
    }
}
