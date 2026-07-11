package com.nexuslink.ui.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, JavaFX-free back/forward navigation history for a single {@link FileBrowserPane} — a browser-style
 * visited-path stack with a movable cursor.
 *
 * <p>{@link #visit(String)} records a fresh navigation: it drops any forward entries (the ones you'd reach
 * with {@link #forward()}) and appends the new path, so navigating somewhere new always abandons the
 * forward branch — exactly like a web browser. Re-visiting the path the cursor already sits on is a no-op,
 * so a {@code refresh()} that re-lists the current directory never pollutes the history.
 *
 * <p>{@link #back()} / {@link #forward()} move the cursor without recording, returning the newly current
 * path (or {@code null} when there is nowhere to go); callers should then navigate to it <em>without</em>
 * calling {@link #visit} again, otherwise the forward branch would be truncated.
 */
public final class NavHistory {

    private final List<String> entries = new ArrayList<>();
    private int cursor = -1;   // index of the current path in entries, or -1 when empty

    /**
     * Records a navigation to {@code path}. No-op when {@code path} is null/blank or already the current
     * entry; otherwise truncates the forward branch and appends {@code path} as the new current entry.
     */
    public void visit(String path) {
        if (path == null || path.isBlank()) return;
        if (cursor >= 0 && path.equals(entries.get(cursor))) return;   // already here — don't duplicate
        // Drop the forward branch (everything after the cursor) before appending.
        while (entries.size() > cursor + 1) {
            entries.remove(entries.size() - 1);
        }
        entries.add(path);
        cursor = entries.size() - 1;
    }

    /** Whether {@link #back()} would move (there is an earlier entry). */
    public boolean canGoBack() {
        return cursor > 0;
    }

    /** Whether {@link #forward()} would move (there is a later entry). */
    public boolean canGoForward() {
        return cursor >= 0 && cursor < entries.size() - 1;
    }

    /**
     * Moves the cursor one step back and returns the now-current path, or {@code null} when already at the
     * oldest entry. Does not record — the caller should navigate to the returned path without {@link #visit}.
     */
    public String back() {
        if (!canGoBack()) return null;
        return entries.get(--cursor);
    }

    /**
     * Moves the cursor one step forward and returns the now-current path, or {@code null} when already at
     * the newest entry. Does not record.
     */
    public String forward() {
        if (!canGoForward()) return null;
        return entries.get(++cursor);
    }

    /** The current path, or {@code null} when no navigation has been recorded yet. */
    public String current() {
        return cursor < 0 ? null : entries.get(cursor);
    }

    /** Forgets all recorded history (e.g. when a pane disconnects and starts fresh). */
    public void clear() {
        entries.clear();
        cursor = -1;
    }

    /** An immutable snapshot of the recorded paths, oldest first (for tests / diagnostics). */
    public List<String> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }
}
