package com.nexuslink.ui.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An ordered, name-unique list of saved connection profiles ("sessions") for a file-style connector,
 * plus the last local/remote directory each one was left in — the WinSCP/MobaXterm "quick connect to
 * a server and land where I was" behaviour. Pure and JavaFX-free so it is unit-testable; persistence
 * is a line-per-session {@code key=value} format loaded/saved via {@link #load}/{@link #save}.
 * Mirrors the pure-helper style of {@link PathBookmarks} and {@link FileOrder}.
 *
 * <p><strong>Passwords are deliberately never stored.</strong> A session carries only the non-secret
 * coordinates (host/port/user, an optional private-key <em>path</em>, and protocol flags), so the file
 * is safe to sit in the user's home directory; quick-connect pre-fills everything but the password.
 */
public final class SavedSessions {

    /**
     * One saved profile. {@code keyPath} points at a private key file (never its contents) and is blank
     * when unused; {@code lastLocalDir}/{@code lastRemoteDir} are blank until the session has been used.
     * {@code options} holds protocol-specific flags (e.g. {@code passive}, {@code tls}, {@code scp}).
     */
    public record Session(String name, String host, int port, String user, String keyPath,
                          String lastLocalDir, String lastRemoteDir, Map<String, String> options) {

        public Session {
            name = trim(name);
            host = trim(host);
            user = trim(user);
            keyPath = trim(keyPath);
            lastLocalDir = trim(lastLocalDir);
            lastRemoteDir = trim(lastRemoteDir);
            options = options == null ? Map.of() : Map.copyOf(options);
        }

        /** A profile with no remembered directories and no protocol flags. */
        public static Session of(String name, String host, int port, String user, String keyPath) {
            return new Session(name, host, port, user, keyPath, "", "", Map.of());
        }

        /** A copy of this session remembering the directories each pane was left in. */
        public Session withDirs(String localDir, String remoteDir) {
            return new Session(name, host, port, user, keyPath, localDir, remoteDir, options);
        }

        /** A copy of this session under a different {@code name} (used when saving a filled-in form). */
        public Session withName(String name) {
            return new Session(name, host, port, user, keyPath, lastLocalDir, lastRemoteDir, options);
        }

        /** A copy of this session carrying {@code options} as its protocol flags. */
        public Session withOptions(Map<String, String> options) {
            return new Session(name, host, port, user, keyPath, lastLocalDir, lastRemoteDir, options);
        }

        /** The flag stored under {@code key}, or {@code fallback} when absent or unparseable. */
        public boolean flag(String key, boolean fallback) {
            String v = options.get(key);
            return v == null ? fallback : Boolean.parseBoolean(v);
        }

        /** A {@code user@host:port} label for menus and logs. */
        public String target() {
            String at = user.isEmpty() ? "" : user + "@";
            return at + host + ":" + port;
        }

        private static String trim(String s) { return s == null ? "" : s.trim(); }
    }

    private final List<Session> sessions = new ArrayList<>();

    /**
     * Adds {@code session}, or replaces the existing one with the same name (names are unique and
     * compared case-insensitively, since they are user-facing labels). A session with a blank name is
     * ignored. Returns this for chaining.
     */
    public SavedSessions add(Session session) {
        if (session == null || session.name().isEmpty()) return this;
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).name().equalsIgnoreCase(session.name())) {
                sessions.set(i, session);
                return this;
            }
        }
        sessions.add(session);
        return this;
    }

    /** Removes the session called {@code name}, if present. Returns true when one was removed. */
    public boolean remove(String name) {
        if (name == null) return false;
        return sessions.removeIf(s -> s.name().equalsIgnoreCase(name.trim()));
    }

    /** The session called {@code name}, if saved. */
    public Optional<Session> find(String name) {
        if (name == null) return Optional.empty();
        String n = name.trim();
        return sessions.stream().filter(s -> s.name().equalsIgnoreCase(n)).findFirst();
    }

    /**
     * Records the directories the panes were left in for the session called {@code name}, leaving the
     * rest of the profile untouched. A no-op when no such session is saved, so callers can record
     * unconditionally on disconnect without first checking. Returns true when a session was updated.
     */
    public boolean rememberDirs(String name, String localDir, String remoteDir) {
        Optional<Session> existing = find(name);
        if (existing.isEmpty()) return false;
        add(existing.get().withDirs(localDir, remoteDir));
        return true;
    }

    /** An immutable snapshot in insertion order. */
    public List<Session> list() {
        return List.copyOf(sessions);
    }

    public int size() { return sessions.size(); }

    /** Serialises to one {@code key=value}-per-field, tab-separated line per session. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Session s : sessions) {
            StringBuilder line = new StringBuilder();
            field(line, "name", s.name());
            field(line, "host", s.host());
            field(line, "port", String.valueOf(s.port()));
            field(line, "user", s.user());
            if (!s.keyPath().isEmpty()) field(line, "key", s.keyPath());
            if (!s.lastLocalDir().isEmpty()) field(line, "local", s.lastLocalDir());
            if (!s.lastRemoteDir().isEmpty()) field(line, "remote", s.lastRemoteDir());
            for (Map.Entry<String, String> e : s.options().entrySet()) {
                field(line, "opt." + e.getKey(), e.getValue());
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses the {@link #serialize} format. Unknown keys are ignored and a line without a usable
     * {@code name} is skipped, so a file written by a newer build (or hand-edited) degrades rather
     * than failing to load.
     */
    public static SavedSessions parse(String text) {
        SavedSessions out = new SavedSessions();
        if (text == null) return out;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            Map<String, String> options = new LinkedHashMap<>();
            for (String part : line.split("\t")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;                        // no key → not a field
                String key = part.substring(0, eq);
                String value = part.substring(eq + 1);
                if (key.startsWith("opt.")) options.put(key.substring(4), value);
                else fields.put(key, value);
            }
            String name = fields.getOrDefault("name", "");
            if (name.isBlank()) continue;
            out.add(new Session(name, fields.getOrDefault("host", ""), port(fields.get("port")),
                    fields.getOrDefault("user", ""), fields.getOrDefault("key", ""),
                    fields.getOrDefault("local", ""), fields.getOrDefault("remote", ""), options));
        }
        return out;
    }

    /** Loads sessions from {@code file}; a missing or unreadable file yields an empty set. */
    public static SavedSessions load(Path file) {
        try {
            if (file == null || !Files.exists(file)) return new SavedSessions();
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new SavedSessions();
        }
    }

    /** Saves sessions to {@code file}, creating parent directories as needed. */
    public void save(Path file) throws IOException {
        if (file == null) return;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, serialize(), StandardCharsets.UTF_8);
    }

    /** The per-protocol sessions file, e.g. {@code ~/.nexuslink/sessions-sftp.txt}. */
    public static Path fileFor(String protocol) {
        String safe = (protocol == null ? "unknown" : protocol).replaceAll("[^A-Za-z0-9_-]", "_");
        return Path.of(System.getProperty("user.home"), ".nexuslink", "sessions-" + safe + ".txt");
    }

    private static void field(StringBuilder line, String key, String value) {
        if (line.length() > 0) line.append('\t');
        line.append(key).append('=').append(clean(value));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static int port(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (RuntimeException e) { return 0; }
    }
}
