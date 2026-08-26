package com.nexuslink.protocol.mongo;

import java.util.Locale;

/**
 * One query as the Compass-style query bar states it: filter, projection, sort, skip and limit.
 *
 * <p>Keeping these as separate fields rather than one JSON blob is the point — they are what the
 * driver takes, what a saved favourite stores, and what can be validated individually before a
 * round-trip. Blank parts are simply not applied.
 */
public record MongoQuerySpec(String filter, String projection, String sort, int skip, int limit) {

    /** Normalises nulls to blanks and clamps the paging numbers into a sane range. */
    public MongoQuerySpec {
        filter = blankIfNull(filter);
        projection = blankIfNull(projection);
        sort = blankIfNull(sort);
        skip = Math.max(0, skip);
        limit = limit <= 0 ? 50 : Math.min(limit, 10_000);
    }

    private static String blankIfNull(String s) { return s == null ? "" : s.trim(); }

    /** Everything in {@code collection}, capped at {@code limit}. */
    public static MongoQuerySpec all(int limit) {
        return new MongoQuerySpec("", "", "", 0, limit);
    }

    /** This query with a different filter — the rest of the bar is kept. */
    public MongoQuerySpec withFilter(String newFilter) {
        return new MongoQuerySpec(newFilter, projection, sort, skip, limit);
    }

    /** This query moved on by one page, for the result pane's Next button. */
    public MongoQuerySpec nextPage() {
        return new MongoQuerySpec(filter, projection, sort, skip + limit, limit);
    }

    /** This query moved back one page, stopping at the start. */
    public MongoQuerySpec previousPage() {
        return new MongoQuerySpec(filter, projection, sort, Math.max(0, skip - limit), limit);
    }

    /** {@code true} when nothing but the row cap is set — a plain "show me the collection". */
    public boolean isPlain() {
        return filter.isBlank() && projection.isBlank() && sort.isBlank() && skip == 0;
    }

    /**
     * The equivalent shell line, for the query history and for anyone who would rather read
     * {@code db.people.find({...}).sort({...}).limit(50)} than a form.
     */
    public String toShell(String collection) {
        StringBuilder sb = new StringBuilder("db.")
                .append(collection == null || collection.isBlank() ? "collection" : collection)
                .append(".find(").append(filter.isBlank() ? "{}" : filter);
        if (!projection.isBlank()) sb.append(", ").append(projection);
        sb.append(')');
        if (!sort.isBlank()) sb.append(".sort(").append(sort).append(')');
        if (skip > 0) sb.append(".skip(").append(skip).append(')');
        sb.append(".limit(").append(limit).append(')');
        return sb.toString();
    }

    /** A short label for a saved-query list, e.g. {@code {"role":"admin"} · sort · limit 50}. */
    public String label() {
        StringBuilder sb = new StringBuilder(filter.isBlank() ? "{}" : filter);
        if (sb.length() > 40) sb.setLength(40);
        if (!projection.isBlank()) sb.append(" · projection");
        if (!sort.isBlank()) sb.append(" · sort");
        if (skip > 0) sb.append(" · skip ").append(skip);
        return sb.append(" · limit ").append(limit).toString().toLowerCase(Locale.ROOT).isEmpty()
                ? "{}" : sb.toString();
    }
}
