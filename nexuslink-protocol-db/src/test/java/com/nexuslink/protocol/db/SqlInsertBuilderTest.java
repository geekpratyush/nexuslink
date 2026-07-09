package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlInsertBuilderTest {

    @Test
    void singleColumnInsert() {
        assertEquals("INSERT INTO \"users\" (\"name\") VALUES ('ada')",
                new SqlInsertBuilder().table("users").value("name", "ada").build());
    }

    @Test
    void multipleColumnsPreserveOrder() {
        String sql = new SqlInsertBuilder().table("users")
                .value("name", "ada")
                .value("role", "admin")
                .build();
        assertEquals("INSERT INTO \"users\" (\"name\", \"role\") VALUES ('ada', 'admin')", sql);
    }

    @Test
    void numericValuesAreNotQuoted() {
        assertEquals("INSERT INTO \"t\" (\"age\", \"score\") VALUES (21, 3.5)",
                new SqlInsertBuilder().table("t").value("age", "21").value("score", "3.5").build());
    }

    @Test
    void omittedColumnsAreLeftForTheDatabaseDefault() {
        // Only the columns the user set appear — an auto-increment id / default is untouched.
        assertEquals("INSERT INTO \"users\" (\"name\") VALUES ('ada')",
                new SqlInsertBuilder().table("users").value("name", "ada").build());
    }

    @Test
    void explicitNullDiffersFromBlankSkip() {
        assertEquals("INSERT INTO \"t\" (\"note\") VALUES (NULL)",
                new SqlInsertBuilder().table("t").valueNull("note").build());
    }

    @Test
    void blankValueRendersAsNullLiteral() {
        assertEquals("INSERT INTO \"t\" (\"note\") VALUES (NULL)",
                new SqlInsertBuilder().table("t").value("note", "   ").build());
    }

    @Test
    void singleQuotesInValueAreEscaped() {
        assertEquals("INSERT INTO \"t\" (\"name\") VALUES ('O''Brien')",
                new SqlInsertBuilder().table("t").value("name", "O'Brien").build());
    }

    @Test
    void doubleQuotesInIdentifiersAreDoubled() {
        assertEquals("INSERT INTO \"we\"\"ird\" (\"c\") VALUES (1)",
                new SqlInsertBuilder().table("we\"ird").value("c", "1").build());
    }

    @Test
    void rawLiteralPassesThroughUnescaped() {
        assertEquals("INSERT INTO \"t\" (\"ts\") VALUES (now())",
                new SqlInsertBuilder().table("t").valueLiteral("ts", "now()").build());
    }

    @Test
    void blankRawLiteralBecomesNull() {
        assertEquals("INSERT INTO \"t\" (\"c\") VALUES (NULL)",
                new SqlInsertBuilder().table("t").valueLiteral("c", "  ").build());
    }

    @Test
    void missingTableThrows() {
        assertThrows(IllegalStateException.class,
                () -> new SqlInsertBuilder().value("c", "1").build());
    }

    @Test
    void noColumnsThrows() {
        assertThrows(IllegalStateException.class,
                () -> new SqlInsertBuilder().table("t").build());
    }

    @Test
    void blankColumnNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlInsertBuilder().table("t").value("  ", "1"));
    }
}
