package com.nexuslink.protocol.http.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One entry in a REST collection tree — either a folder (which owns {@link #children}) or a saved
 * request (which owns {@link #request}).
 *
 * <p>The saved request is kept as an opaque {@link JsonNode} rather than a typed request object:
 * the exact field set lives in the REST view's own serializer, so the tree keeps working when that
 * serializer gains a field. It nests in the on-disk file instead of being an escaped string blob.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CollectionNode {

    /** Stable across renames and moves, so the UI can address a node it is holding. */
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public boolean folder;

    /** Folders only; always non-null so callers never branch on null. */
    public List<CollectionNode> children = new ArrayList<>();

    /** Requests only. */
    public JsonNode request;

    public CollectionNode() {
    }

    public static CollectionNode folder(String name) {
        CollectionNode n = new CollectionNode();
        n.folder = true;
        n.name = name == null ? "" : name;
        return n;
    }

    public static CollectionNode request(String name, JsonNode request) {
        CollectionNode n = new CollectionNode();
        n.folder = false;
        n.name = name == null ? "" : name;
        n.request = request;
        return n;
    }

    /** Deep copy with fresh ids, for duplicate/paste. */
    public CollectionNode copy() {
        CollectionNode n = folder ? folder(name) : request(name, request == null ? null : request.deepCopy());
        for (CollectionNode c : children) n.children.add(c.copy());
        return n;
    }

    @Override
    public String toString() {
        return name;
    }
}
