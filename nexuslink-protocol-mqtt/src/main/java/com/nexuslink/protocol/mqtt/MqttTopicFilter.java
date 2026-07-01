package com.nexuslink.protocol.mqtt;

import java.nio.charset.StandardCharsets;

/**
 * A pure, dependency-free implementation of MQTT topic matching and topic/filter validation as
 * specified by MQTT 3.1.1 and MQTT 5.0, section 4.7 ("Topic Names and Topic Filters").
 *
 * <p>A <em>topic name</em> is the concrete label a message is published to (no wildcards). A
 * <em>topic filter</em> is what a subscription registers and may contain the wildcards defined in
 * &sect;4.7.1:
 * <ul>
 *   <li>{@code +} — the single-level wildcard; it must occupy an entire level and matches exactly
 *       one level (including an empty level). E.g. {@code sport/+/player1} matches
 *       {@code sport/tennis/player1}.</li>
 *   <li>{@code #} — the multi-level wildcard; it must be the last character of the filter and
 *       occupy its own level. It matches the parent level and any number of child levels, so
 *       {@code sport/#} matches {@code sport}, {@code sport/tennis} and {@code sport/tennis/x}, and
 *       {@code #} on its own matches every topic.</li>
 * </ul>
 *
 * <p>Per &sect;4.7.2 the server MUST NOT match a Topic Filter that starts with a wildcard
 * ({@code #} or {@code +}) against a Topic Name beginning with {@code $} (e.g. {@code $SYS/...});
 * such topics are only reachable by a filter that names the {@code $}-prefixed level explicitly.
 *
 * <p>Instances are immutable: a filter is compiled once via {@link #compile(String)} (which
 * validates it) and its pre-split levels are reused for many {@link #matches(String)} calls. Each
 * match walks the topic string by index without splitting it, so no per-match allocation occurs.
 */
public final class MqttTopicFilter {

    /** &sect;4.7.3: a Topic Name / Topic Filter MUST NOT encode to more than 65,535 UTF-8 bytes. */
    public static final int MAX_LENGTH_BYTES = 65_535;

    private static final char LEVEL_SEPARATOR = '/';
    private static final String MULTI_LEVEL = "#";
    private static final String SINGLE_LEVEL = "+";

    private final String filter;
    private final String[] levels;
    /** True when the first level is a wildcard, which triggers the {@code $}-topic rule (&sect;4.7.2). */
    private final boolean firstLevelIsWildcard;

    private MqttTopicFilter(String filter, String[] levels) {
        this.filter = filter;
        this.levels = levels;
        this.firstLevelIsWildcard =
                levels[0].equals(MULTI_LEVEL) || levels[0].equals(SINGLE_LEVEL);
    }

    /**
     * Compiles a topic filter for repeated matching.
     *
     * @throws IllegalArgumentException if {@code filter} is not a valid topic filter (&sect;4.7.3)
     */
    public static MqttTopicFilter compile(String filter) {
        if (!isValidFilter(filter)) {
            throw new IllegalArgumentException("Invalid MQTT topic filter: " + describe(filter));
        }
        return new MqttTopicFilter(filter, splitLevels(filter));
    }

    /** The original filter string this object was compiled from. */
    public String filter() {
        return filter;
    }

    /**
     * Tests whether {@code topicName} matches this compiled filter, following the rules of
     * &sect;4.7.1 (wildcards, parent matching) and &sect;4.7.2 (the {@code $}-topic exclusion).
     *
     * @param topicName a concrete topic name; {@code null} never matches
     */
    public boolean matches(String topicName) {
        if (topicName == null) {
            return false;
        }
        // §4.7.2: a filter beginning with a wildcard must not match a $-prefixed topic.
        if (firstLevelIsWildcard && !topicName.isEmpty()
                && topicName.charAt(0) == '$') {
            return false;
        }

        final int topicLen = topicName.length();
        int cursor = 0;              // start index of the current (unconsumed) topic level
        boolean topicHasLevel = true; // a topic always has at least one (possibly empty) level

        for (String level : levels) {
            // §4.7.1.2: '#' is always the final level and matches the parent plus all children.
            if (level.equals(MULTI_LEVEL)) {
                return true;
            }
            if (!topicHasLevel) {
                // The filter still expects a level but the topic has been fully consumed.
                return false;
            }
            int slash = topicName.indexOf(LEVEL_SEPARATOR, cursor);
            int levelEnd = (slash < 0) ? topicLen : slash;

            // §4.7.1.3: '+' matches any single level; a literal level must compare equal.
            if (!level.equals(SINGLE_LEVEL)
                    && !regionEquals(topicName, cursor, levelEnd, level)) {
                return false;
            }

            if (slash < 0) {
                topicHasLevel = false;
            } else {
                cursor = slash + 1;
            }
        }
        // Every filter level consumed: match only if the topic is also fully consumed.
        return !topicHasLevel;
    }

    /**
     * One-shot convenience: compiles {@code filter} and matches {@code topicName} against it.
     *
     * @throws IllegalArgumentException if {@code filter} is not a valid topic filter
     */
    public static boolean matches(String filter, String topicName) {
        return compile(filter).matches(topicName);
    }

    /**
     * Validates a topic filter per &sect;4.7.3: non-null, at least one character, no U+0000, at
     * most {@link #MAX_LENGTH_BYTES} UTF-8 bytes, {@code +} must occupy an entire level, and
     * {@code #} must occupy an entire level and be the last level.
     */
    public static boolean isValidFilter(String filter) {
        if (!hasValidLength(filter)) {
            return false;
        }
        String[] levels = splitLevels(filter);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.indexOf('#') >= 0) {
                // '#' must stand alone in its level and be the last level of the filter.
                if (!level.equals(MULTI_LEVEL) || i != levels.length - 1) {
                    return false;
                }
            }
            if (level.indexOf('+') >= 0 && !level.equals(SINGLE_LEVEL)) {
                // '+' must occupy the whole level (e.g. "sp+rt" and "foo+" are invalid).
                return false;
            }
        }
        return true;
    }

    /**
     * Validates a topic name per &sect;4.7.3: non-null, at least one character, no U+0000, at most
     * {@link #MAX_LENGTH_BYTES} UTF-8 bytes, and containing neither wildcard character
     * ({@code +} or {@code #} are not permitted in a Topic Name).
     */
    public static boolean isValidTopicName(String topicName) {
        if (!hasValidLength(topicName)) {
            return false;
        }
        return topicName.indexOf('+') < 0 && topicName.indexOf('#') < 0;
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Shared length/character constraints from &sect;4.7.3, applied to both names and filters. */
    private static boolean hasValidLength(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.indexOf('\u0000') >= 0) {
            return false;
        }
        return s.getBytes(StandardCharsets.UTF_8).length <= MAX_LENGTH_BYTES;
    }

    /** Splits on the level separator, preserving leading, trailing and empty levels. */
    private static String[] splitLevels(String s) {
        return s.split("/", -1);
    }

    /** Compares {@code s[start, end)} to {@code other} without allocating a substring. */
    private static boolean regionEquals(String s, int start, int end, String other) {
        int len = end - start;
        return len == other.length() && s.regionMatches(start, other, 0, len);
    }

    private static String describe(String filter) {
        return filter == null ? "null" : '"' + filter + '"';
    }

    @Override
    public String toString() {
        return "MqttTopicFilter[" + filter + ']';
    }
}
