package com.nexuslink.protocol.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import org.bson.Document;

import java.time.Instant;

/**
 * One change reported by a change stream, flattened into the fields a log line needs: when, what
 * kind of change, which collection, which document, and what actually changed.
 *
 * <p>The driver's own event object is generic and awkward to render; the interesting part of an
 * update is the {@code updatedFields} description, not the whole document, and that is what this
 * keeps.
 */
public record ChangeEvent(
        String operation,
        String namespace,
        String documentId,
        String detail,
        Instant at
) {

    /** Reads a driver change-stream document into a log-ready event. */
    public static ChangeEvent of(ChangeStreamDocument<Document> change) {
        if (change == null) return new ChangeEvent("unknown", "", "", "", Instant.now());
        String operation = change.getOperationType() == null
                ? "unknown" : change.getOperationType().getValue();
        String namespace = change.getNamespace() == null ? "" : change.getNamespace().getFullName();

        String id = "";
        if (change.getDocumentKey() != null && change.getDocumentKey().containsKey("_id")) {
            id = BsonNode.render(change.getDocumentKey().get("_id"));
            if (id.startsWith("BsonObjectId")) id = String.valueOf(change.getDocumentKey().get("_id"));
        }

        String detail = "";
        if (change.getUpdateDescription() != null) {
            var update = change.getUpdateDescription();
            StringBuilder sb = new StringBuilder();
            if (update.getUpdatedFields() != null && !update.getUpdatedFields().isEmpty()) {
                sb.append("set ").append(update.getUpdatedFields().toJson());
            }
            if (update.getRemovedFields() != null && !update.getRemovedFields().isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("removed ").append(String.join(", ", update.getRemovedFields()));
            }
            detail = sb.toString();
        } else if (change.getFullDocument() != null) {
            detail = change.getFullDocument().toJson();
        }

        Instant at = change.getClusterTime() == null
                ? Instant.now() : Instant.ofEpochSecond(change.getClusterTime().getTime());
        return new ChangeEvent(operation, namespace, id, detail, at);
    }

    /** {@code true} for a change that added or removed a document rather than editing one. */
    public boolean isInsertOrDelete() {
        return "insert".equals(operation) || "delete".equals(operation);
    }

    /** A single log line, e.g. {@code 10:15:30  update  app.people  _id=64b…  set {"age": 37}}. */
    public String line() {
        StringBuilder sb = new StringBuilder(at.toString()).append("  ").append(operation);
        if (!namespace.isEmpty()) sb.append("  ").append(namespace);
        if (!documentId.isEmpty()) sb.append("  _id=").append(documentId);
        if (!detail.isEmpty()) sb.append("  ").append(detail.length() > 200
                ? detail.substring(0, 200) + "…" : detail);
        return sb.toString();
    }
}
