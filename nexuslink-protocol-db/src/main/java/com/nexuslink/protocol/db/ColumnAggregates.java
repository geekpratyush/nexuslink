package com.nexuslink.protocol.db;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Summary statistics for one column of a result grid, computed from the rendered cell text — the
 * aggregate footer under the grid. Every column gets a count, a null count and a distinct count;
 * a column whose non-null cells all parse as numbers additionally gets sum, average, min and max.
 *
 * <p>Cells arrive as strings because that is what the grid holds, and a null cell may be either a
 * Java {@code null} or the text {@code NULL} that {@link JdbcService} renders — both are counted as
 * null and excluded from the numeric statistics. Pure and dependency-free, so it is unit-testable
 * without a database.
 */
public record ColumnAggregates(
        String column,
        int count,
        int nulls,
        int distinct,
        boolean numeric,
        BigDecimal sum,
        BigDecimal average,
        BigDecimal min,
        BigDecimal max
) {

    /** Computes the statistics for column {@code index} of {@code rows}. */
    public static ColumnAggregates of(String column, List<List<String>> rows, int index) {
        int nulls = 0;
        Set<String> seen = new HashSet<>();
        boolean numeric = true;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        int values = 0;

        for (List<String> row : rows) {
            String cell = index >= 0 && index < row.size() ? row.get(index) : null;
            if (isNull(cell)) { nulls++; seen.add("\0null"); continue; }
            seen.add(cell);
            if (!numeric) continue;
            BigDecimal n = number(cell);
            if (n == null) { numeric = false; continue; }
            values++;
            sum = sum.add(n);
            if (min == null || n.compareTo(min) < 0) min = n;
            if (max == null || n.compareTo(max) > 0) max = n;
        }

        if (!numeric || values == 0) {
            return new ColumnAggregates(column, rows.size(), nulls, seen.size(), false, null, null, null, null);
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(values), 6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
        return new ColumnAggregates(column, rows.size(), nulls, seen.size(), true,
                sum.stripTrailingZeros(), avg, min, max);
    }

    /** {@code true} for a Java null or the {@code NULL} text the grid renders for a SQL null. */
    private static boolean isNull(String cell) {
        return cell == null || cell.isEmpty() || "NULL".equalsIgnoreCase(cell);
    }

    /** The cell as a number, or {@code null} when it is not one. */
    private static BigDecimal number(String cell) {
        try {
            return new BigDecimal(cell.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A one-line footer rendering, e.g. {@code id · 3 rows · 0 null · 3 distinct · sum 6 · avg 2 …}. */
    public String footer() {
        StringBuilder sb = new StringBuilder(column)
                .append(" · ").append(count).append(count == 1 ? " row" : " rows")
                .append(" · ").append(nulls).append(" null")
                .append(" · ").append(distinct).append(" distinct");
        if (numeric) {
            sb.append(" · sum ").append(sum.toPlainString())
              .append(" · avg ").append(average.toPlainString())
              .append(" · min ").append(min.toPlainString())
              .append(" · max ").append(max.toPlainString());
        }
        return sb.toString();
    }
}
