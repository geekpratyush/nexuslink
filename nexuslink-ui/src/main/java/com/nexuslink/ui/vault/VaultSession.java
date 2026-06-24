package com.nexuslink.ui.vault;

import com.nexuslink.security.vault.CredentialVault;
import com.nexuslink.security.vault.VaultStore;
import javafx.application.Platform;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * App-wide gateway to the {@link CredentialVault}. Lazily creates or loads the vault file,
 * prompts for the master password via {@link MasterPasswordDialog} when access is needed, and
 * auto-locks after a period of inactivity. Saved-connection secrets are stored here and the
 * connection profile keeps only the returned reference — never plaintext.
 */
public final class VaultSession {

    private static final VaultSession INSTANCE = new VaultSession();
    public static VaultSession get() { return INSTANCE; }

    private final Path file = Path.of(System.getProperty("user.home"), ".nexuslink", "vault.json");
    private long autoLockMillis = 5 * 60 * 1000L;   // 5 minutes
    private final Timer timer = new Timer("vault-autolock", true);
    private TimerTask pending;

    private CredentialVault vault;
    private Runnable onStateChange = () -> {};

    private VaultSession() {}

    public void setOnStateChange(Runnable r) { this.onStateChange = r == null ? () -> {} : r; }

    public boolean exists() { return VaultStore.exists(file); }

    public boolean isUnlocked() { return vault != null && !vault.isLocked(); }

    /**
     * Ensures the vault is loaded and unlocked, prompting the user as needed. Returns false if the
     * user cancels. Creates a new vault on first use.
     */
    public boolean ensureUnlocked(Window owner) {
        if (isUnlocked()) { touch(); return true; }

        if (!exists()) {
            Optional<char[]> pw = MasterPasswordDialog.create(owner);
            if (pw.isEmpty()) return false;
            vault = CredentialVault.create(pw.get());
            persist();
            touch();
            notifyState();
            return true;
        }

        if (vault == null) {
            try {
                vault = VaultStore.load(file);
            } catch (IOException e) {
                return false;
            }
        }
        String error = null;
        while (true) {
            Optional<char[]> pw = MasterPasswordDialog.unlock(owner, error);
            if (pw.isEmpty()) return false;
            if (vault.unlock(pw.get())) { touch(); notifyState(); return true; }
            error = "Incorrect master password — try again.";
        }
    }

    /** Encrypts {@code secret} under a fresh reference and returns it, or null if the user cancels. */
    public String storeSecret(String label, String secret, Window owner) {
        if (!ensureUnlocked(owner)) return null;
        String ref = label.replaceAll("\\s+", "-") + ":" + UUID.randomUUID();
        vault.store(ref, secret);
        persist();
        touch();
        return ref;
    }

    /** Resolves a stored secret reference, unlocking the vault if needed. */
    public Optional<String> resolve(String ref, Window owner) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        if (!ensureUnlocked(owner)) return Optional.empty();
        touch();
        return vault.retrieve(ref);
    }

    public void lock() {
        if (vault != null) vault.lock();
        cancelPending();
        notifyState();
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            VaultStore.save(vault, file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save vault to " + file, e);
        }
    }

    /** Resets the inactivity timer that auto-locks the vault. */
    private synchronized void touch() {
        cancelPending();
        pending = new TimerTask() {
            @Override public void run() { Platform.runLater(VaultSession.this::lock); }
        };
        timer.schedule(pending, autoLockMillis);
    }

    private synchronized void cancelPending() {
        if (pending != null) { pending.cancel(); pending = null; }
    }

    private void notifyState() { Platform.runLater(onStateChange); }
}
