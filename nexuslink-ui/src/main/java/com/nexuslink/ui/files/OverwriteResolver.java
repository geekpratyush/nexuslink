package com.nexuslink.ui.files;

import java.util.function.Function;

/**
 * Decides, for each target file that already exists, whether the transfer should overwrite it or
 * skip it — honouring "…all" answers for the remainder of the batch. This class is deliberately
 * JavaFX-free so the policy can be unit-tested without a display; the actual confirmation dialog
 * lives in the UI layer and is supplied as the {@code prompt} callback.
 */
public final class OverwriteResolver {

    /**
     * The answers a user can give when a target file already exists. Besides the plain
     * overwrite/skip (and their "…all" batch-sticky variants), {@code RENAME} keeps both files by
     * landing the incoming one under an auto-suffixed name, and {@code OVERWRITE_IF_NEWER} overwrites
     * only when the source is newer than the existing target (otherwise skips).
     */
    public enum Choice {
        OVERWRITE, SKIP, RENAME, OVERWRITE_IF_NEWER,
        OVERWRITE_ALL, SKIP_ALL, RENAME_ALL, OVERWRITE_IF_NEWER_ALL
    }

    /**
     * The resolved per-file action. {@code OVERWRITE_IF_NEWER} is a deferred decision the caller
     * completes with the two timestamps (see {@link #sourceIsNewer(long, long)}); {@code RENAME} asks
     * the caller to land the file under a non-colliding name.
     */
    public enum Action { OVERWRITE, SKIP, RENAME, OVERWRITE_IF_NEWER }

    private final Function<String, Choice> prompt;
    private Action sticky;   // non-null once an "…all" answer has been given

    /**
     * @param prompt asks the user about a single conflicting file (by name) and returns their
     *               {@link Choice}; never called once an "…all" answer is in effect.
     */
    public OverwriteResolver(Function<String, Choice> prompt) {
        this.prompt = prompt == null ? n -> Choice.SKIP : prompt;
    }

    /** A resolver that always overwrites without prompting (e.g. when nothing can conflict). */
    public static OverwriteResolver alwaysOverwrite() {
        OverwriteResolver r = new OverwriteResolver(n -> Choice.OVERWRITE);
        return r;
    }

    /**
     * Resolves the action for a conflicting file. Once the user picks OVERWRITE_ALL or SKIP_ALL the
     * remembered action is returned for every subsequent call and {@code prompt} is not invoked again.
     */
    public synchronized Action resolve(String name) {
        if (sticky != null) return sticky;
        Choice c = prompt.apply(name);
        if (c == null) return Action.SKIP;
        return switch (c) {
            case OVERWRITE -> Action.OVERWRITE;
            case SKIP -> Action.SKIP;
            case RENAME -> Action.RENAME;
            case OVERWRITE_IF_NEWER -> Action.OVERWRITE_IF_NEWER;
            case OVERWRITE_ALL -> sticky = Action.OVERWRITE;
            case SKIP_ALL -> sticky = Action.SKIP;
            case RENAME_ALL -> sticky = Action.RENAME;
            case OVERWRITE_IF_NEWER_ALL -> sticky = Action.OVERWRITE_IF_NEWER;
        };
    }

    /**
     * The rule behind {@link Action#OVERWRITE_IF_NEWER}: overwrite when the source is strictly newer
     * than the destination. When either timestamp is unknown ({@code 0} epoch millis — e.g. a remote
     * file system that doesn't report a machine-readable mtime) it can't tell, so it errs toward
     * copying rather than silently dropping an update, returning true. Pure and unit-testable.
     */
    public static boolean sourceIsNewer(long sourceEpochMillis, long destEpochMillis) {
        if (sourceEpochMillis == 0 || destEpochMillis == 0) return true;
        return sourceEpochMillis > destEpochMillis;
    }

    /** True once an "…all" answer has fixed the decision for the rest of the batch. */
    public synchronized boolean isResolvedForAll() { return sticky != null; }

    /** Forgets any "…all" decision so a fresh batch prompts again. */
    public synchronized void reset() { sticky = null; }
}
