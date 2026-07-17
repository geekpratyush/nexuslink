package com.nexuslink.ui.files;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 digests of file content, used by {@link TransferQueue} to prove a transferred file arrived
 * byte-for-byte rather than merely at the right length ({@link TransferIntegrity} compares the two
 * digests). Kept pure and JavaFX-free like {@link DuplicateName} and {@link TransferIntegrity}: a
 * {@link FileSystem} supplies the bytes, this turns them into a hex string.
 *
 * <p>Digests are lowercase hex. {@link TransferIntegrity} compares case-insensitively, so a remote
 * service reporting an upper-case digest of its own still matches.</p>
 */
public final class Checksum {

    /** The digest algorithm used for every transfer checksum. */
    public static final String ALGORITHM = "SHA-256";

    private static final int BUFFER_BYTES = 64 * 1024;

    private Checksum() {}

    /** Hex SHA-256 of in-memory bytes. */
    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    /**
     * Hex SHA-256 of everything remaining in {@code in}, streamed in fixed-size chunks so a large file
     * never has to be held in memory. The stream is read to the end but <em>not</em> closed — the
     * caller owns it (typically via try-with-resources).
     */
    public static String sha256(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[BUFFER_BYTES];
        int read;
        while ((read = in.read(buf)) != -1) {
            md.update(buf, 0, read);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE, so this cannot happen on a valid platform.
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }
}
