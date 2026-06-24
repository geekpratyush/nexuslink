package com.nexuslink.core.history;

import com.nexuslink.core.cache.CacheRegion;
import com.nexuslink.core.cache.CacheRegistry;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistent request history backed by SQLite with an FTS5 full-text index.
 * Recent entries are mirrored in the {@code history-recent} Caffeine region so the
 * common "show latest" path never touches disk.
 * <p>
 * Thread-safe for the app's usage pattern (single connection, short statements).
 */
public final class HistoryStore implements AutoCloseable {

    private final Connection conn;
    private final CacheRegion<Long, HistoryEntry> recentCache;
    private boolean ftsAvailable;

    public HistoryStore(String dbPath) {
        try {
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            this.recentCache = CacheRegistry.get().region(CacheRegistry.HISTORY_RECENT);
            initSchema();
        } catch (SQLException e) {
            throw new HistoryException("Failed to open history database at " + dbPath, e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS history (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    protocol   TEXT NOT NULL,
                    timestamp  INTEGER NOT NULL,
                    summary    TEXT NOT NULL,
                    status     INTEGER NOT NULL DEFAULT 0,
                    duration   INTEGER NOT NULL DEFAULT 0,
                    favorite   INTEGER NOT NULL DEFAULT 0,
                    detail     TEXT
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_history_ts ON history(timestamp DESC)");
            // FTS5 may not be compiled into every SQLite build — degrade gracefully.
            try {
                st.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS history_fts
                    USING fts5(summary, detail, content='history', content_rowid='id')""");
                ftsAvailable = true;
            } catch (SQLException noFts) {
                ftsAvailable = false;
            }
        }
    }

    /** Insert an entry; returns it with the generated id populated. */
    public HistoryEntry add(HistoryEntry e) {
        String sql = "INSERT INTO history(protocol,timestamp,summary,status,duration,favorite,detail) " +
                     "VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.protocol());
            ps.setLong(2, e.timestamp());
            ps.setString(3, e.summary());
            ps.setInt(4, e.statusCode());
            ps.setLong(5, e.durationMs());
            ps.setInt(6, e.favorite() ? 1 : 0);
            ps.setString(7, e.detail());
            ps.executeUpdate();
            long id;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                id = keys.next() ? keys.getLong(1) : 0;
            }
            HistoryEntry stored = new HistoryEntry(id, e.protocol(), e.timestamp(),
                    e.summary(), e.statusCode(), e.durationMs(), e.favorite(), e.detail());
            if (ftsAvailable) indexFts(stored);
            recentCache.put(id, stored);
            return stored;
        } catch (SQLException ex) {
            throw new HistoryException("Failed to add history entry", ex);
        }
    }

    private void indexFts(HistoryEntry e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO history_fts(rowid, summary, detail) VALUES(?,?,?)")) {
            ps.setLong(1, e.id());
            ps.setString(2, e.summary());
            ps.setString(3, e.detail() == null ? "" : e.detail());
            ps.executeUpdate();
        }
    }

    /** Most-recent entries first, capped at {@code limit}. */
    public List<HistoryEntry> recent(int limit) {
        return query("SELECT * FROM history ORDER BY timestamp DESC LIMIT ?", ps -> ps.setInt(1, limit));
    }

    /**
     * Full-text search over summary + detail (FTS5 when available, LIKE fallback otherwise).
     */
    public List<HistoryEntry> search(String text, int limit) {
        if (text == null || text.isBlank()) return recent(limit);
        if (ftsAvailable) {
            return query("""
                    SELECT h.* FROM history h
                    JOIN history_fts f ON f.rowid = h.id
                    WHERE history_fts MATCH ?
                    ORDER BY h.timestamp DESC LIMIT ?""",
                    ps -> { ps.setString(1, text + "*"); ps.setInt(2, limit); });
        }
        return query("SELECT * FROM history WHERE summary LIKE ? OR detail LIKE ? " +
                     "ORDER BY timestamp DESC LIMIT ?",
                ps -> { ps.setString(1, "%" + text + "%"); ps.setString(2, "%" + text + "%"); ps.setInt(3, limit); });
    }

    public Optional<HistoryEntry> byId(long id) {
        HistoryEntry cached = recentCache.get(id).orElse(null);
        if (cached != null) return Optional.of(cached);
        List<HistoryEntry> r = query("SELECT * FROM history WHERE id = ?", ps -> ps.setLong(1, id));
        return r.stream().findFirst();
    }

    public void setFavorite(long id, boolean favorite) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE history SET favorite=? WHERE id=?")) {
            ps.setInt(1, favorite ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
            recentCache.invalidate(id);
        } catch (SQLException e) {
            throw new HistoryException("Failed to update favorite", e);
        }
    }

    public int count() {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM history")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new HistoryException("Failed to count history", e);
        }
    }

    public boolean isFtsAvailable() {
        return ftsAvailable;
    }

    // ---- helpers ----

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<HistoryEntry> query(String sql, Binder binder) {
        List<HistoryEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new HistoryException("History query failed", e);
        }
        return out;
    }

    private HistoryEntry map(ResultSet rs) throws SQLException {
        return new HistoryEntry(
                rs.getLong("id"),
                rs.getString("protocol"),
                rs.getLong("timestamp"),
                rs.getString("summary"),
                rs.getInt("status"),
                rs.getLong("duration"),
                rs.getInt("favorite") != 0,
                rs.getString("detail"));
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    public static final class HistoryException extends RuntimeException {
        public HistoryException(String message, Throwable cause) { super(message, cause); }
    }
}
