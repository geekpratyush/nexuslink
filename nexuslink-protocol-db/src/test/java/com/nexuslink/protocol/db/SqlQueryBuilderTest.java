package com.nexuslink.protocol.db;

import com.nexuslink.protocol.db.SqlQueryBuilder.Condition;
import com.nexuslink.protocol.db.SqlQueryBuilder.Direction;
import com.nexuslink.protocol.db.SqlQueryBuilder.Operator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryBuilderTest {

    @Test
    void noColumnsSelectsStar() {
        assertEquals("SELECT * FROM \"users\"",
                new SqlQueryBuilder().table("users").build());
    }

    @Test
    void explicitColumnsAreQuotedAndCommaSeparated() {
        assertEquals("SELECT \"id\", \"name\" FROM \"users\"",
                new SqlQueryBuilder().table("users").columns(List.of("id", "name")).build());
    }

    @Test
    void singleStringConditionIsQuoted() {
        assertEquals("SELECT * FROM \"users\" WHERE \"role\" = 'admin'",
                new SqlQueryBuilder().table("users")
                        .where("role", Operator.EQ, "admin").build());
    }

    @Test
    void numericValuesAreNotQuoted() {
        assertEquals("SELECT * FROM \"users\" WHERE \"age\" >= 21",
                new SqlQueryBuilder().table("users")
                        .where("age", Operator.GE, "21").build());
    }

    @Test
    void multipleConditionsCombinedWithAnd() {
        String sql = new SqlQueryBuilder().table("orders")
                .where("status", Operator.EQ, "open")
                .where("total", Operator.GT, "100")
                .build();
        assertEquals("SELECT * FROM \"orders\" WHERE \"status\" = 'open' AND \"total\" > 100", sql);
    }

    @Test
    void allComparisonOperatorsRender() {
        assertTrue(cond("a", Operator.NE, "x").contains("<> "));
        assertTrue(cond("a", Operator.LT, "1").contains("< 1"));
        assertTrue(cond("a", Operator.LE, "1").contains("<= 1"));
        assertTrue(cond("a", Operator.GT, "1").contains("> 1"));
        assertTrue(cond("a", Operator.GE, "1").contains(">= 1"));
        assertTrue(cond("a", Operator.LIKE, "%x%").contains("LIKE '%x%'"));
    }

    @Test
    void isNullTakesNoValue() {
        assertEquals("SELECT * FROM \"t\" WHERE \"deleted_at\" IS NULL",
                new SqlQueryBuilder().table("t")
                        .where("deleted_at", Operator.IS_NULL, "ignored").build());
    }

    @Test
    void isNotNullTakesNoValue() {
        assertEquals("SELECT * FROM \"t\" WHERE \"email\" IS NOT NULL",
                new SqlQueryBuilder().table("t")
                        .where("email", Operator.IS_NOT_NULL, null).build());
    }

    @Test
    void orderByWithDirection() {
        assertEquals("SELECT * FROM \"users\" ORDER BY \"name\" DESC",
                new SqlQueryBuilder().table("users").orderBy("name", Direction.DESC).build());
    }

    @Test
    void orderByDefaultsToAscWhenDirectionNull() {
        assertEquals("SELECT * FROM \"users\" ORDER BY \"name\" ASC",
                new SqlQueryBuilder().table("users").orderBy("name", null).build());
    }

    @Test
    void limitAppended() {
        assertEquals("SELECT * FROM \"users\" LIMIT 50",
                new SqlQueryBuilder().table("users").limit(50).build());
    }

    @Test
    void zeroOrNegativeLimitOmitted() {
        assertEquals("SELECT * FROM \"users\"",
                new SqlQueryBuilder().table("users").limit(0).build());
        assertEquals("SELECT * FROM \"users\"",
                new SqlQueryBuilder().table("users").limit(-5).build());
    }

    @Test
    void fullQueryCombinesEveryClause() {
        String sql = new SqlQueryBuilder().table("orders")
                .columns(List.of("id", "total"))
                .where("status", Operator.EQ, "open")
                .where("total", Operator.GE, "10")
                .orderBy("total", Direction.DESC)
                .limit(25)
                .build();
        assertEquals("SELECT \"id\", \"total\" FROM \"orders\" "
                + "WHERE \"status\" = 'open' AND \"total\" >= 10 "
                + "ORDER BY \"total\" DESC LIMIT 25", sql);
    }

    @Test
    void singleQuotesInValueAreEscaped() {
        assertEquals("SELECT * FROM \"t\" WHERE \"name\" = 'O''Brien'",
                new SqlQueryBuilder().table("t")
                        .where("name", Operator.EQ, "O'Brien").build());
    }

    @Test
    void doubleQuotesInIdentifiersAreDoubled() {
        assertEquals("SELECT * FROM \"we\"\"ird\"",
                new SqlQueryBuilder().table("we\"ird").build());
    }

    @Test
    void blankValueBecomesNullLiteral() {
        assertEquals("SELECT * FROM \"t\" WHERE \"c\" = NULL",
                new SqlQueryBuilder().table("t").where("c", Operator.EQ, "  ").build());
    }

    @Test
    void missingTableThrows() {
        assertThrows(IllegalStateException.class, () -> new SqlQueryBuilder().build());
    }

    @Test
    void conditionRequiresColumnAndOperator() {
        assertThrows(IllegalArgumentException.class, () -> new Condition("", Operator.EQ, "x"));
        assertThrows(IllegalArgumentException.class, () -> new Condition("c", null, "x"));
    }

    private static String cond(String col, Operator op, String val) {
        return new SqlQueryBuilder().table("t").where(col, op, val).build();
    }
}
