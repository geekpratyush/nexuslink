package com.nexuslink.protocol.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlScriptSplitterTest {

    @Test
    void multipleSimpleStatements() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1; SELECT 2; SELECT 3");
        assertEquals(List.of("SELECT 1", "SELECT 2", "SELECT 3"), stmts);
    }

    @Test
    void trailingStatementWithoutFinalSemicolon() {
        List<String> stmts = SqlScriptSplitter.split("INSERT INTO t VALUES (1);\nSELECT * FROM t");
        assertEquals(2, stmts.size());
        assertEquals("INSERT INTO t VALUES (1)", stmts.get(0));
        assertEquals("SELECT * FROM t", stmts.get(1));
    }

    @Test
    void trailingSemicolonProducesNoEmptyStatement() {
        assertEquals(List.of("SELECT 1"), SqlScriptSplitter.split("SELECT 1;"));
        assertEquals(List.of("SELECT 1"), SqlScriptSplitter.split("SELECT 1;   \n  "));
    }

    @Test
    void semicolonInsideStringLiteralIsIgnored() {
        List<String> stmts = SqlScriptSplitter.split("INSERT INTO t VALUES ('a;b;c'); SELECT 1");
        assertEquals(2, stmts.size());
        assertEquals("INSERT INTO t VALUES ('a;b;c')", stmts.get(0));
        assertEquals("SELECT 1", stmts.get(1));
    }

    @Test
    void escapedSingleQuoteInsideLiteral() {
        // 'it''s a; test' is a single literal containing a semicolon and an escaped quote.
        List<String> stmts = SqlScriptSplitter.split("SELECT 'it''s a; test'; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 'it''s a; test'", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void semicolonInsideLineCommentIsIgnored() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1 -- comment; still comment\n; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1 -- comment; still comment", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void hashLineCommentIsIgnored() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1 # note; here\n; SELECT 2");
        assertEquals(List.of("SELECT 1 # note; here", "SELECT 2"), stmts);
    }

    @Test
    void semicolonInsideBlockCommentIsIgnored() {
        List<String> stmts = SqlScriptSplitter.split("SELECT 1 /* a; b; c */; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT 1 /* a; b; c */", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void quotedIdentifierContainingSemicolon() {
        List<String> stmts = SqlScriptSplitter.split("SELECT \"weird;col\" FROM t; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("SELECT \"weird;col\" FROM t", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void backtickIdentifierContainingSemicolon() {
        List<String> stmts = SqlScriptSplitter.split("SELECT `weird;col` FROM t; SELECT 2");
        assertEquals(List.of("SELECT `weird;col` FROM t", "SELECT 2"), stmts);
    }

    @Test
    void dollarQuotedFunctionBodyWithSemicolons() {
        String sql = """
                CREATE FUNCTION f() RETURNS int AS $$
                BEGIN
                    RETURN 1;
                END;
                $$ LANGUAGE plpgsql;
                SELECT f()""";
        List<String> stmts = SqlScriptSplitter.split(sql);
        assertEquals(2, stmts.size());
        assertTrue(stmts.get(0).startsWith("CREATE FUNCTION f()"), stmts.get(0));
        assertTrue(stmts.get(0).contains("RETURN 1;"), stmts.get(0));
        assertTrue(stmts.get(0).endsWith("LANGUAGE plpgsql"), stmts.get(0));
        assertEquals("SELECT f()", stmts.get(1));
    }

    @Test
    void taggedDollarQuoteWithSemicolons() {
        String sql = "SELECT $func$ a; b; $func$; SELECT 2";
        List<String> stmts = SqlScriptSplitter.split(sql);
        assertEquals(2, stmts.size());
        assertEquals("SELECT $func$ a; b; $func$", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void dollarSignThatIsNotAQuoteIsPlainText() {
        // A price value; the '$' is not a dollar-quote opener.
        List<String> stmts = SqlScriptSplitter.split("UPDATE t SET p = 5 WHERE label = 'US$'; SELECT 2");
        assertEquals(List.of("UPDATE t SET p = 5 WHERE label = 'US$'", "SELECT 2"), stmts);
    }

    @Test
    void emptyInputYieldsNoStatements() {
        assertTrue(SqlScriptSplitter.split("").isEmpty());
        assertTrue(SqlScriptSplitter.split(null).isEmpty());
        assertTrue(SqlScriptSplitter.split("   \n\t ").isEmpty());
    }

    @Test
    void commentOnlyScriptYieldsNoStatementsByDefault() {
        String sql = "-- just a comment\n/* and a block\n   comment */\n# and a hash comment\n";
        assertTrue(SqlScriptSplitter.split(sql).isEmpty());
    }

    @Test
    void commentOnlyFragmentsRetainedWhenKeepCommentsSet() {
        String sql = "-- header\nSELECT 1; -- trailing comment only\n; SELECT 2";
        List<String> kept = SqlScriptSplitter.split(sql, SqlScriptSplitter.Options.keepingComments());
        assertEquals(3, kept.size());
        assertEquals("-- header\nSELECT 1", kept.get(0));
        assertEquals("-- trailing comment only", kept.get(1));
        assertEquals("SELECT 2", kept.get(2));
    }

    @Test
    void leadingCommentIsPreservedOnStatementWithCode() {
        List<String> stmts = SqlScriptSplitter.split("-- explain\nSELECT 1; SELECT 2");
        assertEquals(2, stmts.size());
        assertEquals("-- explain\nSELECT 1", stmts.get(0));
        assertEquals("SELECT 2", stmts.get(1));
    }

    @Test
    void nestedBlockCommentsHonoredWhenEnabled() {
        String sql = "SELECT 1 /* outer /* inner */ still */; SELECT 2";
        SqlScriptSplitter.Options nested = SqlScriptSplitter.Options.defaults().withNestedBlockComments(true);
        assertEquals(List.of("SELECT 1 /* outer /* inner */ still */", "SELECT 2"),
                SqlScriptSplitter.split(sql, nested));

        // Without nesting the first "*/" closes the comment, so "still */" becomes code and the
        // following semicolon still terminates the first statement identically here.
        List<String> flat = SqlScriptSplitter.split(sql);
        assertEquals(2, flat.size());
        assertEquals("SELECT 1 /* outer /* inner */ still */", flat.get(0));
    }

    @Test
    void startOffsetsPointAtFirstNonWhitespaceChar() {
        String sql = "SELECT 1;  \n  SELECT 2";
        List<SqlScriptSplitter.Statement> stmts = SqlScriptSplitter.splitWithOffsets(sql);
        assertEquals(2, stmts.size());
        assertEquals(0, stmts.get(0).startOffset());
        assertEquals("SELECT 1", stmts.get(0).text());
        assertEquals(sql.indexOf("SELECT 2"), stmts.get(1).startOffset());
        assertEquals("SELECT 2", stmts.get(1).text());
    }

    @Test
    void interiorTextPreservedVerbatim() {
        String sql = "SELECT   a,\n       b\nFROM t";
        List<String> stmts = SqlScriptSplitter.split(sql);
        assertEquals(1, stmts.size());
        assertEquals("SELECT   a,\n       b\nFROM t", stmts.get(0));
    }
}
