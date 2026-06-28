package com.nexuslink.security.vault;

/**
 * Signals that an encrypted vault backup could not be exported, imported, or restored.
 * <p>
 * This is a dedicated <em>checked</em> exception so callers must explicitly handle the
 * failure modes that matter for a backup file: an unreadable/corrupt file, an unsupported
 * backup format version, or a wrong passphrase. When import fails for any of these reasons
 * the operation aborts cleanly — callers never receive a partially-decrypted vault.
 */
public class VaultBackupException extends Exception {

    public VaultBackupException(String message) {
        super(message);
    }

    public VaultBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
