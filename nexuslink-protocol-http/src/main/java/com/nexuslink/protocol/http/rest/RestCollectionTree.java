package com.nexuslink.protocol.http.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure tree operations over a forest of {@link CollectionNode}s — each root is one collection.
 *
 * <p>All operations address nodes by id, because the UI holds ids across renames and reorders. The
 * forest is mutated in place; every method returns whether it changed anything, so the caller knows
 * when to persist.
 */
public final class RestCollectionTree {

    private RestCollectionTree() {
    }

    /** The node with this id, anywhere in the forest. */
    public static Optional<CollectionNode> find(List<CollectionNode> roots, String id) {
        if (id == null) return Optional.empty();
        for (CollectionNode n : roots) {
            if (id.equals(n.id)) return Optional.of(n);
            Optional<CollectionNode> hit = find(n.children, id);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** The folder holding this id, or empty when the node is a root (or absent). */
    public static Optional<CollectionNode> parentOf(List<CollectionNode> roots, String id) {
        for (CollectionNode n : roots) {
            for (CollectionNode c : n.children) {
                if (c.id.equals(id)) return Optional.of(n);
            }
            Optional<CollectionNode> hit = parentOf(n.children, id);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /** The child list that holds this id — a folder's children, or the roots themselves. */
    public static Optional<List<CollectionNode>> siblingsOf(List<CollectionNode> roots, String id) {
        for (CollectionNode n : roots) {
            if (n.id.equals(id)) return Optional.of(roots);
        }
        for (CollectionNode n : roots) {
            Optional<List<CollectionNode>> hit = siblingsOf(n.children, id);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }

    /**
     * Human-readable path to a node, e.g. {@code "Billing / v2 / Create invoice"}. Empty when the
     * node is not in the forest.
     */
    public static String path(List<CollectionNode> roots, String id) {
        List<String> names = new ArrayList<>();
        if (!collectPath(roots, id, names)) return "";
        return String.join(" / ", names);
    }

    private static boolean collectPath(List<CollectionNode> nodes, String id, List<String> out) {
        for (CollectionNode n : nodes) {
            out.add(n.name);
            if (n.id.equals(id) || collectPath(n.children, id, out)) return true;
            out.remove(out.size() - 1);
        }
        return false;
    }

    /**
     * Adds a node under {@code parentId}, or at the top level when it is null/absent. Requests
     * cannot hold children, so a request parent is rejected.
     */
    public static boolean add(List<CollectionNode> roots, String parentId, CollectionNode node) {
        if (node == null) return false;
        if (parentId == null) {
            roots.add(node);
            return true;
        }
        Optional<CollectionNode> parent = find(roots, parentId);
        if (parent.isEmpty() || !parent.get().folder) return false;
        parent.get().children.add(node);
        return true;
    }

    public static boolean rename(List<CollectionNode> roots, String id, String name) {
        if (name == null || name.isBlank()) return false;
        Optional<CollectionNode> node = find(roots, id);
        if (node.isEmpty()) return false;
        node.get().name = name.trim();
        return true;
    }

    /** Removes a node and everything below it. */
    public static boolean remove(List<CollectionNode> roots, String id) {
        return siblingsOf(roots, id)
                .map(list -> list.removeIf(n -> n.id.equals(id)))
                .orElse(false);
    }

    /**
     * Moves a node into {@code newParentId} (null = top level) at {@code index}, clamped into range.
     *
     * <p>Refuses to move a folder into itself or its own subtree — that would silently detach the
     * whole subtree from the forest — and refuses a request as a parent.
     */
    public static boolean move(List<CollectionNode> roots, String id, String newParentId, int index) {
        Optional<CollectionNode> moving = find(roots, id);
        if (moving.isEmpty()) return false;
        if (id.equals(newParentId)) return false;
        if (newParentId != null && find(moving.get().children, newParentId).isPresent()) return false;

        List<CollectionNode> target;
        if (newParentId == null) {
            target = roots;
        } else {
            Optional<CollectionNode> parent = find(roots, newParentId);
            if (parent.isEmpty() || !parent.get().folder) return false;
            target = parent.get().children;
        }

        List<CollectionNode> from = siblingsOf(roots, id).orElse(null);
        if (from == null) return false;
        int wasAt = indexOf(from, id);
        from.remove(wasAt);
        // Removing from the same list first shifts every later position down by one.
        int at = Math.max(0, Math.min(index, target.size()));
        target.add(at, moving.get());
        return true;
    }

    private static int indexOf(List<CollectionNode> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    /** Every request node under {@code node} (or the forest), in depth-first display order. */
    public static List<CollectionNode> requestsUnder(CollectionNode node) {
        List<CollectionNode> out = new ArrayList<>();
        collectRequests(node.folder ? node.children : List.of(node), out);
        return out;
    }

    public static List<CollectionNode> requestsUnder(List<CollectionNode> roots) {
        List<CollectionNode> out = new ArrayList<>();
        collectRequests(roots, out);
        return out;
    }

    private static void collectRequests(List<CollectionNode> nodes, List<CollectionNode> out) {
        for (CollectionNode n : nodes) {
            if (n.folder) collectRequests(n.children, out);
            else out.add(n);
        }
    }

    /**
     * A name not already used by a sibling, minted as {@code "name copy"}, {@code "name copy 2"}…
     * so repeated duplication increments rather than stacking suffixes.
     */
    public static String uniqueName(List<CollectionNode> siblings, String desired) {
        String base = desired == null ? "" : desired.trim();
        if (!used(siblings, base)) return base;
        // Duplicating a duplicate increments the counter instead of stacking " copy copy".
        base = base.replaceFirst("(?i) copy( \\d+)?$", "");
        String candidate = base + " copy";
        int n = 2;
        while (used(siblings, candidate)) {
            candidate = base + " copy " + n++;
        }
        return candidate;
    }

    private static boolean used(List<CollectionNode> siblings, String name) {
        return siblings.stream().anyMatch(s -> s.name.equalsIgnoreCase(name));
    }
}
