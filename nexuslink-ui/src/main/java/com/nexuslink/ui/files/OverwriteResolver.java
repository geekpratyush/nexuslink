package com.nexuslink.ui.files;

import java.util.function.Function;

/**
 * Decides, for each target file that already exists, whether the transfer should overwrite it or
 * skip it — honouring "…all" answers for the remainder of the batch. This class is deliberately
 * JavaFX-free so the policy can be unit-tested without a display; the actual confirmation dialog
 * lives in the UI layer and is supplied as the {@code prompt} callback.
 */
public final class OverwriteResolver {

    /** The four answers a user can give when a target file already exists. */
    public enum Choice { OVERWRITE, SKIP, OVERWRITE_ALL, SKIP_ALL }

    /** The resolved per-file action. */
    public enum Action { OVERWRITE, SKIP }

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
            case OVERWRITE_ALL -> sticky = Action.OVERWRITE;
            case SKIP_ALL -> sticky = Action.SKIP;
        };
    }

    /** True once an "…all" answer has fixed the decision for the rest of the batch. */
    public synchronized boolean isResolvedForAll() { return sticky != null; }

    /** Forgets any "…all" decision so a fresh batch prompts again. */
    public synchronized void reset() { sticky = null; }
}
