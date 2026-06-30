package com.nexuslink.protocol.kafka;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An immutable predicate for the Kafka message browser. It filters consumed records by
 * offset range, timestamp range, partition, key, value, and headers — all optional and
 * AND-combined, so a filter with nothing set matches everything.
 *
 * <p>Like {@link ConsumerLagCalculator}, this is pure and Kafka-type-free: it tests a
 * small neutral {@link Record} view rather than any {@code org.apache.kafka} type, so it
 * carries zero client dependency and is fully offline-testable. Callers adapt their
 * already-consumed records into {@link Record} and ask {@link #matches(Record)} or
 * {@link #apply(List)}.
 *
 * <p>Build instances through {@link #builder()}. Any supplied regex is compiled eagerly
 * in the builder, so an invalid pattern fails fast with a {@link PatternSyntaxException}
 * at build time rather than per-record at match time.
 */
public final class MessageFilter {

    /**
     * A neutral, Kafka-type-free view of one consumed record. {@code key} and
     * {@code value} may be {@code null}; {@code headers} is never {@code null} (use an
     * empty map for none).
     */
    public record Record(int partition, long offset, long timestamp, String key, String value,
                         Map<String, String> headers) {}

    /** How a text predicate interprets its needle against a record field. */
    public enum MatchMode { SUBSTRING, REGEX }

    /** A compiled text predicate over a single string field (key or value). */
    private record TextMatcher(MatchMode mode, String needle, boolean caseSensitive, Pattern pattern) {

        static TextMatcher of(MatchMode mode, String needle, boolean caseSensitive) {
            Pattern p = null;
            if (mode == MatchMode.REGEX) {
                int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                p = Pattern.compile(needle, flags); // eager compile -> fail fast at build time
            }
            return new TextMatcher(mode, needle, caseSensitive, p);
        }

        /** A {@code null} field never matches a set predicate. */
        boolean test(String field) {
            if (field == null) return false;
            if (mode == MatchMode.REGEX) return pattern.matcher(field).find();
            if (caseSensitive) return field.contains(needle);
            return field.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        }
    }

    /** Requires a header key to be present, optionally constraining its value. */
    private record HeaderMatcher(String key, String expectedValue, boolean substring) {

        boolean test(Map<String, String> headers) {
            if (headers == null || !headers.containsKey(key)) return false;
            if (expectedValue == null) return true; // presence only
            String actual = headers.get(key);
            if (actual == null) return false;
            return substring ? actual.contains(expectedValue) : actual.equals(expectedValue);
        }
    }

    private final Long minOffset;
    private final Long maxOffset;
    private final Long minTimestamp;
    private final Long maxTimestamp;
    private final Integer partition;
    private final TextMatcher keyMatcher;
    private final TextMatcher valueMatcher;
    private final HeaderMatcher headerMatcher;

    private MessageFilter(Builder b) {
        this.minOffset = b.minOffset;
        this.maxOffset = b.maxOffset;
        this.minTimestamp = b.minTimestamp;
        this.maxTimestamp = b.maxTimestamp;
        this.partition = b.partition;
        this.keyMatcher = b.keyMatcher;
        this.valueMatcher = b.valueMatcher;
        this.headerMatcher = b.headerMatcher;
    }

    /** Tests one record against every set predicate (AND-combined). */
    public boolean matches(Record r) {
        if (minOffset != null && r.offset() < minOffset) return false;
        if (maxOffset != null && r.offset() > maxOffset) return false;
        if (minTimestamp != null && r.timestamp() < minTimestamp) return false;
        if (maxTimestamp != null && r.timestamp() > maxTimestamp) return false;
        if (partition != null && r.partition() != partition) return false;
        if (keyMatcher != null && !keyMatcher.test(r.key())) return false;
        if (valueMatcher != null && !valueMatcher.test(r.value())) return false;
        if (headerMatcher != null && !headerMatcher.test(r.headers())) return false;
        return true;
    }

    /** Returns the matching records, preserving the input order. */
    public List<Record> apply(List<Record> records) {
        List<Record> out = new ArrayList<>();
        for (Record r : records) {
            if (matches(r)) out.add(r);
        }
        return out;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent, AND-combining builder. All constraints are optional. */
    public static final class Builder {
        private Long minOffset;
        private Long maxOffset;
        private Long minTimestamp;
        private Long maxTimestamp;
        private Integer partition;
        private TextMatcher keyMatcher;
        private TextMatcher valueMatcher;
        private HeaderMatcher headerMatcher;

        private Builder() {}

        /** Inclusive lower bound on offset. */
        public Builder minOffset(long offset) {
            this.minOffset = offset;
            return this;
        }

        /** Inclusive upper bound on offset. */
        public Builder maxOffset(long offset) {
            this.maxOffset = offset;
            return this;
        }

        /** Inclusive lower bound on timestamp (epoch millis). */
        public Builder minTimestamp(long epochMillis) {
            this.minTimestamp = epochMillis;
            return this;
        }

        /** Inclusive upper bound on timestamp (epoch millis). */
        public Builder maxTimestamp(long epochMillis) {
            this.maxTimestamp = epochMillis;
            return this;
        }

        /** Restricts to a single partition. */
        public Builder partition(int partition) {
            this.partition = partition;
            return this;
        }

        /** Matches the key by substring. */
        public Builder keyContains(String needle, boolean caseSensitive) {
            this.keyMatcher = TextMatcher.of(MatchMode.SUBSTRING, needle, caseSensitive);
            return this;
        }

        /** Matches the key by regex (compiled now; invalid regex throws here). */
        public Builder keyMatches(String regex, boolean caseSensitive) {
            this.keyMatcher = TextMatcher.of(MatchMode.REGEX, regex, caseSensitive);
            return this;
        }

        /** Matches the value by substring. */
        public Builder valueContains(String needle, boolean caseSensitive) {
            this.valueMatcher = TextMatcher.of(MatchMode.SUBSTRING, needle, caseSensitive);
            return this;
        }

        /** Matches the value by regex (compiled now; invalid regex throws here). */
        public Builder valueMatches(String regex, boolean caseSensitive) {
            this.valueMatcher = TextMatcher.of(MatchMode.REGEX, regex, caseSensitive);
            return this;
        }

        /** Requires the named header to be present, with any value. */
        public Builder headerPresent(String key) {
            this.headerMatcher = new HeaderMatcher(key, null, false);
            return this;
        }

        /** Requires the named header to be present with an exactly-equal value. */
        public Builder headerEquals(String key, String value) {
            this.headerMatcher = new HeaderMatcher(key, value, false);
            return this;
        }

        /** Requires the named header to be present with a value containing {@code value}. */
        public Builder headerContains(String key, String value) {
            this.headerMatcher = new HeaderMatcher(key, value, true);
            return this;
        }

        public MessageFilter build() {
            return new MessageFilter(this);
        }
    }
}
