package com.nexuslink.ui.help;

/**
 * One search result from the help index.
 * {@code anchor} may be null if the match is at the topic level.
 * {@code excerpt} has <<word>> markers for UI highlighting.
 */
public record SearchResult(
        HelpTopic topic,
        String anchor,
        String sectionTitle,
        String excerpt,
        double score
) {
    /** Full navigation target: "topicId" or "topicId#anchor" */
    public String navigationTarget() {
        return anchor != null ? topic.id() + "#" + anchor : topic.id();
    }
}
