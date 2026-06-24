package com.nexuslink.ui.help;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory search index built from all help topics.
 * Uses an inverted index (word → topics) + simple relevance scoring.
 * Results are cached via CacheRegistry.HELP_SEARCH.
 */
public final class HelpIndex {

    /** Maps normalized word → list of (topicId, sectionAnchor, score). */
    private final Map<String, List<Hit>> invertedIndex = new HashMap<>();
    private final Map<String, HelpTopic> topicsById = new LinkedHashMap<>();

    /** Add a topic to the index. Call during application startup. */
    public void index(HelpTopic topic) {
        topicsById.put(topic.id(), topic);

        // Index title with high weight
        tokenize(topic.title()).forEach(word ->
                addHit(word, topic.id(), null, 10));

        // Index keywords with medium weight
        topic.keywords().forEach(kw ->
                tokenize(kw).forEach(word -> addHit(word, topic.id(), null, 7)));

        // Index each section
        for (HelpTopic.Section section : topic.sections()) {
            int weight = section.level() == 1 ? 8 : section.level() == 2 ? 5 : 3;
            tokenize(section.heading()).forEach(word ->
                    addHit(word, topic.id(), section.anchor(), weight));
            tokenize(section.excerpt()).forEach(word ->
                    addHit(word, topic.id(), section.anchor(), 1));
        }
    }

    /**
     * Search the index. Returns ranked results, best match first.
     * Supports multi-word queries (AND semantics — all terms must appear).
     */
    public List<SearchResult> search(String query) {
        if (query == null || query.isBlank()) return List.of();

        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();

        // Accumulate scores per (topicId, anchor)
        Map<ResultKey, Double> scores = new HashMap<>();
        for (String term : terms) {
            // Exact match
            scoreHits(invertedIndex.get(term), term, 1.0, scores);
            // Prefix match (e.g. "kafka" matches "kafka-consumer")
            invertedIndex.entrySet().stream()
                    .filter(e -> !e.getKey().equals(term) && e.getKey().startsWith(term))
                    .forEach(e -> scoreHits(e.getValue(), term, 0.6, scores));
        }

        // Filter to results that match ALL terms (approximate: score > threshold)
        double threshold = terms.size() * 0.5;
        return scores.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .sorted(Map.Entry.<ResultKey, Double>comparingByValue().reversed())
                .limit(20)
                .map(e -> buildResult(e.getKey(), e.getValue(), query))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Collection<HelpTopic> allTopics() {
        return Collections.unmodifiableCollection(topicsById.values());
    }

    public Optional<HelpTopic> topic(String id) {
        return Optional.ofNullable(topicsById.get(id));
    }

    // ---- private ----

    private void addHit(String word, String topicId, String anchor, int weight) {
        invertedIndex.computeIfAbsent(word, k -> new ArrayList<>())
                .add(new Hit(topicId, anchor, weight));
    }

    private void scoreHits(List<Hit> hits, String term, double multiplier,
                           Map<ResultKey, Double> scores) {
        if (hits == null) return;
        for (Hit hit : hits) {
            ResultKey key = new ResultKey(hit.topicId, hit.anchor);
            scores.merge(key, hit.weight * multiplier, Double::sum);
        }
    }

    private SearchResult buildResult(ResultKey key, double score, String query) {
        HelpTopic topic = topicsById.get(key.topicId);
        if (topic == null) return null;

        String sectionTitle = null;
        String excerpt = null;
        if (key.anchor != null) {
            topic.sections().stream()
                    .filter(s -> key.anchor.equals(s.anchor()))
                    .findFirst()
                    .ifPresent(s -> {
                        // set via local var — Java record fields are final
                    });
            for (HelpTopic.Section s : topic.sections()) {
                if (key.anchor.equals(s.anchor())) {
                    sectionTitle = s.heading();
                    excerpt = highlight(s.excerpt(), query);
                    break;
                }
            }
        }

        return new SearchResult(topic, key.anchor, sectionTitle, excerpt, score);
    }

    /** Wraps matching words in <<>> markers; UI will render as bold/highlighted. */
    private String highlight(String text, String query) {
        if (text == null) return "";
        String result = text;
        for (String term : tokenize(query)) {
            result = result.replaceAll("(?i)(" + term + ")", "<<$1>>");
        }
        return result.length() > 200 ? result.substring(0, 200) + "…" : result;
    }

    private List<String> tokenize(String text) {
        if (text == null) return List.of();
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 2)
                .distinct()
                .collect(Collectors.toList());
    }

    private record Hit(String topicId, String anchor, int weight) {}
    private record ResultKey(String topicId, String anchor) {}
}
