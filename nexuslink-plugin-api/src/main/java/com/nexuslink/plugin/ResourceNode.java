package com.nexuslink.plugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One node in a connected resource hierarchy — a server, database, schema, table, column,
 * collection, index, topic, queue, etc. Protocols expose their live structure as a tree of
 * these via {@link ResourceExplorer}, and the UI renders them with per-kind icons plus a
 * details panel built from {@link #details()}.
 *
 * <p>Deliberately UI-agnostic: {@link #iconHint()} is just a string the view maps to an icon.
 */
public final class ResourceNode {

    /** What a node represents. Drives the icon and how children are interpreted. */
    public enum Kind {
        SERVER, DATABASE, SCHEMA, TABLE, COLUMN, COLLECTION, INDEX, FIELD,
        TOPIC, QUEUE, QUEUE_MANAGER, FOLDER, GENERIC
    }

    private final String id;
    private final String label;
    private final Kind kind;
    private final boolean hasChildren;
    private final Map<String, String> details;

    public ResourceNode(String id, String label, Kind kind, boolean hasChildren,
                        Map<String, String> details) {
        this.id = Objects.requireNonNull(id, "id");
        this.label = Objects.requireNonNull(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.hasChildren = hasChildren;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    /** Convenience: a leaf node with no details. */
    public static ResourceNode leaf(String id, String label, Kind kind) {
        return new ResourceNode(id, label, kind, false, Map.of());
    }

    /** Convenience: a branch node (children loaded lazily). */
    public static ResourceNode branch(String id, String label, Kind kind) {
        return new ResourceNode(id, label, kind, true, Map.of());
    }

    public String id() { return id; }
    public String label() { return label; }
    public Kind kind() { return kind; }
    public boolean hasChildren() { return hasChildren; }
    public Map<String, String> details() { return details; }

    /** Icon family name the UI resolves against its icon set. */
    public String iconHint() {
        return switch (kind) {
            case SERVER        -> "server";
            case DATABASE      -> "database";
            case SCHEMA        -> "schema";
            case TABLE         -> "table";
            case COLUMN        -> "column";
            case COLLECTION    -> "collection";
            case INDEX         -> "index";
            case FIELD         -> "field";
            case TOPIC         -> "topic";
            case QUEUE         -> "queue";
            case QUEUE_MANAGER -> "queue-manager";
            case FOLDER        -> "schema";
            case GENERIC       -> "dot";
        };
    }

    @Override public String toString() { return label; }
}
