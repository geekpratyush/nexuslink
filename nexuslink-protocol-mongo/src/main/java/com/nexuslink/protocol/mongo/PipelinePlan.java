package com.nexuslink.protocol.mongo;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An aggregation pipeline broken into the prefixes needed to preview it stage by stage: what the
 * documents look like after stage 1, after stages 1–2, and so on.
 *
 * <p>This is the thing an aggregation editor is actually for. Writing a six-stage pipeline blind and
 * getting an empty result tells you nothing about <em>which</em> stage emptied it; seeing the count
 * drop from 12,431 to 0 at stage 4 tells you everything. The prefixes are plain sub-lists, and the
 * per-stage sample is the prefix with a {@code $limit} appended, so previewing costs one cheap query
 * per stage rather than materialising the whole pipeline.
 *
 * <p>Pure: it builds and validates pipeline documents without a connection.
 */
public final class PipelinePlan {

    /** Stages that change how many documents flow on — worth flagging in the preview. */
    private static final List<String> REDUCING_STAGES =
            List.of("$match", "$limit", "$sample", "$group", "$count", "$facet", "$unwind");

    private PipelinePlan() {}

    /** Thrown when a stage is not valid JSON or is not a single-key stage document. */
    public static final class PipelineException extends RuntimeException {
        private final int stageIndex;

        public PipelineException(int stageIndex, String message) {
            super(message);
            this.stageIndex = stageIndex;
        }

        /** The zero-based stage the problem is in, so the editor can point at it. */
        public int stageIndex() { return stageIndex; }
    }

    /**
     * Parses a pipeline written as a JSON array into its stage documents.
     *
     * @throws PipelineException naming the offending stage
     */
    public static List<Document> parse(String pipelineJson) {
        if (pipelineJson == null || pipelineJson.isBlank()) return List.of();
        List<Document> stages = new ArrayList<>();
        org.bson.BsonArray array;
        try {
            array = org.bson.BsonArray.parse(pipelineJson);
        } catch (RuntimeException e) {
            throw new PipelineException(-1, "The pipeline is not a JSON array: " + e.getMessage());
        }
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isDocument()) {
                throw new PipelineException(i, "Stage " + (i + 1) + " is not a document");
            }
            Document stage = Document.parse(array.get(i).asDocument().toJson());
            validate(i, stage);
            stages.add(stage);
        }
        return stages;
    }

    /** A stage must be exactly one {@code $operator: …} pair. */
    public static void validate(int index, Document stage) {
        if (stage.size() != 1) {
            throw new PipelineException(index, "Stage " + (index + 1) + " must have exactly one "
                    + "$operator, found " + stage.size());
        }
        String name = stage.keySet().iterator().next();
        if (!name.startsWith("$")) {
            throw new PipelineException(index, "Stage " + (index + 1) + " starts with “" + name
                    + "”, which is not a $operator");
        }
    }

    /** The pipeline that produces stage {@code index}'s output: stages 0..index inclusive. */
    public static List<Document> prefix(List<Document> stages, int index) {
        if (index < 0 || index >= stages.size()) return List.of();
        return List.copyOf(stages.subList(0, index + 1));
    }

    /**
     * The prefix with a {@code $limit} appended — the cheap query behind a stage's sample. A prefix
     * that already ends in {@code $count} is left alone, since it emits one document by definition.
     */
    public static List<Document> sampleAt(List<Document> stages, int index, int sampleSize) {
        List<Document> prefix = new ArrayList<>(prefix(stages, index));
        if (prefix.isEmpty()) return prefix;
        if (!stageName(prefix.get(prefix.size() - 1)).equals("$count")) {
            prefix.add(new Document("$limit", Math.max(1, sampleSize)));
        }
        return prefix;
    }

    /** The prefix with {@code $count} appended — how many documents survive to this stage. */
    public static List<Document> countAt(List<Document> stages, int index) {
        List<Document> prefix = new ArrayList<>(prefix(stages, index));
        if (prefix.isEmpty()) return prefix;
        if (stageName(prefix.get(prefix.size() - 1)).equals("$count")) return prefix;
        prefix.add(new Document("$count", "count"));
        return prefix;
    }

    /** A stage's operator name, e.g. {@code $match}. */
    public static String stageName(Document stage) {
        return stage == null || stage.isEmpty() ? "" : stage.keySet().iterator().next();
    }

    /** {@code true} for a stage that can change the document count — worth watching in a preview. */
    public static boolean changesCount(Document stage) {
        return REDUCING_STAGES.contains(stageName(stage).toLowerCase(Locale.ROOT));
    }

    /** Renders stages back to the JSON array shown in the editor, one stage per line. */
    public static String render(List<Document> stages) {
        if (stages == null || stages.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < stages.size(); i++) {
            sb.append("  ").append(stages.get(i).toJson());
            if (i < stages.size() - 1) sb.append(',');
            sb.append('\n');
        }
        return sb.append("]").toString();
    }
}
